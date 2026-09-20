package pl.ayeo.repo.core;

import com.daml.ledger.javaapi.data.ArchivedEvent;
import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.codegen.Contract;
import java.util.Optional;
import java.util.stream.Stream;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.ledger.Refusal;

public abstract class Actor {

  private final Party us;

  private final String name;

  private final Contracts contracts = new Contracts();

  protected Actor(Party us, String name) {
    this.us = us;
    this.name = name;
  }

  public String name() {
    return name;
  }

  protected final Party us() {
    return us;
  }

  public Contracts contracts() {
    return contracts;
  }

  public final Decision receive(Object arrival) {
    return switch (arrival) {
      case Contract<?, ?> contract -> onContract(contract);
      case ArchivedEvent archived -> onArchived(archived);
      case Message instruction -> decide(instruction);
      default -> nothingToDo();
    };
  }

  protected Decision onContract(Contract<?, ?> contract) {
    return nothingToDo();
  }

  protected Decision onArchived(ArchivedEvent archived) {
    return nothingToDo();
  }

  protected void onRejected(Refusal refusal, Object arrival) {}

  protected void onFailed(Refusal refusal, Decision decision) {
    if (refusal.byModel()) {
      onRejected(refusal, decision.rejectionContext());
    }
  }

  protected abstract Decision decide(Message message);

  protected Stream<Decision> onTick() {
    return Stream.empty();
  }

  protected Optional<Message> handed(DisclosedContract contract) {
    return Optional.empty();
  }

  protected final Decision nothingToDo() {
    return Decision.nothing("a " + name + " has nothing to say about that");
  }
}
