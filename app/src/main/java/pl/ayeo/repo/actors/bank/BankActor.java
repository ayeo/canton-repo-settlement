package pl.ayeo.repo.actors.bank;

import com.daml.ledger.javaapi.data.ArchivedEvent;
import com.daml.ledger.javaapi.data.codegen.Contract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import pl.ayeo.repo.actors.bank.messages.SettleLeg;
import pl.ayeo.repo.actors.bank.messages.ShowCash;
import pl.ayeo.repo.actors.bank.messages.ShowPositions;
import pl.ayeo.repo.actors.bank.messages.ShowTrades;
import pl.ayeo.repo.actors.dealer.messages.CommitCollateral;
import pl.ayeo.repo.actors.dealer.messages.ShowQuote;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.Reference;
import pl.ayeo.repo.actors.domain.Terms;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.actors.lender.messages.CollateralShown;
import pl.ayeo.repo.actors.lender.messages.PublishSchedule;
import pl.ayeo.repo.core.Actor;
import pl.ayeo.repo.core.ContractRef;
import pl.ayeo.repo.core.Decision;
import pl.ayeo.repo.core.Message;
import pl.ayeo.repo.ledger.Market;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.calendar.BusinessDate;
import pl.ayeo.repo.model.cash.AllocatedCash;
import pl.ayeo.repo.model.cash.CashBalance;
import pl.ayeo.repo.model.custody.AllocatedSecurity;
import pl.ayeo.repo.model.custody.SecurityPosition;
import pl.ayeo.repo.model.repo.OpenRepo;
import pl.ayeo.repo.model.repo.RepoTrade;
import pl.ayeo.repo.core.render.Amounts;
import pl.ayeo.repo.core.render.Says;
import pl.ayeo.repo.core.render.Show;

public abstract class BankActor extends Actor {

  private Today today;

  protected final Market market;

  protected BankActor(Party us, Market market, String name) {
    super(us, name);
    this.market = market;
  }

  // ------------------------------------------------------------ taking note ---

  @Override
  protected final Decision decide(Message message) {
    return switch (message) {
      case SettleLeg command -> onMessage(command);
      case CommitCollateral command -> onMessage(command);
      case ShowQuote command -> onMessage(command);
      case PublishSchedule command -> onMessage(command);
      case CollateralShown collateral -> onMessage(collateral);
      case ShowPositions question -> onMessage(question);
      case ShowCash question -> onMessage(question);
      case ShowTrades question -> onMessage(question);
      default -> nothingToDo();
    };
  }

  @Override
  protected Decision onContract(Contract<?, ?> contract) {
    if (contract instanceof BusinessDate.Contract held) {
      today = new Today(held.id, held.data.current);
    }
    return nothingToDo();
  }

  public record Today(BusinessDate.ContractId id, LocalDate current) {

    public boolean reached(LocalDate date) {
      return !current.isBefore(date);
    }

    public ContractRef contractId() {
      return new ContractRef(id.contractId);
    }
  }

  @Override
  protected Decision onArchived(ArchivedEvent archived) {
    ContractRef contract = ContractRef.of(archived);
    if (today != null && today.contractId().equals(contract)) {
      today = null;
    }
    return nothingToDo();
  }

  protected Decision onMessage(CommitCollateral command) {
    return nothingToDo();
  }

  protected Decision onMessage(ShowQuote command) {
    return nothingToDo();
  }

  protected Decision onMessage(PublishSchedule command) {
    return nothingToDo();
  }

  protected Decision onMessage(CollateralShown collateral) {
    return nothingToDo();
  }

  protected Decision onMessage(ShowPositions question) {
    return Decision.nothing(Show.positions(us(), market.icsd(), contracts()));
  }

  protected Decision onMessage(ShowCash question) {
    return Decision.nothing(Show.cash(us(), contracts()));
  }

  protected Decision onMessage(ShowTrades question) {
    return Decision.nothing(Show.trades(contracts()));
  }

  protected abstract Decision onMessage(SettleLeg command);

  // ------------------------------------------------------------- both sides ---

  protected Decision commit(
      BigDecimal owed, Currency currency, Party counterparty, Reference reference) {
    List<CashBalance.Contract> ours =
        contracts().every(CashBalance.Contract.class).stream()
            .filter(held -> new Currency(held.data.currency).equals(currency))
            .sorted(Comparator.comparing((CashBalance.Contract held) -> held.data.amount).reversed())
            .toList();
    if (ours.isEmpty()) {
      return Decision.nothing("we hold nothing in " + currency.code());
    }
    CashBalance.Contract biggest = ours.get(0);
    if (biggest.data.amount.compareTo(owed) < 0) {
      if (ours.size() < 2) {
        return Decision.nothing("not enough " + currency + ": we need " + Amounts.money(owed));
      }
      return new Decision(
          Says.merged(owed, currency.code()),
          biggest.id.exerciseMergeCash(ours.get(1).id));
    }
    return new Decision(
        Says.allocatedCash(owed, currency.code(), reference.name()),
        biggest.id.exerciseAllocateCash(owed, counterparty.id(), reference.name()));
  }

  // ---------------------------------------------------------------- asking ---

  protected Optional<Today> day() {
    return Optional.ofNullable(today);
  }

  protected Optional<LocalDate> today() {
    return day().map(Today::current);
  }

  protected boolean reached(LocalDate due) {
    return day().map(day -> day.reached(due)).orElse(false);
  }

  protected Optional<SecurityPosition.Contract> positionCovering(Isin isin, BigDecimal quantity) {
    return contracts().every(SecurityPosition.Contract.class).stream()
        .filter(
            held ->
                new Isin(held.data.isin).equals(isin)
                    && held.data.quantity.compareTo(quantity) >= 0)
        .findFirst();
  }

  public Optional<AllocatedSecurity.Contract> collateralFor(Reference reference) {
    return contracts().every(AllocatedSecurity.Contract.class).stream()
        .filter(hold -> Reference.of(hold.data.reference).equals(reference))
        .findFirst();
  }

  protected Optional<AllocatedCash.Contract> cashFor(Reference reference) {
    return contracts().every(AllocatedCash.Contract.class).stream()
        .filter(hold -> Reference.of(hold.data.reference).equals(reference))
        .findFirst();
  }

  public List<RepoTrade.Contract> agreed() {
    return contracts().every(RepoTrade.Contract.class);
  }

  public List<OpenRepo.Contract> open() {
    return contracts().every(OpenRepo.Contract.class);
  }

  public Optional<RepoTrade.Contract> agreed(TradeId tradeId) {
    return agreed().stream()
        .filter(trade -> Terms.of(trade.data.terms).tradeId().equals(tradeId))
        .findFirst();
  }

  public Optional<OpenRepo.Contract> open(TradeId tradeId) {
    return open().stream()
        .filter(trade -> Terms.of(trade.data.terms).tradeId().equals(tradeId))
        .findFirst();
  }

  protected Optional<RepoTrade.Contract> agreedFor(Reference reference) {
    return agreed().stream()
        .filter(trade -> Terms.of(trade.data.terms).openingReference().equals(reference))
        .findFirst();
  }

}
