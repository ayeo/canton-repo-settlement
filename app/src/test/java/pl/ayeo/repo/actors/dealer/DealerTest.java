package pl.ayeo.repo.actors.dealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.Actors;
import pl.ayeo.repo.actors.bank.messages.SettleLeg;
import pl.ayeo.repo.actors.dealer.messages.ShowQuote;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.Reference;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.core.ContractRef;
import pl.ayeo.repo.core.Decision;
import pl.ayeo.repo.core.Desk;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.core.render.Show;
import pl.ayeo.repo.testing.TestCourier;
import pl.ayeo.repo.testing.TestNode;

class DealerTest {

  private static final TradeId TRADE = new TradeId("REPO-1");
  private static final Reference OPENING = TRADE.opening();
  private static final Reference CLOSING = TRADE.closing();

  private static DealerActor dealer(TestNode market) {
    DealerActor actor = new DealerActor(ActorRole.ALPHA, market.party(), Actors.market(market));
    new Desk(market, new TestCourier(), actor).learn();
    return actor;
  }

  @Test
  void showsTheHoldToEachLenderItQuotes() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .collateralHeld(OPENING.name(), "1000000"));

    Decision quote =
        actor.onMessage(
            new ShowQuote(
                TRADE,
                new Isin(TestNode.ISIN),
                new BigDecimal("1000000"),
                new BigDecimal("98.75"),
                new BigDecimal("0.03"),
                new Currency("EUR"),
                new BigDecimal("0.035"),
                7,
                0,
                List.of(new Party(TestNode.LENDER))));

    assertEquals(
        List.of(
            new Decision.Showing(
                ContractRef.of(actor.collateralFor(OPENING).orElseThrow()),
                new Party(TestNode.LENDER))),
        quote.showing());
  }

  @Test
  void pullsTheQuoteThatWasNotTaken() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .trade("REPO-1")
                .offerTo(TestNode.OTHER_LENDER, "REPO-1")
                .collateralHeld(OPENING.name(), "1000000"));

    assertTrue(
        actor.considerAgreed(actor.agreed(TRADE).orElseThrow())
            .says()
            .contains("CharlieBank"));
  }

  @Test
  void commitsTheCollateralOnceNoQuoteIsLeftToPull() {
    DealerActor actor =
        dealer(new TestNode(ActorRole.ALPHA).trade("REPO-1").position(TestNode.DEALER, "10000000"));

    assertTrue(
        actor.considerAgreed(actor.agreed(TRADE).orElseThrow())
            .says()
            .contains("allocated"));
  }

  @Test
  void hasNothingToSayOnceTheCollateralIsCommitted() {
    DealerActor actor =
        dealer(new TestNode(ActorRole.ALPHA).trade("REPO-1").collateralHeld(OPENING.name(), "1000000"));

    assertEquals(
        "the collateral is already committed",
        actor.considerAgreed(actor.agreed(TRADE).orElseThrow()).says());
  }

  @Test
  void commitsPrincipalPlusInterestOnTheRepurchaseDate() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .openRepo("REPO-1", TestNode.TODAY)
                .balance(TestNode.DEALER, "25000000"));

    // 957,875.00 at 3.5% for seven days, ACT/360: 651.89 of interest.
    assertTrue(
        actor.considerOpen(actor.open(TRADE).orElseThrow())
            .says()
            .contains("958526.89"));
  }

  @Test
  void waitsForThePaperToComeBack() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .openRepo("REPO-1", TestNode.TODAY)
                .cashHeld(TestNode.DEALER, TestNode.LENDER, CLOSING.name(), "958526.89"));

    assertEquals(
        "the lender has not returned the collateral yet",
        actor.onMessage(new SettleLeg(TRADE)).says());
  }

  @Test
  void saysWhatAnAgreedTradeIsStillWaitingFor() {
    DealerActor actor = dealer(new TestNode(ActorRole.ALPHA).day(TestNode.TODAY).trade("REPO-1"));

    String waiting = Show.trades(actor.contracts());
    assertTrue(waiting.contains("agreed, settles"));
    assertTrue(waiting.contains("[ ] collateral"));
    assertTrue(waiting.contains("[ ] cash"));

    // With the paper set aside, one box is ticked and the other is not.
    DealerActor covered =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .trade("REPO-1")
                .collateralHeld(OPENING.name(), "1000000"));

    String half = Show.trades(covered.contracts());
    assertTrue(half.contains("[x] collateral"));
    assertTrue(half.contains("[ ] cash"));
  }

  @Test
  void saysWhichPaperIsSpokenForAndForWhat() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .collateralHeld(OPENING.name(), "1000000"));

    String held = Show.positions(new Party(TestNode.DEALER), new Party(TestNode.ICSD), actor.contracts());
    assertTrue(held.contains("committed"));
    assertTrue(held.contains(TestNode.ISIN));
    assertTrue(held.contains("1kk"));
    assertTrue(held.contains(OPENING.name()));
  }

  @Test
  void stopsAskingOnceItsOwnActionHasArchivedTheSubject() {
    // Breaking this pays the repurchase amount twice.
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .openRepo("REPO-1", TestNode.TODAY)
                .balance(TestNode.DEALER, "25000000"));
    var open = actor.open(TRADE).orElseThrow();

    assertTrue(actor.considerOpen(open).acted());

    // Settling archives the repo; the read side is what hears it.
    actor.contracts().archived(ContractRef.of(open));

    // What is no longer a live contract is not among the answers.
    assertTrue(actor.open(TRADE).isEmpty());
  }

  @Test
  void settlesTheClosingLegOnceBothSidesAreBack() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .openRepo("REPO-1", TestNode.TODAY)
                .collateralReturned(CLOSING.name(), "1000000")
                .cashHeld(TestNode.DEALER, TestNode.LENDER, CLOSING.name(), "958526.89"));

    assertTrue(
        actor.onMessage(new SettleLeg(TRADE)).says().contains("settled the closing leg"));
  }

  @Test
  void doesNotQuoteALenderThatAlreadyHasIt() {
    DealerActor actor =
        dealer(
            new TestNode(ActorRole.ALPHA)
                .day(TestNode.TODAY)
                .collateralHeld(OPENING.name(), "1000000")
                .offerTo(TestNode.LENDER, "REPO-1"));

    Decision quote =
        actor.onMessage(
            new ShowQuote(
                TRADE,
                new Isin(TestNode.ISIN),
                new BigDecimal("1000000"),
                new BigDecimal("98.75"),
                new BigDecimal("0.03"),
                new Currency("EUR"),
                new BigDecimal("0.035"),
                7,
                0,
                List.of(new Party(TestNode.LENDER))));

    assertFalse(quote.acted());
    assertEquals("every lender has been shown this quote", quote.says());
  }
}
