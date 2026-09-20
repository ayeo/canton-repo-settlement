package pl.ayeo.repo.cli.commands;

import java.math.BigDecimal;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.icsd.messages.IssueSecurities;
import pl.ayeo.repo.ledger.LedgerConfig;

@Command(name = "issue", description = "credit a holder with securities")
public final class Issue extends DeskCommand {
  // No default, unlike other commands: which security is issued cannot be assumed.
  @Option(names = "--isin", required = true, description = "the instrument to issue")
  String isin;
  @Option(names = "--qty", defaultValue = "1000000000") BigDecimal quantity;
  @Option(names = "--to", required = true, description = "the holder, e.g. AlphaBank")
  String owner;

  @Option(names = "--again", description = "issue more of something already held")
  boolean again;

  protected void act(LedgerConfig config) throws Exception {
    String holder = config.role(ActorRole.of(owner).key()).party();
    tell(new IssueSecurities(new Isin(isin), quantity, holder, again));
  }
}
