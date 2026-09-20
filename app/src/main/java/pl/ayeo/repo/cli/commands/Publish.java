package pl.ayeo.repo.cli.commands;

import java.math.BigDecimal;
import java.time.LocalDate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.lender.messages.PublishSchedule;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "publish", description = "publish what we will take as collateral")
public final class Publish extends DeskCommand {
  @Option(names = "--isin", defaultValue = Bond.ISIN) String isin;
  @Option(names = "--min-haircut", defaultValue = "0.02") BigDecimal minHaircut;
  @Option(names = "--max-qty", defaultValue = "500000000") BigDecimal maxQuantity;
  @Option(names = "--max-cash", defaultValue = "500000000") BigDecimal maxCash;
  @Option(names = "--currency", defaultValue = "EUR") String currency;

  @Option(names = "--rate", defaultValue = "0.0325") BigDecimal rate;
  @Option(names = "--valid-to", description = "three months out by default") LocalDate validUntil;

  protected void act(LedgerConfig config) throws Exception {
    tell(new PublishSchedule(new Isin(isin), minHaircut, maxQuantity, maxCash, new Currency(currency), rate, validUntil));
  }
}
