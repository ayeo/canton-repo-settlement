package pl.ayeo.repo.cli.commands;

import java.math.BigDecimal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.centralbank.messages.FundAccount;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "fund", description = "credit a bank's account at the central bank")
public final class Fund extends DeskCommand {
  @Option(names = "--to", required = true) String owner;
  @Option(names = "--amount", defaultValue = "500000000") BigDecimal amount;

  @Option(names = "--currency", defaultValue = "EUR") String currency;

  @Option(names = "--again", description = "credit an account that already has a balance")
  boolean again;

  protected void act(LedgerConfig config) throws Exception {
    String holder = config.role(ActorRole.of(owner).key()).party();
    tell(new FundAccount(holder, new Currency(currency), amount, again));
  }
}
