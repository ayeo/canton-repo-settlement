package pl.ayeo.repo.cli.commands;

import picocli.CommandLine.Command;
import pl.ayeo.repo.actors.bank.messages.ShowCash;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "cash", description = "balances held, free and committed")
public final class Cash extends DeskCommand {

  protected void act(LedgerConfig config) throws Exception {
    tell(new ShowCash());
  }
}
