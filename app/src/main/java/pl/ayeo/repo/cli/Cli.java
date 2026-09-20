package pl.ayeo.repo.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.cli.commands.Cash;
import pl.ayeo.repo.cli.commands.Collateral;
import pl.ayeo.repo.cli.commands.EndOfDay;
import pl.ayeo.repo.cli.commands.Fund;
import pl.ayeo.repo.cli.commands.Issue;
import pl.ayeo.repo.cli.commands.Positions;
import pl.ayeo.repo.cli.commands.Propose;
import pl.ayeo.repo.cli.commands.Publish;
import pl.ayeo.repo.cli.commands.Register;
import pl.ayeo.repo.cli.commands.Settle;
import pl.ayeo.repo.cli.commands.Trades;
import pl.ayeo.repo.cli.commands.Watch;
import pl.ayeo.repo.cli.commands.WriteTrace;

@Command(
    name = "repo",
    mixinStandardHelpOptions = true,
    subcommands = {
      Register.class,
      Issue.class,
      Fund.class,
      EndOfDay.class,
      Publish.class,
      Collateral.class,
      Propose.class,
      Settle.class,
      Watch.class,
      Positions.class,
      Cash.class,
      Trades.class,
      WriteTrace.class,
    },
    description = "A bilateral repo, settled across five independent nodes.",
    footer = {
      "",
      "Roles: ICSD, CentralBank, AlphaBank, BravoBank, CharlieBank",
      "",
      "Taking an offer, pulling a quote and committing an asset have no command",
      "of their own: they are decided, not instructed. Run an institution as a",
      "desk - repo --as <role> watch - and it reaches them itself.",
    })
public final class Cli {

  @Option(
      names = "--as",
      paramLabel = "<role>",
      defaultValue = "AlphaBank",
      converter = WhoIsSpeaking.class,
      description = "the institution this invocation acts as")
  public ActorRole role;

  static final class WhoIsSpeaking implements CommandLine.ITypeConverter<ActorRole> {
    @Override
    public ActorRole convert(String typed) {
      try {
        return ActorRole.of(typed);
      } catch (IllegalArgumentException e) {
        throw new CommandLine.TypeConversionException(e.getMessage());
      }
    }
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new Cli()).setCaseInsensitiveEnumValuesAllowed(true).execute(args));
  }
}
