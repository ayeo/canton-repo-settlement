package pl.ayeo.repo.actors.lender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.Actors;
import pl.ayeo.repo.actors.bank.messages.SettleLeg;
import pl.ayeo.repo.actors.domain.Reference;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.core.Desk;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.ledger.Refusals;
import pl.ayeo.repo.core.render.Show;
import pl.ayeo.repo.testing.TestCourier;
import pl.ayeo.repo.testing.TestNode;

class LenderTest {

  private static final TradeId TRADE = new TradeId("REPO-1");
  private static final Reference OPENING = TRADE.opening();

  private static LenderActor lender(TestNode market) {
    return lender(market, new TestCourier());
  }

  private static LenderActor lender(TestNode market, TestCourier courier) {
    return (LenderActor) desk(market, courier).actor();
  }

  private static Desk desk(TestNode market, TestCourier courier) {
    Desk desk =
        new Desk(
            market,
            courier,
            new LenderActor(ActorRole.BRAVO, market.party(), Actors.market(market)));
    desk.learn();
    return desk;
  }

  private static TestCourier shownTheCollateral() {
    return new TestCourier().shown(new Party(TestNode.LENDER), "sec-REPO-1");
  }

  // The trace sets a refusal among transactions stamped when they were sent. Stamped when the
  // answer came back instead, a lender that lost a race shows up after the dealer's cleanup.
  @Test
  void datesARefusalByWhenItWasTriedNotWhenTheAnswerCameBack() throws Exception {
    Files.deleteIfExists(Path.of(".repo-refusals.log"));
    TestNode market =
        new TestNode(ActorRole.BRAVO)
            .schedule()
            .instrument()
            .offerTo(TestNode.LENDER, "REPO-1")
            .refusing(
                "LOCAL_VERDICT_LOCKED_CONTRACTS(2,abc): Rejected transaction is referring to locked contracts",
                Duration.ofMillis(500));
    Desk desk =
        new Desk(
            market,
            shownTheCollateral(),
            new LenderActor(ActorRole.BRAVO, market.party(), Actors.market(market)));

    LocalTime tried = LocalTime.now();
    // run() takes one turn before it listens for commands; a listener that will not listen ends it.
    assertThrows(
        StopListening.class,
        () ->
            desk.run(
                commands -> {
                  throw new StopListening();
                }));

    LocalTime stamped = LocalTime.parse(Refusals.read().getLast().at());
    assertTrue(
        Duration.between(tried, stamped).toMillis() < 250,
        "tried at " + tried + " but stamped " + stamped);
  }

  private static final class StopListening extends RuntimeException {}

