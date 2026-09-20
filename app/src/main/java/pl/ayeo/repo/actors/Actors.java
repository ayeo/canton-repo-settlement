package pl.ayeo.repo.actors;

import java.util.List;
import pl.ayeo.repo.actors.centralbank.CentralBankActor;
import pl.ayeo.repo.actors.dealer.DealerActor;
import pl.ayeo.repo.actors.icsd.IcsdActor;
import pl.ayeo.repo.actors.lender.LenderActor;
import pl.ayeo.repo.core.Actor;
import pl.ayeo.repo.core.Desk;
import pl.ayeo.repo.core.courier.Courier;
import pl.ayeo.repo.ledger.Market;
import pl.ayeo.repo.ledger.Node;
import pl.ayeo.repo.ledger.Party;

public final class Actors {

  public static Desk of(ActorRole role, Node io, Courier courier) {
    Market market = market(io);
    Party us = io.party();
    Actor actor =
        switch (role) {
          case ALPHA -> new DealerActor(role, us, market);
          case BRAVO, CHARLIE -> new LenderActor(role, us, market);
          case ICSD -> new IcsdActor(us, market);
          case CENTRAL_BANK -> new CentralBankActor(us, market);
        };
    return new Desk(io, courier, actor);
  }

  public static Market market(Node io) {
    return new Market(
        io.party(ActorRole.ICSD.key()),
        io.party(ActorRole.CENTRAL_BANK.key()),
        List.of(io.party(ActorRole.ALPHA.key())),
        ActorRole.lenders().stream().map(role -> io.party(role.key())).toList());
  }

  private Actors() {}
}
