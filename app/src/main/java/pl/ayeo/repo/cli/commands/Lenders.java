package pl.ayeo.repo.cli.commands;

import java.util.List;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.ledger.Party;

final class Lenders {

  static List<Party> named(List<String> typed, LedgerConfig config) {
    return typed == null || typed.isEmpty()
        ? ActorRole.lenders().stream().map(who -> new Party(config.role(who.key()).party())).toList()
        : typed.stream()
            .map(name -> new Party(config.role(ActorRole.of(name).key()).party()))
            .toList();
  }

  private Lenders() {}
}
