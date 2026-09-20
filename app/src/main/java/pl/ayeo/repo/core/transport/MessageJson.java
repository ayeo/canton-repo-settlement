package pl.ayeo.repo.core.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import pl.ayeo.repo.core.Instruction;
import pl.ayeo.repo.core.Reply;

public final class MessageJson {

  private static final ObjectMapper JSON =
      new ObjectMapper().registerModule(new JavaTimeModule());

  public static String write(Instruction message) {
    try {
      return JSON.writeValueAsString(message);
    } catch (Exception e) {
      throw new IllegalStateException("cannot send " + message.getClass().getSimpleName(), e);
    }
  }

  public static Instruction read(String line) {
    try {
      return JSON.readValue(line, Instruction.class);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read an instruction: " + line, e);
    }
  }

  public static String writeReply(Reply reply) {
    try {
      return JSON.writeValueAsString(reply);
    } catch (Exception e) {
      throw new IllegalStateException("cannot encode the desk reply", e);
    }
  }

  public static Reply readReply(String line) {
    try {
      Reply reply = JSON.readValue(line, Reply.class);
      if (reply.status() == null || reply.message() == null) {
        throw new IllegalArgumentException("incomplete reply");
      }
      return reply;
    } catch (Exception e) {
      throw new IllegalStateException("cannot read the desk reply", e);
    }
  }

  private MessageJson() {}
}
