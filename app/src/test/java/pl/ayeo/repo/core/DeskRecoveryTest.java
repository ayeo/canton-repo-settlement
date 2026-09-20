package pl.ayeo.repo.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.ayeo.repo.testing.TestNode.*;

import com.daml.ledger.javaapi.data.*;
import com.daml.ledger.javaapi.data.codegen.Contract;
import com.daml.ledger.javaapi.data.codegen.Update;
import io.grpc.Status;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import pl.ayeo.repo.actors.*;
import pl.ayeo.repo.actors.centralbank.CentralBankActor;
import pl.ayeo.repo.actors.centralbank.messages.*;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.actors.lender.LenderActor;
import pl.ayeo.repo.ledger.Node;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.calendar.BusinessDate;
import pl.ayeo.repo.model.cash.CashBalance;
import pl.ayeo.repo.testing.*;

class DeskRecoveryTest {
  private static final class TestIo implements Node {
    final TestNode history;
    final List<String> attempts = new ArrayList<>();
    BiFunction<String, List<Update<?>>, List<Event>> submission = (intent, commands) -> List.of();
    Consumer<Consumer<Event>> stream = ignored -> {};
    TestIo(TestNode history) { this.history = history; }
    public Party party() { return history.party(); }
    public Party party(String key) { return history.party(key); }
    public long ledgerEnd() { return history.ledgerEnd(); }
    public void replay(long from, long to, Consumer<Event> handler) { history.replay(from, to, handler); }
    public void subscribe(long from, Consumer<Event> handler) { stream.accept(handler); }
    public DisclosedContract disclosure(ContractRef id) { return history.disclosure(id); }
    public List<Event> submit(String intent, List<Update<?>> commands, List<DisclosedContract> disclosed) {
      attempts.add(intent);
      return submission.apply(intent, commands);
    }
  }

  private static LenderActor lender(Node io) {
    return new LenderActor(ActorRole.BRAVO, io.party(), Actors.market(io));
  }

  private static RuntimeException modelRefusal() {
    return Status.INVALID_ARGUMENT.withDescription("DAML_FAILURE(9,abc): wrong price").asRuntimeException();
  }

  private static Event last(TestNode node) {
    List<Event> events = new ArrayList<>();
    node.replay(0, node.ledgerEnd(), events::add);
    return events.getLast();
  }

  @Test void appliesTheQueuedEventEvenWhenNewDisclosureTriggersARefusal() {
    TestNode history = new TestNode(ActorRole.BRAVO).schedule().instrument().offerTo(LENDER, "BAD");
    TestIo io = new TestIo(history);
    io.submission = (intent, commands) -> { throw modelRefusal(); };
    TestCourier courier = new TestCourier();
    LenderActor actor = lender(io);
    Desk desk = new Desk(io, courier, actor);
    desk.learn();
    courier.shown(io.party(), "sec-BAD");
    desk.tick(new Mailbox.Delivery<>(last(history.day(TODAY)), null));
    assertEquals(1, actor.contracts().every(BusinessDate.Contract.class).size());
    assertTrue(actor.offer(new TradeId("BAD")).isEmpty());
  }

  @Test void refusesBadOfferOnceAndContinuesWithTheNextOffer() {
    TestIo io = new TestIo(new TestNode(ActorRole.BRAVO).schedule().instrument()
        .offerTo(LENDER, "BAD").offerTo(LENDER, "GOOD"));
    io.submission = (intent, commands) -> {
      if (intent.contains("BAD")) throw modelRefusal();
      return List.of();
    };
    LenderActor actor = lender(io);
    Desk desk = new Desk(io, new TestCourier().shown(io.party(), "sec-BAD").shown(io.party(), "sec-GOOD"), actor);
    desk.learn();
    desk.considerEverything();
    desk.considerEverything();
    assertEquals(1, io.attempts.stream().filter(intent -> intent.contains("BAD")).count());
    assertTrue(io.attempts.stream().anyMatch(intent -> intent.contains("GOOD")));
    assertTrue(actor.offer(new TradeId("BAD")).isEmpty());
  }

