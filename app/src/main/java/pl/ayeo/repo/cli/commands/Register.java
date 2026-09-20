package pl.ayeo.repo.cli.commands;

import java.math.BigDecimal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.icsd.messages.RegisterInstrument;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "register", description = "record an instrument in the register")
public final class Register extends DeskCommand {
  @Option(names = "--isin", required = true, description = "the instrument's ISIN")
  String isin;

  @Option(names = "--issuer", required = true, description = "who issued it")
  String issuer;

  @Option(names = "--currency", defaultValue = "EUR") String currency;

  @Option(
      names = "--price",
      defaultValue = "98.75",
      description = "what the depository marks it at, per 100 nominal")
  BigDecimal price;

  protected void act(LedgerConfig config) throws Exception {
    tell(new RegisterInstrument(new Isin(isin), issuer, new Currency(currency), price));
  }
}
