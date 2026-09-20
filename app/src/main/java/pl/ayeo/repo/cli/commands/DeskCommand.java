package pl.ayeo.repo.cli.commands;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.UUID;
import picocli.CommandLine.Option;
import pl.ayeo.repo.core.Instruction;
import pl.ayeo.repo.core.Reply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.cli.Cli;
import pl.ayeo.repo.core.Message;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.core.transport.MessageJson;

public abstract class DeskCommand implements Callable<Integer> {

  protected static final Logger log = LoggerFactory.getLogger(DeskCommand.class);

  @ParentCommand public Cli cli;

  @Spec CommandSpec spec;

  @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this command's options")
  boolean help;

  @Option(names = "--request-id", description = "reuse this ID only to retry the same instruction")
  String requestId;

  private int resultCode;

  protected final LedgerConfig.Role acting(LedgerConfig config) {
    return config.role(cli.role.key());
  }

  protected final ActorRole role() {
    return cli.role;
  }

  protected abstract void act(LedgerConfig config) throws Exception;

  @Override
  public final Integer call() throws Exception {
    LedgerConfig config = LedgerConfig.loadDefault();
    try {
      resultCode = 0;
      act(config);
      return resultCode;
    } catch (Exception e) {
      log.error("{}", e.getMessage());
      return 1;
    }
  }

  protected final void tell(Message command) throws Exception {
    LedgerConfig.Role acting = LedgerConfig.loadDefault().role(cli.role.key());
    try (Socket desk = new Socket(InetAddress.getLoopbackAddress(), acting.deskPort())) {
      PrintWriter out = new PrintWriter(desk.getOutputStream(), true, StandardCharsets.UTF_8);
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(desk.getInputStream(), StandardCharsets.UTF_8));
      desk.setSoTimeout(125_000);
      String id = requestId == null ? UUID.randomUUID().toString() : requestId;
      if (!(command instanceof pl.ayeo.repo.core.Query)) log.info("request id: {}", id);
      out.println(MessageJson.write(new Instruction(id, command)));
      String line = in.readLine();
      if (line == null) throw new IllegalStateException("no answer from the desk");
      Reply reply = MessageJson.readReply(line);
      log.info("{}", reply.message());
      resultCode = reply.exitCode();
    } catch (ConnectException unreachable) {
      throw new IllegalStateException(
          acting.displayName()
              + " is not running - start it with: make desk ROLE="
              + cli.role.key());
    }
  }
}
