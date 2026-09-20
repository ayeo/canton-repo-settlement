package pl.ayeo.repo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.Actors;
import pl.ayeo.repo.actors.icsd.IcsdActor;
import pl.ayeo.repo.model.calendar.BusinessDate;
import pl.ayeo.repo.model.custody.Instrument;
import pl.ayeo.repo.model.custody.SecurityPosition;
import pl.ayeo.repo.testing.TestNode;

class ContractsTest {

  private static IcsdActor depository(TestNode market) {
    IcsdActor actor = new IcsdActor(market.party(), Actors.market(market));
    new Desk(market, actor).learn();
    return actor;
  }

  @Test
  void keepsEveryContractItWasShown() {
    IcsdActor actor =
        depository(
            new TestNode(ActorRole.ICSD)
                .day(TestNode.TODAY)
                .instrument()
                .position(TestNode.DEALER, "10000000"));

    Contracts seen = actor.contracts();
    assertEquals(1, seen.every(Instrument.Contract.class).size());
    assertEquals(1, seen.every(SecurityPosition.Contract.class).size());
    // The depository never reacts to a business date; the read side keeps it anyway.
    assertEquals(1, seen.every(BusinessDate.Contract.class).size());
  }

  @Test
  void stopsOfferingWhatTheLedgerSaysIsGone() {
    IcsdActor actor =
        depository(
            new TestNode(ActorRole.ICSD)
                .instrument()
                .position(TestNode.DEALER, "10000000")
                .archivedLast(SecurityPosition.TEMPLATE_ID));

    assertTrue(actor.contracts().every(SecurityPosition.Contract.class).isEmpty());
    assertEquals(1, actor.contracts().every(Instrument.Contract.class).size());
  }
}
