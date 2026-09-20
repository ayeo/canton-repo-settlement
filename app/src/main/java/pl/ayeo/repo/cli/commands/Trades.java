package pl.ayeo.repo.cli.commands;

import picocli.CommandLine.Command;
import pl.ayeo.repo.actors.bank.messages.ShowTrades;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "trades", description = "our agreements and what they wait for")
public final class Trades extends DeskCommand {

  protected void act(LedgerConfig config) throws Exception {
    tell(new ShowTrades());
  }
}