  @Test void retriesAnOfferAfterTemporaryTransportFailure() {
    TestIo io = new TestIo(new TestNode(ActorRole.BRAVO).schedule().instrument().offerTo(LENDER, "REPO-1"));
    LenderActor actor = lender(io);
    Desk desk = new Desk(io, new TestCourier().shown(io.party(), "sec-REPO-1"), actor);
    desk.learn();
    io.submission = (intent, commands) -> { throw Status.UNAVAILABLE.asRuntimeException(); };
    assertThrows(RuntimeException.class, () -> desk.tell(actor.offer(new TradeId("REPO-1")).orElseThrow()));
    assertTrue(actor.offer(new TradeId("REPO-1")).isPresent());
    io.submission = (intent, commands) -> List.of();
    desk.considerEverything();
    assertEquals(2, io.attempts.size());
    assertEquals(io.attempts.getFirst(), io.attempts.getLast());
  }

  @Test void retriesCalendarRollAfterSubmissionFailure() {
    TestIo io = new TestIo(new TestNode(ActorRole.CENTRAL_BANK).day(TODAY));
    Desk desk = new Desk(io, new CentralBankActor(io.party(), Actors.market(io)));
    desk.learn();
    io.submission = (intent, commands) -> { throw Status.UNAVAILABLE.asRuntimeException(); };
    assertThrows(RuntimeException.class, () -> desk.tell(new OpenSettlementDay(null)));
    io.submission = (intent, commands) -> List.of();
    assertTrue(desk.tell(new OpenSettlementDay(null)).acted());
    assertEquals(io.attempts.getFirst(), io.attempts.getLast());
  }

  @Test void duplicateCreatesCannotResurrectOrNotifyTheActorAgain() {
    TestNode history = new TestNode(ActorRole.CENTRAL_BANK).balance(DEALER, "10");
    CreatedEvent created = (CreatedEvent) last(history);
    AtomicInteger callbacks = new AtomicInteger();
    Actor actor = new Actor(history.party(), "counter") {
      protected Decision decide(Message message) { return nothingToDo(); }
      protected Decision onContract(Contract<?, ?> contract) { callbacks.incrementAndGet(); return nothingToDo(); }
    };
    Desk desk = new Desk(history, actor);
    desk.learn();
    desk.tick(new Mailbox.Delivery<>(TestNode.gone(new ContractRef(created.getContractId()), CashBalance.TEMPLATE_ID), null));
    desk.tick(new Mailbox.Delivery<>(created, null));
    assertTrue(actor.contracts().every(CashBalance.Contract.class).isEmpty());
    assertEquals(1, callbacks.get());
  }

