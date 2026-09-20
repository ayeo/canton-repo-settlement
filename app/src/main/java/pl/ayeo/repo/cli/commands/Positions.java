package pl.ayeo.repo.cli.commands;

import picocli.CommandLine.Command;
import pl.ayeo.repo.actors.bank.messages.ShowPositions;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "positions", description = "securities held, free and committed")
public final class Positions extends DeskCommand {

  protected void act(LedgerConfig config) throws Exception {
    tell(new ShowPositions());
  }
}
