package pl.ayeo.repo.core.transport;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import pl.ayeo.repo.core.*;
import pl.ayeo.repo.actors.bank.messages.ShowCash;

class MessageJsonTest {
  @Test void preservesRequestIdentityAndTypedMessage() {
    Instruction request = new Instruction("retry-123", new ShowCash());
    assertEquals(request, MessageJson.read(MessageJson.write(request)));
  }

  @Test void preservesReplyStatusAndMultilineContent() {
    for (Reply reply : new Reply[] {Reply.ok("one\ntwo"), Reply.notReady("wait"), Reply.error("failed")}) {
      assertEquals(reply, MessageJson.readReply(MessageJson.writeReply(reply)));
    }
  }

  @Test void missingOrOldProtocolReplyFailsInsteadOfReportingSuccess() {
    assertThrows(IllegalStateException.class, () -> MessageJson.readReply("done"));
    assertThrows(IllegalStateException.class, () -> MessageJson.readReply("{}"));
  }
}