  @Test void streamFailureStopsTheDeskAndClosesItsListener() throws Exception {
    TestIo io = new TestIo(new TestNode(ActorRole.CENTRAL_BANK));
    io.stream = ignored -> { throw Status.UNAVAILABLE.asRuntimeException(); };
    AtomicBoolean closed = new AtomicBoolean();
    CommandListener listener = new CommandListener() {
      public void listen(Function<Instruction, CompletableFuture<Reply>> desk) {}
      public void close() { closed.set(true); }
    };
    Desk desk = new Desk(io, new CentralBankActor(io.party(), Actors.market(io)));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> run = executor.submit(() -> desk.run(listener));
      ExecutionException failure = assertThrows(ExecutionException.class, () -> run.get(4, TimeUnit.SECONDS));
      assertTrue(failure.getCause().getMessage().contains("stream failed"));
      assertTrue(closed.get());
      assertTrue(io.attempts.isEmpty());
      assertThrows(IllegalStateException.class, () -> desk.tell(new OpenSettlementDay(null)));
    } finally { executor.shutdownNow(); }
  }

  @Test void newFundingInstructionsHaveDistinctIdentitiesButRetriesKeepTheirIdentity() {
    TestIo io = new TestIo(new TestNode(ActorRole.CENTRAL_BANK));
    Desk desk = new Desk(io, new CentralBankActor(io.party(), Actors.market(io)));
    FundAccount command = new FundAccount(DEALER, new pl.ayeo.repo.actors.domain.Currency("EUR"), BigDecimal.TEN, true);
    for (String id : List.of("first", "second", "second")) {
      CompletableFuture<Reply> reply = new CompletableFuture<>();
      desk.tick(new Mailbox.Delivery<>(new Instruction(id, command), reply));
      assertEquals(Reply.Status.OK, reply.join().status());
    }
    assertNotEquals(io.attempts.get(0), io.attempts.get(1));
    assertEquals(io.attempts.get(1), io.attempts.get(2));
  }

  @Test void instructionsDistinguishNotReadyFromFailureAndSuccessfulQuery() {
    TestIo io = new TestIo(new TestNode(ActorRole.BRAVO));
    Desk desk = new Desk(io, lender(io));
    CompletableFuture<Reply> waiting = new CompletableFuture<>();
    desk.tick(new Mailbox.Delivery<>(new Instruction("settle", new pl.ayeo.repo.actors.bank.messages.SettleLeg(new TradeId("missing"))), waiting));
    assertEquals(2, waiting.join().exitCode());
    CompletableFuture<Reply> query = new CompletableFuture<>();
    desk.tick(new Mailbox.Delivery<>(new Instruction("query", new pl.ayeo.repo.actors.bank.messages.ShowCash()), query));
    assertEquals(0, query.join().exitCode());
  }
  @Test void reportsSubmissionFailureAsAnErrorReply() {
    TestIo io = new TestIo(new TestNode(ActorRole.CENTRAL_BANK));
    io.submission = (intent, commands) -> { throw Status.UNAVAILABLE.asRuntimeException(); };
    Desk desk = new Desk(io, new CentralBankActor(io.party(), Actors.market(io)));
    var command = new FundAccount(DEALER, new pl.ayeo.repo.actors.domain.Currency("EUR"), BigDecimal.TEN, true);
    CompletableFuture<Reply> answer = new CompletableFuture<>();
    desk.tick(new Mailbox.Delivery<>(new Instruction("fund", command), answer));
    assertEquals(1, answer.join().exitCode());
  }

  @Test void delayedStreamDoesNotUndoEventsReturnedBySuccessiveSubmissions() {
    TestNode history = new TestNode(ActorRole.CENTRAL_BANK).balance(DEALER, "10");
    Event created = last(history);
    var archived = TestNode.gone(new ContractRef(((CreatedEvent) created).getContractId()), CashBalance.TEMPLATE_ID);
    TestIo io = new TestIo(new TestNode(ActorRole.CENTRAL_BANK));
    AtomicInteger submissions = new AtomicInteger();
    io.submission = (intent, commands) -> submissions.getAndIncrement() == 0 ? List.of(created) : List.of(archived);
    AtomicInteger callbacks = new AtomicInteger();
    Actor actor = new Actor(io.party(), "counter") {
      protected Decision decide(Message message) {
        return new Decision("test command", CashBalance.create(CENTRAL_BANK, DEALER, "EUR", BigDecimal.TEN));
      }
      protected Decision onContract(Contract<?, ?> contract) { callbacks.incrementAndGet(); return nothingToDo(); }
    };
    Desk desk = new Desk(io, actor);
    var instruction = new OpenSettlementDay(null);
    desk.tell(instruction);
    desk.tell(instruction);
    desk.tick(new Mailbox.Delivery<>(created, null));
    desk.tick(new Mailbox.Delivery<>(archived, null));
    assertTrue(actor.contracts().every(CashBalance.Contract.class).isEmpty());
    assertEquals(1, callbacks.get());
  }

}
