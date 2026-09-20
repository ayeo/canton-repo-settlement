package pl.ayeo.repo.actors.lender;

import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.codegen.Contract;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.bank.BankActor;
import pl.ayeo.repo.actors.bank.messages.SettleLeg;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.Reference;
import pl.ayeo.repo.actors.domain.Terms;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.actors.lender.messages.CollateralShown;
import pl.ayeo.repo.actors.lender.messages.PublishSchedule;
import pl.ayeo.repo.core.ContractRef;
import pl.ayeo.repo.core.Decision;
import pl.ayeo.repo.core.Message;
import pl.ayeo.repo.ledger.Market;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.ledger.Refusal;
import pl.ayeo.repo.model.cash.AllocatedCash;
import pl.ayeo.repo.model.custody.AllocatedSecurity;
import pl.ayeo.repo.model.custody.Instrument;
import pl.ayeo.repo.model.repo.CollateralPolicy;
import pl.ayeo.repo.model.repo.EligibleCollateral;
import pl.ayeo.repo.model.repo.OpenRepo;
import pl.ayeo.repo.model.repo.RepoProposal;
import pl.ayeo.repo.model.repo.RepoTrade;
import pl.ayeo.repo.core.render.Says;

public final class LenderActor extends BankActor {

  private final Map<ContractRef, DisclosedContract> shown = new LinkedHashMap<>();

  private final Set<ContractRef> refused = new HashSet<>();

  public LenderActor(ActorRole role, Party us, Market market) {
    super(us, market, role.display());
  }

  @Override
  protected Decision onContract(Contract<?, ?> contract) {
    return switch (contract) {
      // Acted on at once: everything else it needs may already be here.
      case RepoProposal.Contract offer -> onOffer(offer);
      default -> super.onContract(contract);
    };
  }

  @Override
  protected void onRejected(Refusal refusal, Object arrival) {
    if (arrival instanceof RepoProposal.Contract offer) {
      refused.add(ContractRef.of(offer));
    }
  }

  private List<RepoProposal.Contract> offers() {
    return contracts().every(RepoProposal.Contract.class).stream()
        .filter(offer -> !refused.contains(ContractRef.of(offer)))
        .toList();
  }

  private Optional<Instrument.Contract> instrument(Isin isin) {
    return contracts().every(Instrument.Contract.class).stream()
        .filter(listed -> new Isin(listed.data.isin).equals(isin))
        .findFirst();
  }

  @Override
  protected Optional<Message> handed(DisclosedContract contract) {
    return Optional.of(new CollateralShown(contract));
  }

  @Override
  protected Stream<Decision> onTick() {
    // Each walks a fresh list: acting on one archives another, and the events off
    // our own transaction are read before the sending returns.
    return Stream.of(
            contracts().every(AllocatedSecurity.Contract.class).stream()
                .map(this::considerPaperHold),
            open().stream().map(this::considerRepo),
            offers().stream().map(this::onOffer))
        .flatMap(considered -> considered);
  }

  // ---------------------------------------------------------------- acting ---

  private Decision onOffer(RepoProposal.Contract offer) {
    Terms terms = Terms.of(offer.data.terms);
    Optional<CollateralPolicy.Contract> ours = ourSchedule();
    if (ours.isEmpty()) {
      return Decision.nothing("we have published no collateral schedule to take an offer against");
    }
    CollateralPolicy.Contract published = ours.get();
    if (!new Currency(published.data.currency).equals(terms.currency())) {
      // The ledger refuses this too; saying it here saves a transaction.
      return Decision.nothing(
          "we lend in "
              + new Currency(published.data.currency)
              + " and that offer is in "
              + terms.currency());
    }
    // The hold is not on our node: unless shown it, we cannot name it.
    Optional<DisclosedContract> collateral = shownFor(offer);
    if (collateral.isEmpty()) {
      return Decision.nothing("the dealer has not shown us the collateral behind that offer");
    }
    // The model checks the quoted price against the depository's instrument.
    Optional<Instrument.Contract> listed = instrument(terms.isin());
    if (listed.isEmpty()) {
      return Decision.nothing("the depository has not told us what " + terms.isin() + " is worth");
    }
    return new Decision(
            Says.accepted(terms.tradeId().id()),
            offer.id.exerciseAcceptProposal(published.id, listed.get().id))
        .using(collateral.get())
        .inResponseTo(offer);
  }

  @Override
  protected Decision onMessage(CollateralShown collateral) {
    shown.put(collateral.contractId(), collateral.contract());
    return Decision.provided(
        offers().stream()
            .filter(
                offer ->
                    new ContractRef(offer.data.collateral.contractId)
                        .equals(collateral.contractId()))
            .findFirst(),
        "no offer of ours rests on that collateral",
        this::onOffer);
  }

