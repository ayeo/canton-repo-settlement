package pl.ayeo.repo.core.courier;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.javaapi.data.DisclosedContract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import pl.ayeo.repo.ledger.Party;

public final class Folder implements Courier {

  private final Path root;

  public Folder() { this(Path.of(".repo-courier")); }

  public Folder(Path root) { this.root = root; }

  @Override
  public void deliver(Party to, DisclosedContract contract) {
    Path inbox = inboxOf(to);
    try {
      Files.createDirectories(inbox);
      Path temporary = Files.createTempFile(inbox, ".delivery-", ".tmp");
      try {
        Files.write(temporary, contract.toProto().toByteArray());
        Files.move(temporary, inbox.resolve(contract.contractId.orElseThrow() + ".disclosed"),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not hand a contract to " + to, e);
    }
  }

  @Override
  public List<DisclosedContract> collect(Party party) {
    Path inbox = inboxOf(party);
    if (!Files.isDirectory(inbox)) {
      return List.of();
    }
    List<DisclosedContract> handed = new ArrayList<>();
    try (Stream<Path> files = Files.list(inbox)) {
      for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".disclosed")).sorted().toList()) {
        handed.add(
            DisclosedContract.fromProto(
                CommandsOuterClass.DisclosedContract.parseFrom(Files.readAllBytes(file))));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read what was handed to " + party, e);
    }
    return handed;
  }

  private Path inboxOf(Party party) {
    return root.resolve(party.id().replace(':', '_'));
  }
}
