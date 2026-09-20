package pl.ayeo.repo.testing;

import com.daml.ledger.api.v2.EventOuterClass;
import com.daml.ledger.javaapi.data.ArchivedEvent;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.Event;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.codegen.DefinedDataType;
import com.daml.ledger.javaapi.data.codegen.Update;
import com.google.protobuf.ByteString;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.core.ContractRef;
import pl.ayeo.repo.ledger.Node;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.calendar.BusinessDate;
import pl.ayeo.repo.model.cash.AllocatedCash;
import pl.ayeo.repo.model.cash.CashBalance;
import pl.ayeo.repo.model.custody.AllocatedSecurity;
import pl.ayeo.repo.model.custody.Instrument;
import pl.ayeo.repo.model.custody.SecurityPosition;
import pl.ayeo.repo.model.repo.CollateralPolicy;
import pl.ayeo.repo.model.repo.EligibleCollateral;
import pl.ayeo.repo.model.repo.OpenRepo;
import pl.ayeo.repo.model.repo.RepoProposal;
import pl.ayeo.repo.model.repo.RepoTrade;
import pl.ayeo.repo.model.terms.RepoTerms;

public final class TestNode implements Node {

  public static final String ICSD = "ICSD::test";
  public static final String CENTRAL_BANK = "CentralBank::test";
  public static final String DEALER = "AlphaBank::test";
  public static final String LENDER = "BravoBank::test";
  public static final String OTHER_LENDER = "CharlieBank::test";
  public static final String ISIN = "DE0001102580";
  public static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

  private final ActorRole who;
  private final List<Event> history = new ArrayList<>();
  private final List<Update<?>> submitted = new ArrayList<>();
  private final List<DisclosedContract> attached = new ArrayList<>();
  private int nextId;

  public TestNode(ActorRole who) {
    this.who = who;
  }

  // ------------------------------------------------------------- the node ---

  @Override
  public Party party() {
    return party(who.key());
  }

  @Override
  public Party party(String key) {
    return new Party(named(key));
  }

  private static String named(String key) {
    return switch (ActorRole.of(key)) {
      case ICSD -> ICSD;
      case CENTRAL_BANK -> CENTRAL_BANK;
      case ALPHA -> DEALER;
      case BRAVO -> LENDER;
      case CHARLIE -> OTHER_LENDER;
    };
  }

  private String refusal;
  private Duration answeredAfter = Duration.ZERO;

  public TestNode refusing(String message) {
    return refusing(message, Duration.ZERO);
  }

  // A real refusal comes back only once the confirmation round is over.
  public TestNode refusing(String message, Duration after) {
    refusal = message;
    answeredAfter = after;
    return this;
  }