  @Test
  void takesAnOfferWhenAScheduleIsPublished() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO).schedule().instrument().offerTo(TestNode.LENDER, "REPO-1"),
            shownTheCollateral());

    assertTrue(
        actor.receive(actor.offer(TRADE).orElseThrow())
            .says()
            .contains("REPO-1"));
  }

  @Test
  void waitsToBeShownTheCollateralBehindAnOffer() {
    LenderActor actor =
        lender(new TestNode(ActorRole.BRAVO).schedule().offerTo(TestNode.LENDER, "REPO-1"));

    assertEquals(
        "the dealer has not shown us the collateral behind that offer",
        actor.receive(actor.offer(TRADE).orElseThrow()).says());
  }

  @Test
  void attachesTheCollateralItWasShown() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO).schedule().instrument().offerTo(TestNode.LENDER, "REPO-1"),
            shownTheCollateral());

    assertEquals(
        List.of("sec-REPO-1"),
        actor.receive(actor.offer(TRADE).orElseThrow()).using().stream()
            .map(contract -> contract.contractId.orElseThrow())
            .toList());
  }

  @Test
  void doesNothingWithoutASchedule() {
    LenderActor actor = lender(new TestNode(ActorRole.BRAVO).offerTo(TestNode.LENDER, "REPO-1"));

    assertEquals(
        "we have published no collateral schedule to take an offer against",
        actor.receive(actor.offer(TRADE).orElseThrow())
            .says());
  }

  @Test
  void commitsThePurchasePriceAgainstCollateralEarmarkedForUs() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO)
                .trade("REPO-1")
                .balance(TestNode.LENDER, "25000000")
                .collateralHeld(OPENING.name(), "1000000"));

    // 1,000,000 at 98.75 less a 3% haircut.
    assertTrue(
        actor.considerPaperHold(actor.collateralFor(OPENING).orElseThrow())
            .says()
            .contains("957875.00"));
  }

  @Test
  void mergesBalancesFirstWhenNoSingleOneCovers() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO)
                .trade("REPO-1")
                .balance(TestNode.LENDER, "500000")
                .balance(TestNode.LENDER, "600000")
                .collateralHeld(OPENING.name(), "1000000"));

    assertTrue(
        actor.considerPaperHold(actor.collateralFor(OPENING).orElseThrow())
            .says()
            .contains("merged"));
  }

  @Test
  void willNotSpendACurrencyItDoesNotHold() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO)
                .trade("REPO-1")
                .balance(TestNode.LENDER, "USD", "25000000")
                .collateralHeld(OPENING.name(), "1000000"));

    // The ledger would refuse the payment; a bank that knows its own book does
    // not have to find out that way.
    assertEquals(
        "we hold nothing in EUR",
        actor.considerPaperHold(actor.collateralFor(OPENING).get()).says());
  }

  @Test
  void saysWhichMoneyIsSpokenForAndForWhat() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO)
                .cashHeld(TestNode.LENDER, TestNode.DEALER, OPENING.name(), "957875"));

    String cash = Show.cash(new Party(TestNode.LENDER), actor.contracts());
    assertTrue(cash.contains("committed"));
    assertTrue(cash.contains("957875.00 EUR"));
    assertTrue(cash.contains(OPENING.name()));
  }

  @Test
  void addsUpWhatItHoldsAndNeverHearsOfTheRest() {
    LenderActor actor =
        lender(
            new TestNode(ActorRole.BRAVO)
                .balance(TestNode.LENDER, "500000")
                .balance(TestNode.LENDER, "600000")
                .balance(TestNode.DEALER, "9000000"));

    // A balance is observed by its owner alone, so the dealer's never reaches us.
    String cash = Show.cash(new Party(TestNode.LENDER), actor.contracts());
    assertTrue(cash.contains("1100k EUR"));
    // Look for the would-be total: a sum is never printed as one of its parts.
    assertFalse(cash.contains("10100k"));
  }

  @Test
  void settlesTheOpeningLegOnTheDayAndNotBefore() {
    TestNode ready =
        new TestNode(ActorRole.BRAVO)
            .day(TestNode.TODAY)
            .balance(TestNode.LENDER, "25000000")
            .collateralHeld(OPENING.name(), "1000000")
            .cashHeld(TestNode.LENDER, TestNode.DEALER, OPENING.name(), "957875.00");

    LenderActor today = lender(ready.tradeSettling("REPO-1", TestNode.TODAY));
    assertTrue(
        today.onMessage(new SettleLeg(TRADE)).says().contains("settled the opening leg"));

    LenderActor tomorrow =
        lender(
            new TestNode(ActorRole.BRAVO)
                .day(TestNode.TODAY)
                .tradeSettling("REPO-1", TestNode.TODAY.plusDays(1))
                .collateralHeld(OPENING.name(), "1000000")
                .cashHeld(TestNode.LENDER, TestNode.DEALER, OPENING.name(), "957875.00"));
    assertEquals(
        "the market has not reached the purchase date",
        tomorrow.onMessage(new SettleLeg(TRADE)).says());
  }

  @Test
  void doesNotKeepOfferingWhatTheModelRefused() {
    Desk desk =
        desk(
            new TestNode(ActorRole.BRAVO)
                .schedule()
                .instrument()
                .offerTo(TestNode.LENDER, "REPO-1")
                .refusing("DAML_FAILURE(9,abc): the price is not the one the depository publishes"),
            shownTheCollateral());
    LenderActor actor = (LenderActor) desk.actor();
    var offer = actor.offer(TRADE).orElseThrow();

    assertThrows(RuntimeException.class, () -> desk.tell(offer));

    // Dropped by the lender itself; nothing generic keeps a list.
    assertTrue(actor.offer(TRADE).isEmpty());
  }
}
