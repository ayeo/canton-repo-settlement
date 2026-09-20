package pl.ayeo.repo.cli.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.core.observer.Trace;
import pl.ayeo.repo.core.render.Table;

@Command(name = "trace", description = "the whole flow, as each node saw it")
public final class WriteTrace implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(WriteTrace.class);
  @Option(names = "--out", description = "write here instead of standard output")
  String out;

  public Integer call() throws Exception {
    // Data, not a page: web/trace.html renders it.
    String rendered = Table.of(Trace.read(LedgerConfig.loadDefault()));
    if (out == null || out.isEmpty()) {
      log.info(rendered);
    } else {
      Files.writeString(Path.of(out), rendered);
      log.info("written to {}", out);
    }
    return 0;
  }
}
