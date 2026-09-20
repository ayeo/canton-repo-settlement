package pl.ayeo.repo.cli.commands;

import java.math.BigDecimal;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.dealer.messages.ShowQuote;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "propose", description = "quote a trade whose paper is already committed")
public final class Propose extends DeskCommand {
  @Option(names = "--trade", required = true, description = "the trade whose paper is set aside")
  String tradeId;

  @Option(names = "--isin", defaultValue = Bond.ISIN) String isin;
  @Option(names = "--qty", defaultValue = "365000000") BigDecimal quantity;
  @Option(names = "--price", defaultValue = "98.75") BigDecimal price;
  @Option(names = "--haircut", defaultValue = "0.03") BigDecimal haircut;
  @Option(names = "--currency", defaultValue = "EUR") String currency;
  @Option(names = "--rate", defaultValue = "0.035") BigDecimal rate;

  @Option(names = "--days", defaultValue = "1", description = "term, in calendar days")
  int term;

  @Option(
      names = "--settles",
      defaultValue = "0",
      description = "when the opening leg settles, in business days")
  int settles;

  @Option(
      names = "--to",
      split = ",",
      description = "which lenders to show it to; all of them by default")
  List<String> lenders;

  protected void act(LedgerConfig config) throws Exception {
    tell(
        new ShowQuote(
            new TradeId(tradeId), new Isin(isin), quantity, price, haircut, new Currency(currency), rate, term, settles,
            Lenders.named(lenders, config)));
  }
}