  @Override
  public List<Event> submit(
      String intent, List<Update<?>> commands, List<DisclosedContract> disclosed) {
    if (refusal != null) {
      try {
        Thread.sleep(answeredAfter);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      throw new io.grpc.StatusRuntimeException(
          io.grpc.Status.INVALID_ARGUMENT.withDescription(refusal));
    }
    submitted.addAll(commands);
    attached.addAll(disclosed);
    return List.of();
  }

  @Override
  public DisclosedContract disclosure(ContractRef contractId) {
    return disclosed(contractId.id());
  }

  public static DisclosedContract disclosed(String contractId) {
    return new DisclosedContract(
        AllocatedSecurity.TEMPLATE_ID, contractId, ByteString.copyFromUtf8("blob-" + contractId));
  }

  @Override
  public long ledgerEnd() {
    return history.size();
  }

  @Override
  public void replay(long fromExclusive, long toInclusive, Consumer<Event> handler) {
    for (int i = (int) fromExclusive; i < Math.min(toInclusive, history.size()); i++) {
      handler.accept(history.get(i));
    }
  }

  @Override
  public void subscribe(long fromOffset, Consumer<Event> handler) {
    // A test node has no live stream: a test feeds the desk through replay().
  }

  public List<DisclosedContract> attached() {
    return attached;
  }

  public List<Update<?>> submitted() {
    return submitted;
  }

  // -------------------------------------------------------- what happened ---

  private boolean visibleToUs(String owner) {
    return who == ActorRole.ICSD || who == ActorRole.CENTRAL_BANK || party().id().equals(owner);
  }

  private TestNode happened(Identifier templateId, DefinedDataType<?> payload) {
    String contractId = "cid-" + (++nextId);
    history.add(
        CreatedEvent.fromProto(
            EventOuterClass.CreatedEvent.newBuilder()
                .setContractId(contractId)
                .setTemplateId(templateId.toProto())
                .setCreateArguments(((DamlRecord) payload.toValue()).toProtoRecord())
                .build()));
    return this;
  }

  public TestNode archivedLast(Identifier templateId) {
    history.add(
        ArchivedEvent.fromProto(
            EventOuterClass.ArchivedEvent.newBuilder()
                .setContractId("cid-" + nextId)
                .setTemplateId(templateId.toProto())
                .build()));
    return this;
  }

  public static ArchivedEvent gone(ContractRef contract, Identifier templateId) {
    return ArchivedEvent.fromProto(
        EventOuterClass.ArchivedEvent.newBuilder()
            .setContractId(contract.id())
            .setTemplateId(templateId.toProto())
            .build());
  }

  public TestNode position(String owner, String quantity) {
    if (!visibleToUs(owner)) {
      return this;
    }
    return happened(
        SecurityPosition.TEMPLATE_ID,
        new SecurityPosition(ICSD, owner, ISIN, new BigDecimal(quantity)));
  }

  public TestNode balance(String owner, String amount) {
    return balance(owner, "EUR", amount);
  }

  public TestNode balance(String owner, String currency, String amount) {
    if (!visibleToUs(owner)) {
      return this;
    }
    return happened(
        CashBalance.TEMPLATE_ID,
        new CashBalance(CENTRAL_BANK, owner, currency, new BigDecimal(amount)));
  }

  public TestNode day(LocalDate current) {
    return happened(
        BusinessDate.TEMPLATE_ID,
        new BusinessDate(CENTRAL_BANK, current, List.of(ICSD, DEALER, LENDER, OTHER_LENDER)));
  }

  public TestNode instrument() {
    return happened(
        Instrument.TEMPLATE_ID,
        new Instrument(
            ICSD,
            ISIN,
            "Federal Republic of Germany",
            "EUR",
            new BigDecimal("98.7500000000"),
            List.of(DEALER, LENDER, OTHER_LENDER)));
  }

  public TestNode schedule() {
    return happened(
        CollateralPolicy.TEMPLATE_ID,
        new CollateralPolicy(
            LENDER,
            List.of(DEALER),
            ICSD,
            CENTRAL_BANK,
            "EUR",
            new BigDecimal("0.0350000000"),
            new BigDecimal("20000000.0000000000"),
            TODAY.plusMonths(3),
            List.of(
                new EligibleCollateral(
                    ISIN, new BigDecimal("0.0200000000"), new BigDecimal("20000000.0000000000")))));
  }

  public TestNode offerTo(String lender, String tradeId) {
    return happened(
        RepoProposal.TEMPLATE_ID,
        new RepoProposal(
            DEALER,
            lender,
            ICSD,
            CENTRAL_BANK,
            terms(tradeId, TODAY, TODAY.plusDays(7)),
            new AllocatedSecurity.ContractId("sec-" + tradeId)));
  }

  public TestNode trade(String tradeId) {
    return tradeSettling(tradeId, TODAY);
  }

  public TestNode tradeSettling(String tradeId, LocalDate purchaseDate) {
    return happened(
        RepoTrade.TEMPLATE_ID,
        new RepoTrade(
            DEALER, LENDER, ICSD, CENTRAL_BANK,
            terms(tradeId, purchaseDate, purchaseDate.plusDays(7))));
  }

  public TestNode openRepo(String tradeId, LocalDate repurchaseDate) {
    return happened(
        OpenRepo.TEMPLATE_ID,
        new OpenRepo(
            DEALER, LENDER, ICSD, CENTRAL_BANK,
            terms(tradeId, repurchaseDate.minusDays(7), repurchaseDate)));
  }

  public TestNode collateralHeld(String reference, String quantity) {
    return happened(
        AllocatedSecurity.TEMPLATE_ID,
        new AllocatedSecurity(
            ICSD, DEALER, Optional.of(LENDER), ISIN, new BigDecimal(quantity), reference));
  }

  public TestNode collateralReturned(String reference, String quantity) {
    return happened(
        AllocatedSecurity.TEMPLATE_ID,
        new AllocatedSecurity(
            ICSD, LENDER, Optional.of(DEALER), ISIN, new BigDecimal(quantity), reference));
  }

  public TestNode cashHeld(String owner, String counterparty, String reference, String amount) {
    return happened(
        AllocatedCash.TEMPLATE_ID,
        new AllocatedCash(CENTRAL_BANK, owner, counterparty, "EUR", new BigDecimal(amount), reference));
  }

  public static RepoTerms terms(String tradeId, LocalDate purchase, LocalDate repurchase) {
    return new RepoTerms(
        tradeId,
        ISIN,
        new BigDecimal("1000000.0000000000"),
        new BigDecimal("98.7500000000"),
        new BigDecimal("0.0300000000"),
        "EUR",
        new BigDecimal("0.0350000000"),
        TODAY,
        purchase,
        repurchase);
  }
}
