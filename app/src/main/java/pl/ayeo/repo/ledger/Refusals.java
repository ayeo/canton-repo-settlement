package pl.ayeo.repo.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class Refusals {

  private static final Path LOG = Path.of(".repo-refusals.log");
  // Milliseconds: the trace is ordered by this, and a refusal usually shares a
  // second with the transaction that caused it.
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

  public record Note(String at, String role, String command, String message) {
  }

  public static void record(String role, String command, String message) {
    record(LocalTime.now(), role, command, message);
  }

  public static void record(LocalTime at, String role, String command, String message) {
    String line =
        String.join("\t", at.format(CLOCK), role, command, message.replace('\t', ' '));
    try {
      Files.writeString(
          LOG,
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      // Losing the note is not worth failing the command that already failed.
    }
  }

  public static List<Note> read() {
    List<Note> refusals = new ArrayList<>();
    if (!Files.exists(LOG)) {
      return refusals;
    }
    try {
      for (String line : Files.readAllLines(LOG, StandardCharsets.UTF_8)) {
        String[] parts = line.split("\t", 4);
        if (parts.length == 4) {
          refusals.add(new Note(parts[0], parts[1], parts[2], parts[3]));
        }
      }
    } catch (IOException e) {
      // An unreadable note is the same as no note.
    }
    return refusals;
  }

  private Refusals() {
  }
}
