package pl.ayeo.repo.cli.commands;

import picocli.CommandLine.Command;
import pl.ayeo.repo.actors.Actors;
import pl.ayeo.repo.core.Desk;
import pl.ayeo.repo.core.courier.Folder;
import pl.ayeo.repo.ledger.LedgerClient;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.ledger.LedgerNode;
import pl.ayeo.repo.core.transport.SocketCommandListener;

@Command(name = "watch", description = "stay up, reacting to our own node's stream")
public final class Watch extends DeskCommand {

  @Override
  protected void act(LedgerConfig config) throws Exception {
    try (LedgerClient node = LedgerClient.connect(acting(config))) {
      Desk desk = Actors.of(role(), new LedgerNode(node, role().key(), config), new Folder());
      desk.run(new SocketCommandListener(acting(config).deskPort()));
    }
  }
}
