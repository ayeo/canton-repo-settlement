package pl.ayeo.repo.actors.icsd;

import com.daml.ledger.javaapi.data.codegen.Contract;
import java.util.Optional;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.icsd.messages.IssueSecurities;
import pl.ayeo.repo.actors.icsd.messages.RegisterInstrument;
import pl.ayeo.repo.core.Actor;
import pl.ayeo.repo.core.Decision;
import pl.ayeo.repo.core.Message;
import pl.ayeo.repo.ledger.Market;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.custody.Instrument;
import pl.ayeo.repo.model.custody.SecurityPosition;
import pl.ayeo.repo.core.render.Says;

public final class IcsdActor extends Actor {

  private final Market market;

  public IcsdActor(Party us, Market market) {
    super(us, ActorRole.ICSD.display());
    this.market = market;
  }

  @Override
  protected Decision decide(Message message) {
    return switch (message) {
      case RegisterInstrument command -> onMessage(command);
      case IssueSecurities command -> onMessage(command);
      default -> nothingToDo();
    };
  }

  protected Decision onMessage(RegisterInstrument command) {
    if (instrument(command.isin()).isPresent()) {
      return Decision.nothing("that instrument is already registered");
    }
    return (
        new Decision(
            Says.registered(command.isin().code()),
            Instrument.create(
                us().id(),
                command.isin().code(),
                command.issuer(),
                command.currency().code(),
                command.price(),
                Party.asText(market.banks()))));
  }

  protected Decision onMessage(IssueSecurities command) {
    // Whether issuing again was meant is decided once, by the command line.
    if (!command.again() && alreadyHolds(new Party(command.owner()), command.isin())) {
      return Decision.nothing("that holder already has this instrument");
    }
    return Decision.provided(
        instrument(command.isin()),
        command.isin().code() + " is not registered - run: repo --as icsd register",
        instrument ->
            new Decision(
                Says.issued(command.quantity(), command.isin().code(), command.owner()),
                instrument.id.exerciseIssue(command.owner(), command.quantity())));
  }

  // ---------------------------------------------------------------- asking ---

  private Optional<Instrument.Contract> instrument(Isin isin) {
    return contracts().every(Instrument.Contract.class).stream()
        .filter(listed -> new Isin(listed.data.isin).equals(isin))
        .findFirst();
  }

  private boolean alreadyHolds(Party owner, Isin isin) {
    return contracts().every(SecurityPosition.Contract.class).stream()
        .anyMatch(
            position ->
                new Party(position.data.owner).equals(owner)
                    && new Isin(position.data.isin).equals(isin));
  }

}
