package pl.ayeo.repo.cli.commands;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.dealer.messages.CommitCollateral;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "collateral", description = "set paper aside for a trade we are about to quote")
public final class Collateral extends DeskCommand {
  @Option(names = "--trade", description = "the trade it is set aside for; REPO-hhmmss by default")
  String tradeId;

  @Option(names = "--isin", defaultValue = Bond.ISIN) String isin;
  @Option(names = "--qty", defaultValue = "365000000") BigDecimal quantity;


  protected void act(LedgerConfig config) throws Exception {
    tell(
        new CommitCollateral(
            new TradeId(tradeId == null
                ? "REPO-" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"))
                : tradeId),
            new Isin(isin),
            quantity));
  }
}
