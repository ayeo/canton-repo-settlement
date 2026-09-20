package pl.ayeo.repo.cli.commands;

import java.time.LocalDate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.centralbank.messages.OpenSettlementDay;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "end-of-day", description = "open the first settlement day, or move on")
public final class EndOfDay extends DeskCommand {
  @Option(names = "--to", description = "roll to this date; the next business day otherwise")
  LocalDate target;

  protected void act(LedgerConfig config) throws Exception {
    // Null is meaningful: the central bank then works out the next business day.
    tell(new OpenSettlementDay(target));
  }
}
