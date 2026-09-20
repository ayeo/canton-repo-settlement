package pl.ayeo.repo.core.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.ayeo.repo.core.CommandListener;
import pl.ayeo.repo.core.Instruction;
import pl.ayeo.repo.core.Reply;

public final class SocketCommandListener implements CommandListener {

  private static final Logger log = LoggerFactory.getLogger(SocketCommandListener.class);

  private final int port;
  private volatile ServerSocket listening;

  public SocketCommandListener(int port) {
    this.port = port;
  }

  @Override
  public void listen(Function<Instruction, CompletableFuture<Reply>> desk) {
    try {
      listening = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
    } catch (IOException e) {
      throw new IllegalStateException("cannot listen for instructions on " + port, e);
    }
    Thread doorman =
        new Thread(
            () -> {
              while (!listening.isClosed()) {
                try (Socket caller = listening.accept()) {
                  answer(caller, desk);
                } catch (Exception e) {
                  // Catch everything: an exception escaping would kill the only listener thread.
                  log.info("an instruction did not arrive: {}", e.getMessage());
                }
              }
            },
            "commands-" + port);
    doorman.setDaemon(true);
    doorman.start();
    log.info("taking instructions on {}", port);
  }

  private static final int SILENT_CALLER_MS = 30_000;

  private static final long ANSWER_TIMEOUT_SECONDS = 120;

  private void answer(Socket caller, Function<Instruction, CompletableFuture<Reply>> desk)
      throws IOException {
    // Callers are served one at a time, so a silent one must not hold the door.
    caller.setSoTimeout(SILENT_CALLER_MS);
    BufferedReader from =
        new BufferedReader(new InputStreamReader(caller.getInputStream(), StandardCharsets.UTF_8));
    PrintWriter back = new PrintWriter(caller.getOutputStream(), true, StandardCharsets.UTF_8);
    String line = from.readLine();
    if (line == null || line.isBlank()) {
      return;
    }
    Reply answer;
    try {
      // Bounded, so a wedged actor (unreachable ledger, full mailbox) still produces an answer.
      answer = desk.apply(MessageJson.read(line)).get(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      // Re-flag only on a real interrupt; doing it for other failures breaks the next caller's get().
      Thread.currentThread().interrupt();
      answer = Reply.error("the desk was interrupted before it answered");
    } catch (Exception e) {
      answer = Reply.error("the instruction was not answered: " + e.getMessage());
    }
    back.println(MessageJson.writeReply(answer));
  }

  @Override
  public void close() {
    if (listening != null) {
      try {
        listening.close();
      } catch (IOException e) {
        log.warn("could not close the instruction listener", e);
      }
    }
  }
}
