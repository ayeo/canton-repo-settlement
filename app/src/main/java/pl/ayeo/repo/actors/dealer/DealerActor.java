package pl.ayeo.repo.actors.dealer;

import com.daml.ledger.javaapi.data.codegen.Update;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.bank.BankActor;
import pl.ayeo.repo.actors.bank.messages.SettleLeg;
import pl.ayeo.repo.actors.centralbank.Calendar;
import pl.ayeo.repo.actors.dealer.messages.CommitCollateral;
import pl.ayeo.repo.actors.dealer.messages.ShowQuote;
import pl.ayeo.repo.actors.domain.Reference;
import pl.ayeo.repo.actors.domain.Terms;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.core.ContractRef;
import pl.ayeo.repo.core.Decision;
import pl.ayeo.repo.ledger.Market;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.cash.AllocatedCash;
import pl.ayeo.repo.model.custody.AllocatedSecurity;
import pl.ayeo.repo.model.repo.OpenRepo;
import pl.ayeo.repo.model.repo.RepoProposal;
import pl.ayeo.repo.model.repo.RepoTrade;
import pl.ayeo.repo.core.render.Says;

public final class DealerActor extends BankActor {

  public DealerActor(ActorRole role, Party us, Market market) {
    super(us, market, role.display());
  }

  @Override
  protected Stream<Decision> onTick() {
    return Stream.concat(
        agreed().stream().map(this::considerAgreed), open().stream().map(this::considerOpen));
  }

  // ---------------------------------------------------------------- acting ---

  // Package-private for the test beside it, which reads the sentence back.
  Decision considerAgreed(RepoTrade.Contract held) {
    RepoTrade repo = held.data;
    if (!us().equals(new Party(repo.seller))) {
      return Decision.nothing("this repo is not ours");
    }
    Terms terms = Terms.of(repo.terms);
    Optional<RepoProposal.Contract> stale = quote(terms.tradeId());
    if (stale.isPresent()) {
      RepoProposal.Contract copy = stale.get();
      return new Decision(
          Says.pulled(terms.tradeId().id(), copy.data.lender), copy.id.exerciseWithdrawProposal());
    }
    Reference reference = terms.openingReference();
    if (collateralFor(reference).isPresent()) {
      return Decision.nothing("the collateral is already committed");
    }
    return Decision.provided(
        positionCovering(terms.isin(), terms.quantity()),
        "no free position of " + terms.isin().code() + " is large enough",
        position ->
            new Decision(
                Says.allocatedPaper(terms.quantity(), terms.isin().code(), reference.name()),
                position.id.exerciseAllocateSecurity(
                    terms.quantity(), Optional.of(repo.buyer), reference.name())));
  }

  Decision considerOpen(OpenRepo.Contract held) {
    OpenRepo repo = held.data;
    if (!us().equals(new Party(repo.seller))) {
      return Decision.nothing("this repo is not ours");
    }
    Terms terms = Terms.of(repo.terms);
    if (!reached(terms.repurchaseDate())) {
      return Decision.nothing("the repurchase date has not arrived");
    }
    Reference reference = terms.closingReference();
    if (cashFor(reference).isPresent()) {
      return Decision.nothing("the repurchase is committed; settling is somebody's instruction");
    }
    return commit(terms.repurchasePrice(), terms.currency(), new Party(repo.buyer), reference);
  }

  @Override
  protected Decision onMessage(SettleLeg command) {
    Optional<OpenRepo.Contract> found = open(command.tradeId());
    if (found.isEmpty()) {
      return Decision.nothing("we have no open repo " + command.tradeId() + " to settle");
    }
    OpenRepo.Contract running = found.get();
    OpenRepo open = running.data;
    if (!us().equals(new Party(open.seller))) {
      return Decision.nothing("the closing leg is settled by the seller, and we are not it");
    }
    Terms terms = Terms.of(open.terms);
    if (!reached(terms.repurchaseDate())) {
      return Decision.nothing("the repurchase date has not arrived");
    }
    Reference reference = terms.closingReference();
    Optional<AllocatedCash.Contract> paid = cashFor(reference);
    if (paid.isEmpty()) {
      return Decision.nothing("the repurchase price is not committed yet");
    }
    Optional<AllocatedSecurity.Contract> back = collateralFor(reference);
    if (back.isEmpty()) {
      return Decision.nothing("the lender has not returned the collateral yet");
    }
    return Decision.provided(
        day(),
        "the market has no settlement day open",
        day ->
            new Decision(
                Says.settledClosing(terms.tradeId().id(), terms.repurchasePrice()),
                running.id.exerciseSettleClosing(back.get().id, paid.get().id, day.id())));
  }

  @Override
  protected Decision onMessage(CommitCollateral command) {
    Reference reference = command.tradeId().opening();
    if (collateralFor(reference).isPresent()) {
      return Decision.nothing("the paper for " + command.tradeId() + " is already set aside");
    }
    return Decision.provided(
        positionCovering(command.isin(), command.quantity()),
        "no free position of " + command.isin().code() + " is large enough",
        position ->
            new Decision(
                Says.committed(command.quantity(), command.isin().code(), command.tradeId().id()),
                position.id.exerciseAllocateSecurity(
                    command.quantity(), Optional.empty(), reference.name())));
  }

  @Override
  protected Decision onMessage(ShowQuote command) {
    Optional<LocalDate> tradeDate = today();
    if (tradeDate.isEmpty()) {
      return Decision.nothing("the market has no settlement day open");
    }

    Terms terms = terms(command, tradeDate.get());

    Optional<AllocatedSecurity.Contract> reserved = collateralFor(terms.openingReference());
    if (reserved.isEmpty()) {
      return Decision.nothing("no paper is set aside for " + command.tradeId());
    }

    List<Party> waiting =
        command.lenders().stream()
            .filter(
                lender ->
                    quotes()
                      .stream()
                      .noneMatch(
                          offer ->
                            Terms.of(offer.data.terms).tradeId().equals(command.tradeId())
                            && new Party(offer.data.lender).equals(lender)))
            .toList();

    if (waiting.isEmpty()) {
      return Decision.nothing("every lender has been shown this quote");
    }

    List<Update<?>> offers = new ArrayList<>();
    List<String> sentences = new ArrayList<>();
    for (Party lender : waiting) {
      offers.add(
          RepoProposal.create(
              us().id(),
              lender.id(),
              market.icsd().id(),
              market.centralBank().id(),
              terms.toModel(),
              reserved.get().id));
      sentences.add(Says.offered(lender.id(), terms));
    }

    Decision quote = new Decision(String.join("; ", sentences), offers);
    for (Party lender : waiting) {
      quote = quote.showing(ContractRef.of(reserved.get()), lender);
    }

    return quote;
  }

  private static Terms terms(ShowQuote command, LocalDate tradeDate) {
    LocalDate purchaseDate = Calendar.addBusinessDays(tradeDate, command.settles());
    LocalDate repurchaseDate = Calendar.rollModifiedFollowing(purchaseDate.plusDays(command.term()));
    return new Terms(
        command.tradeId(),
        command.isin(),
        command.quantity(),
        command.price(),
        command.haircut(),
        command.currency(),
        command.rate(),
        tradeDate,
        purchaseDate,
        repurchaseDate);
  }

  private List<RepoProposal.Contract> quotes() {
    return contracts().every(RepoProposal.Contract.class);
  }

  public Optional<RepoProposal.Contract> quote(TradeId tradeId) {
    return quotes().stream()
        .filter(offer -> Terms.of(offer.data.terms).tradeId().equals(tradeId))
        .findFirst();
  }
}
