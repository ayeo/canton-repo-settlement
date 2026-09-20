package pl.ayeo.repo.core;

import com.daml.ledger.javaapi.data.ArchivedEvent;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.Event;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.ayeo.repo.core.courier.Courier;
import pl.ayeo.repo.ledger.Node;
import pl.ayeo.repo.ledger.Refusal;
import pl.ayeo.repo.ledger.Refusals;

public final class Desk {

  private static final Logger log = LoggerFactory.getLogger(Desk.class);

  private final Node io;

  private final Courier courier;

  private final Actor actor;

  private final Mailbox<Object> mailbox = new Mailbox<>();

  private final Codegen codegen = new Codegen();

  private final Set<String> handedAlready = new HashSet<>();

  private volatile RuntimeException streamFailure;
  private volatile boolean stopped;

  private String attempting = "";

  // When the attempt left, not when its refusal came back: the trace sets refusals among
  // transactions stamped at submission, and the answer trails by a whole confirmation round.
  private LocalTime attemptedAt = LocalTime.now();

  public Desk(Node io, Actor actor) {
    this(io, Courier.NONE, actor);
  }

  public Desk(Node io, Courier courier, Actor actor) {
    this.io = io;
    this.courier = courier;
    this.actor = actor;
  }

  public Actor actor() {
    return actor;
  }

  public long learn() {
    long end = io.ledgerEnd();
    io.replay(0L, end, this::heard);
    readMail(false);
    return end;
  }

  public void run(CommandListener listener) {
    long from = learn();
    Thread postman = new Thread(() -> {
      try {
        io.subscribe(from, this::post);
        streamFailure = new IllegalStateException("the ledger update stream ended; restart the desk");
      } catch (RuntimeException failure) {
        streamFailure = new IllegalStateException("the ledger update stream failed; restart the desk", failure);
      }
    }, actor.name() + "-stream");
    postman.setDaemon(true);
    try {
      considerEverything();
      postman.start();
      listener.listen(this::sent);
      log.info("watching (Ctrl-C to stop)");
      while (!Thread.currentThread().isInterrupted()) {
        ensureStream();
        Mailbox.Delivery<Object> delivery = mailbox.take(1000);
        try {
          ensureStream();
          tick(delivery);
        } catch (RuntimeException failure) {
          if (delivery != null && delivery.awaited()) {
            delivery.answered().complete(Reply.error("the desk has stopped: " + failure.getMessage()));
          }
          throw failure;
        }
      }
    } finally {
      stopped = true;
      listener.close();
      mailbox.rejectWaiting(Reply.error("the desk has stopped"));
      postman.interrupt();
    }
  }

  private void ensureStream() {
    if (streamFailure != null) throw streamFailure;
    if (stopped) throw new IllegalStateException("the desk has stopped");
  }

  // A refusal is an ordinary outcome and the next turn decides again; anything else is our fault.
  private void survive(Runnable turn) {
    try {
      turn.run();
    } catch (RuntimeException problem) {
      Refusal refusal = Refusal.of(problem).orElseThrow(() -> problem);
      record(refusal);
    }
  }

  // A consumed mailbox delivery is applied before unrelated reactions can fail.
  void tick(Mailbox.Delivery<Object> delivery) {
    ensureStream();
    if (delivery != null && delivery.message() instanceof Event event) {
      decoded(event, arrival -> survive(() -> tell(arrival)));
    } else if (delivery != null && delivery.message() instanceof Instruction instruction) {
      try {
        Outcome outcome = tell(instruction.message(), "instruction:" + instruction.id());
        Reply reply = outcome.acted() || instruction.message() instanceof Query
            ? Reply.ok(outcome.acted() ? "done" : outcome.why())
            : Reply.notReady(outcome.why());
        delivery.answered().complete(reply);
      } catch (RuntimeException problem) {
        Refusal refusal = Refusal.of(problem).orElseThrow(() -> problem);
        record(refusal);
        delivery.answered().complete(Reply.error(refusal.says()));
      }
    }
    readMail(true);
    considerEverything();
  }

  void considerEverything() {
    actor.onTick().forEach(decision -> survive(() -> submit(decision)));
  }

  public Outcome tell(Object arrival) {
    return tell(arrival, null);
  }

  private Outcome tell(Object arrival, String instructionId) {
    ensureStream();
    Decision next = actor.receive(arrival);
    if (next.commands().isEmpty()) {
      return new Outcome(false, next.says());
    }
    if (next.rejectionContext() == null) next = next.inResponseTo(arrival);
    submit(next, instructionId);
    return new Outcome(true, next.says());
  }

  public record Outcome(boolean acted, String why) {}

  private void record(Refusal refusal) {
    Refusals.record(attemptedAt, actor.name(), attempting, refusal.says());
    log.info("refused: {}", refusal.says());
  }

  private void submit(Decision decision) {
    submit(decision, null);
  }

  private void submit(Decision decision, String instructionId) {
    ensureStream();
    if (decision.commands().isEmpty()) return;
    attempting = decision.says();
    attemptedAt = LocalTime.now();
    try {
      for (Decision.Showing shown : decision.showing()) {
        courier.deliver(shown.to(), io.disclosure(shown.contractId()));
      }
      // Automatic reactions use stable intent + payload. Manual instructions have
      // their own identity, reused only for a retry of that particular instruction.
      String intent = instructionId == null ? decision.says() : instructionId;
      io.submit(intent, decision.commands(), decision.using()).forEach(this::heard);
      log.info(decision.says());
    } catch (RuntimeException problem) {
      Refusal.of(problem).ifPresent(refusal -> actor.onFailed(refusal, decision));
      throw problem;
    }
  }

  private void post(Event event) {
    mailbox.post(event);
  }

  private void decoded(Event event, Consumer<Object> to) {
    if (event instanceof CreatedEvent created) {
      // The read side keeps every contract, including ones the desk has no message for.
      if (actor.contracts().saw(created)) {
        codegen.read(created).ifPresent(to);
      }
    } else if (event instanceof ArchivedEvent archived) {
      if (actor.contracts().archived(new ContractRef(archived.getContractId()))) {
        to.accept(archived);
      }
    }
  }

  private void heard(Event event) {
    decoded(event, actor::receive);
  }

  private CompletableFuture<Reply> sent(Instruction instruction) {
    if (stopped || streamFailure != null) {
      return CompletableFuture.completedFuture(Reply.error("the desk has stopped; restart it"));
    }
    return mailbox.postAwaited(instruction);
  }

  private void readMail(boolean act) {
    for (DisclosedContract contract : courier.collect(io.party())) {
      if (!handedAlready.add(contract.contractId.orElse(""))) {
        continue;
      }
      actor
          .handed(contract)
          .ifPresent(
              message -> {
                if (act) {
                  survive(() -> tell(message));
                } else {
                  actor.receive(message);
                }
              });
    }
  }
}