  private Optional<DisclosedContract> shownFor(RepoProposal.Contract offer) {
    return Optional.ofNullable(shown.get(new ContractRef(offer.data.collateral.contractId)));
  }

  // Package-private for the test beside it, which reads the sentence back.
  Decision considerPaperHold(AllocatedSecurity.Contract held) {
    Reference reference = Reference.of(held.data.reference);
    Optional<RepoTrade.Contract> waiting = agreedFor(reference);
    if (waiting.isEmpty()) {
      return Decision.nothing("this collateral is not behind a trade of ours");
    }
    RepoTrade trade = waiting.get().data;
    if (!us().equals(new Party(trade.buyer))) {
      return Decision.nothing("we are not the buyer of that trade");
    }
    if (cashFor(reference).isPresent()) {
      return Decision.nothing("the cash is already committed");
    }
    Terms terms = Terms.of(trade.terms);
    return commit(
        terms.purchasePrice(), terms.currency(), new Party(trade.seller), reference);
  }

  // Package-private for the test beside it, which reads the sentence back.
  Decision considerRepo(OpenRepo.Contract running) {
    OpenRepo repo = running.data;
    if (!us().equals(new Party(repo.buyer))) {
      return Decision.nothing("we do not hold the collateral for this repo");
    }
    Terms terms = Terms.of(repo.terms);
    if (!reached(terms.repurchaseDate())) {
      return Decision.nothing("the repurchase date has not arrived");
    }
    Reference reference = terms.closingReference();
    if (collateralFor(reference).isPresent()) {
      return Decision.nothing("the collateral is already committed back");
    }
    return Decision.provided(
        positionCovering(terms.isin(), terms.quantity()),
        "no free position of " + terms.isin() + " is large enough to return",
        position ->
            new Decision(
                Says.returned(reference.name()),
                position.id.exerciseAllocateSecurity(
                    terms.quantity(), Optional.of(repo.seller), reference.name())));
  }

  @Override
  protected Decision onMessage(SettleLeg command) {
    Optional<RepoTrade.Contract> found = agreed(command.tradeId());
    if (found.isEmpty()) {
      return Decision.nothing("we have no agreed trade " + command.tradeId() + " to settle");
    }
    RepoTrade.Contract agreed = found.get();
    RepoTrade trade = agreed.data;
    if (!us().equals(new Party(trade.buyer))) {
      return Decision.nothing("the opening leg is settled by the buyer, and we are not it");
    }
    Terms terms = Terms.of(trade.terms);
    Reference reference = terms.openingReference();
    Optional<AllocatedCash.Contract> paid = cashFor(reference);
    if (paid.isEmpty()) {
      return Decision.nothing("our cash for that trade is not committed yet");
    }
    Optional<AllocatedSecurity.Contract> collateral = collateralFor(reference);
    if (collateral.isEmpty()) {
      return Decision.nothing("the collateral is not committed yet");
    }
    if (!reached(terms.purchaseDate())) {
      return Decision.nothing("the market has not reached the purchase date");
    }
    return Decision.provided(
        day(),
        "the market has no settlement day open",
        day ->
            new Decision(
                Says.settledOpening(terms.tradeId().id()),
                agreed.id.exerciseSettleOpening(
                    collateral.get().id, paid.get().id, day.id())));
  }

  @Override
  protected Decision onMessage(PublishSchedule command) {
    if (ourSchedule().isPresent()) {
      return Decision.nothing("a schedule is already published");
    }
    Optional<LocalDate> today = today();
    if (command.validUntil() == null && today.isEmpty()) {
      return Decision.nothing("the market has no settlement day open");
    }
    LocalDate validUntil =
        command.validUntil() != null ? command.validUntil() : today.get().plusMonths(3);

    return new Decision(
        Says.published(validUntil),
        CollateralPolicy.create(
            us().id(),
            Party.asText(market.dealers()),
            market.icsd().id(),
            market.centralBank().id(),
            command.currency().code(),
            command.rate(),
            command.maxCash(),
            validUntil,
            List.of(
                new EligibleCollateral(
                    command.isin().code(), command.minHaircut(), command.maxQuantity()))));
  }

  // ---------------------------------------------------------------- asking ---

  public Optional<RepoProposal.Contract> offer(TradeId tradeId) {
    return offers().stream()
        .filter(offer -> Terms.of(offer.data.terms).tradeId().equals(tradeId))
        .findFirst();
  }

  private Optional<CollateralPolicy.Contract> ourSchedule() {
    return contracts().every(CollateralPolicy.Contract.class).stream().findFirst();
  }
}
