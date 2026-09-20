package pl.ayeo.repo.ledger;

import com.daml.ledger.api.v2.CommandServiceGrpc;
import com.daml.ledger.api.v2.EventQueryServiceGrpc;
import com.daml.ledger.api.v2.EventQueryServiceOuterClass;
import com.daml.ledger.api.v2.StateServiceGrpc;
import com.daml.ledger.api.v2.StateServiceOuterClass;
import com.daml.ledger.api.v2.UpdateServiceGrpc;
import com.daml.ledger.api.v2.UpdateServiceOuterClass;
import com.daml.ledger.javaapi.data.Command;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.CumulativeFilter;
import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.Event;
import com.daml.ledger.javaapi.data.EventFormat;
import com.daml.ledger.javaapi.data.Filter;
import com.daml.ledger.javaapi.data.GetUpdatesRequest;
import com.daml.ledger.javaapi.data.GetUpdatesResponse;
import com.daml.ledger.javaapi.data.SubmitAndWaitForTransactionRequest;
import com.daml.ledger.javaapi.data.SubmitAndWaitForTransactionResponse;
import com.daml.ledger.javaapi.data.Transaction;
import com.daml.ledger.javaapi.data.TransactionFormat;
import com.daml.ledger.javaapi.data.TransactionShape;
import com.daml.ledger.javaapi.data.UpdateFormat;
import com.daml.ledger.javaapi.data.UpdateSubmission;
import com.daml.ledger.javaapi.data.codegen.Update;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class LedgerClient implements AutoCloseable {

  private static final String APPLICATION_ID = "repo-poc";

  private final ManagedChannel channel;
  private final CommandServiceGrpc.CommandServiceBlockingStub commands;
  private final StateServiceGrpc.StateServiceBlockingStub state;
  private final UpdateServiceGrpc.UpdateServiceBlockingStub updates;
  private final EventQueryServiceGrpc.EventQueryServiceBlockingStub events;

  private LedgerClient(ManagedChannel channel) {
    this.channel = channel;
    this.commands = CommandServiceGrpc.newBlockingStub(channel);
    this.state = StateServiceGrpc.newBlockingStub(channel);
    this.updates = UpdateServiceGrpc.newBlockingStub(channel);
    this.events = EventQueryServiceGrpc.newBlockingStub(channel);
  }

  public static LedgerClient connect(LedgerConfig.Role role) {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress(role.host(), role.port()).usePlaintext().build();
    return new LedgerClient(channel);
  }

  public List<Event> submit(
      String actAs, String intent, List<Update<?>> updates, List<DisclosedContract> disclosed) {
    // The helper takes one command; the full list is set below, so all of them
    // go into one transaction.
    if (updates.isEmpty()) {
      throw new IllegalArgumentException("a submission needs at least one command");
    }
    UpdateSubmission<?> submission =
        UpdateSubmission.create(APPLICATION_ID, changeId(actAs, intent, updates), updates.get(0))
            .withActAs(actAs);

    // Ask for the transaction back rather than reading for it afterwards: the
    // update service's ledger end can lag behind the commit, so a read right
    // after submitting may find nothing.
    EventFormat everything = eventsFor(actAs, Filter.Wildcard.HIDE_CREATED_EVENT_BLOB);

    SubmitAndWaitForTransactionResponse response =
        SubmitAndWaitForTransactionResponse.fromProto(
            commands.withDeadlineAfter(90, TimeUnit.SECONDS).submitAndWaitForTransaction(
                new SubmitAndWaitForTransactionRequest(
                        submission
                            .toCommandsSubmission()
                            .withCommands(updates)
                            .withDisclosedContracts(disclosed),
                        new TransactionFormat(everything, TransactionShape.ACS_DELTA))
                    .toProto()));
    return response.getTransaction().getEvents();
  }

  private static String changeId(String actAs, String intent, List<Update<?>> updates) {
    StringBuilder change = new StringBuilder(actAs).append(' ').append(intent);
    for (Update<?> update : updates) {
      for (Command command : update.commands()) {
        change.append(' ').append(command.toProtoCommand());
      }
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(change.toString().getBytes(StandardCharsets.UTF_8));
      return "repo-" + HexFormat.of().formatHex(digest).substring(0, 32);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is not available", unavailable);
    }
  }

  public DisclosedContract disclosure(String readAs, String contractId) {
    EventFormat withBlob = eventsFor(readAs, Filter.Wildcard.INCLUDE_CREATED_EVENT_BLOB);
    CreatedEvent created =
        CreatedEvent.fromProto(
            events
                .getEventsByContractId(
                    EventQueryServiceOuterClass.GetEventsByContractIdRequest.newBuilder()
                        .setContractId(contractId)
                        .setEventFormat(withBlob.toProto())
                        .build())
                .getCreated()
                .getCreatedEvent());
    return new DisclosedContract(
        created.getTemplateId(), contractId, created.getCreatedEventBlob());
  }

  public long ledgerEnd() {
    return state
        .getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build())
        .getOffset();
  }

  public void watchAll(String readAs, long fromOffset, Consumer<Event> handler) {
    EventFormat everything = eventsFor(readAs, Filter.Wildcard.HIDE_CREATED_EVENT_BLOB);
    GetUpdatesRequest request =
        new GetUpdatesRequest(
            fromOffset,
            Optional.empty(),
            new UpdateFormat(
                Optional.of(new TransactionFormat(everything, TransactionShape.ACS_DELTA)),
                Optional.empty(),
                Optional.empty()));

    Iterator<UpdateServiceOuterClass.GetUpdatesResponse> stream =
        updates.getUpdates(request.toProto());
    while (stream.hasNext()) {
      GetUpdatesResponse.fromProto(stream.next())
          .getTransaction()
          .ifPresent(
              transaction -> transaction.getEvents().forEach(handler));
    }
  }

  public List<Transaction> transactions(String readAs, long fromExclusive, long toInclusive) {
    return transactions(readAs, fromExclusive, toInclusive, TransactionShape.ACS_DELTA);
  }

  public List<Transaction> transactions(
      String readAs, long fromExclusive, long toInclusive, TransactionShape shape) {
    EventFormat everything = eventsFor(readAs, Filter.Wildcard.HIDE_CREATED_EVENT_BLOB);

    GetUpdatesRequest request =
        new GetUpdatesRequest(
            fromExclusive,
            Optional.of(toInclusive),
            new UpdateFormat(
                Optional.of(new TransactionFormat(everything, shape)),
                Optional.empty(),
                Optional.empty()));

    List<Transaction> collected = new ArrayList<>();
    Iterator<UpdateServiceOuterClass.GetUpdatesResponse> stream =
        updates.getUpdates(request.toProto());
    while (stream.hasNext()) {
      GetUpdatesResponse.fromProto(stream.next()).getTransaction().ifPresent(collected::add);
    }
    return collected;
  }

  private static EventFormat eventsFor(String party, Filter.Wildcard wildcard) {
    return new EventFormat(
        Map.of(party, new CumulativeFilter(Map.of(), Map.of(), Optional.of(wildcard))),
        Optional.empty(),
        true);
  }

  @Override
  public void close() {
    channel.shutdown();
    try {
      channel.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
