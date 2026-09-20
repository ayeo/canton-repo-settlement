package pl.ayeo.repo.actors.centralbank;

import com.daml.ledger.javaapi.data.codegen.Contract;
import java.time.LocalDate;
import java.util.stream.Stream;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.centralbank.messages.FundAccount;
import pl.ayeo.repo.actors.centralbank.messages.OpenSettlementDay;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.core.Actor;
import pl.ayeo.repo.core.Decision;
import pl.ayeo.repo.core.Message;
import pl.ayeo.repo.ledger.Market;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.calendar.BusinessDate;
import pl.ayeo.repo.model.cash.CashBalance;
import pl.ayeo.repo.core.render.Says;

public final class CentralBankActor extends Actor {

  private Today today;

  private LocalDate rollingTo;

  private BusinessDate.ContractId rolled;

  private final Market market;

  public CentralBankActor(Party us, Market market) {
    super(us, ActorRole.CENTRAL_BANK.display());
    this.market = market;
  }

  @Override
  protected Decision decide(Message message) {
    return switch (message) {
      case FundAccount command -> onMessage(command);
      case OpenSettlementDay command -> onMessage(command);
      default -> nothingToDo();
    };
  }

  protected Decision onMessage(FundAccount command) {
    if (!command.again() && hasBalance(new Party(command.owner()), command.currency())) {
      return Decision.nothing("that account already has a balance in " + command.currency());
    }
    return (
        new Decision(
            Says.credited(command.owner(), command.amount()),
            CashBalance.create(
                us().id(), command.owner(), command.currency().code(), command.amount())));
  }

  protected Decision onMessage(OpenSettlementDay command) {
    if (today == null) {
      LocalDate now = LocalDate.now();
      LocalDate first =
          Calendar.isBusinessDay(now) ? now : Calendar.nextBusinessDay(now);
      // Fixed before the first step: opening a day is itself a step.
      rollingTo = command.target() != null ? command.target() : first;
      return (
          new Decision(
              Says.openedDay(first),
              BusinessDate.create(us().id(), first, Party.asText(market.everyoneWhoSettles()))));
    }
    if (command.target() != null) {
      rollingTo = command.target();
    } else if (rollingTo == null) {
      rollingTo = Calendar.nextBusinessDay(today.current());
    }
    if (!today.current().isBefore(rollingTo)) {
      rollingTo = null;
      return Decision.nothing("the market has already reached that date");
    }
    return step();
  }

  private Decision step() {
    if (today == null || rollingTo == null) {
      return nothingToDo();
    }
    if (!today.current().isBefore(rollingTo)) {
      rollingTo = null;
      return nothingToDo();
    }
    if (today.id().equals(rolled)) {
      return Decision.nothing("a roll is already on its way from this calendar");
    }
    rolled = today.id();
    LocalDate next = Calendar.nextBusinessDay(today.current());
    return new Decision(
        Says.rolledDay(today.current(), next), today.id().exerciseRollBusinessDate(next)).inResponseTo(today.id());
  }

  @Override
  protected Stream<Decision> onTick() {
    return Stream.of(step());
  }

  @Override
  protected Decision onContract(Contract<?, ?> contract) {
    switch (contract) {
      case BusinessDate.Contract held -> today = new Today(held.id, held.data.current);
      default -> {}
    }
    return nothingToDo();
  }

  @Override
  protected void onFailed(pl.ayeo.repo.ledger.Refusal refusal, Decision decision) {
    if (decision.rejectionContext() instanceof BusinessDate.ContractId id && id.equals(rolled)) {
      // A retry consumes the same contract and uses the same command ID.
      rolled = null;
    }
    super.onFailed(refusal, decision);
  }

  private record Today(BusinessDate.ContractId id, LocalDate current) {}

  // ---------------------------------------------------------------- asking ---

  private boolean hasBalance(Party owner, Currency currency) {
    return contracts().every(CashBalance.Contract.class).stream()
        .anyMatch(
            balance ->
                new Party(balance.data.owner).equals(owner)
                    && new Currency(balance.data.currency).equals(currency));
  }

}
