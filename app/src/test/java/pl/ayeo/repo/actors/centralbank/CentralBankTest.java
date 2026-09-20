package pl.ayeo.repo.actors.centralbank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.Actors;
import pl.ayeo.repo.actors.centralbank.messages.FundAccount;
import pl.ayeo.repo.actors.centralbank.messages.OpenSettlementDay;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.core.Desk;
import pl.ayeo.repo.model.calendar.BusinessDate;
import pl.ayeo.repo.testing.TestNode;

class CentralBankTest {

  private static CentralBankActor bank(TestNode market) {
    CentralBankActor actor = new CentralBankActor(market.party(), Actors.market(market));
    new Desk(market, actor).learn();
    return actor;
  }

  private static BusinessDate.Contract rolledTo(LocalDate day) {
    return new BusinessDate.Contract(
        new BusinessDate.ContractId("cid-" + day),
        new BusinessDate(TestNode.CENTRAL_BANK, day, List.of()),
        Set.of(),
        Set.of());
  }

  @Test
  void willNotCreditAnAccountThatAlreadyHasABalance() {
    CentralBankActor bank =
        bank(new TestNode(ActorRole.CENTRAL_BANK).day(TestNode.TODAY).balance(TestNode.DEALER, "5"));

    assertFalse(
        bank.onMessage(new FundAccount(TestNode.DEALER, new Currency("EUR"), BigDecimal.ONE, false))
            .acted());
    assertTrue(
        bank.onMessage(new FundAccount(TestNode.DEALER, new Currency("EUR"), BigDecimal.ONE, true))
            .acted());
  }

  @Test
  void willNotRollTwiceFromTheSameCalendar() {
    CentralBankActor bank = bank(new TestNode(ActorRole.CENTRAL_BANK).day(TestNode.TODAY));
    OpenSettlementDay untilThen =
        new OpenSettlementDay(Calendar.addBusinessDays(TestNode.TODAY, 3));

    assertTrue(bank.onMessage(untilThen).acted());

    // No new calendar has arrived: the roll we sent is still on its way.
    assertFalse(bank.onMessage(untilThen).acted());
  }

  @Test
  void walksToTheDateItWasGivenOneCalendarAtATime() {
    CentralBankActor bank = bank(new TestNode(ActorRole.CENTRAL_BANK).day(TestNode.TODAY));
    LocalDate target = Calendar.addBusinessDays(TestNode.TODAY, 3);
    OpenSettlementDay untilThen = new OpenSettlementDay(target);

    LocalDate at = TestNode.TODAY;
    for (int day = 0; day < 3; day++) {
      assertTrue(bank.onMessage(untilThen).acted());
      assertFalse(bank.onMessage(untilThen).acted());
      at = Calendar.nextBusinessDay(at);
      bank.receive(rolledTo(at));
    }

    assertEquals(target, at);
    assertFalse(bank.onMessage(untilThen).acted());
  }

  @Test
  void rollsOneBusinessDayAndThenHasNothingLeftToDo() {
    CentralBankActor bank = bank(new TestNode(ActorRole.CENTRAL_BANK).day(TestNode.TODAY));
    OpenSettlementDay endOfDay = new OpenSettlementDay(null);

    assertTrue(bank.onMessage(endOfDay).acted());
    bank.receive(rolledTo(Calendar.nextBusinessDay(TestNode.TODAY)));

    assertFalse(bank.onMessage(endOfDay).acted());
  }

  @Test
  void keepsRollingWhileTheDateItWasGivenIsStillAhead() {
    CentralBankActor bank = bank(new TestNode(ActorRole.CENTRAL_BANK).day(TestNode.TODAY));
    OpenSettlementDay untilThen =
        new OpenSettlementDay(Calendar.addBusinessDays(TestNode.TODAY, 3));

    assertTrue(bank.onMessage(untilThen).acted());
    bank.receive(rolledTo(Calendar.nextBusinessDay(TestNode.TODAY)));

    assertTrue(bank.onMessage(untilThen).acted());
  }
}
