package pl.ayeo.repo.core.courier;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.testing.TestNode;

class FolderTest {
  @TempDir Path root;

  @Test void ignoresIncompleteTemporaryFiles() throws Exception {
    Party party = new Party(TestNode.LENDER);
    Path inbox = Files.createDirectories(root.resolve(party.id().replace(':', '_')));
    Files.write(inbox.resolve(".delivery-in-progress.tmp"), new byte[] {10, 127});
    Folder courier = new Folder(root);
    assertTrue(courier.collect(party).isEmpty());
    courier.deliver(party, TestNode.disclosed("hold"));
    assertEquals("hold", courier.collect(party).getFirst().contractId.orElseThrow());
  }

  @Test void readersOnlySeeCompletePublishedDisclosuresWhileTheyAreReplaced() throws Exception {
    Party party = new Party(TestNode.LENDER);
    Folder courier = new Folder(root);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> writer = executor.submit(() -> {
        for (int i = 0; i < 200; i++) courier.deliver(party, TestNode.disclosed("hold"));
      });
      for (int i = 0; i < 200; i++) {
        for (var disclosure : courier.collect(party)) {
          assertEquals("hold", disclosure.contractId.orElseThrow());
          assertEquals(TestNode.disclosed("hold").toProto(), disclosure.toProto());
        }
      }
      writer.get(5, TimeUnit.SECONDS);
      assertEquals(1, courier.collect(party).size());
    } finally { executor.shutdownNow(); }
  }
}
