package pl.ayeo.repo.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import pl.ayeo.repo.actors.bank.messages.SettleLeg;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "settle", description = "settle the leg we pay for")
public final class Settle extends DeskCommand {
  @Parameters(index = "0", paramLabel = "<trade>") String tradeId;

  protected void act(LedgerConfig config) throws Exception {
    tell(new SettleLeg(new TradeId(tradeId)));
  }
}
