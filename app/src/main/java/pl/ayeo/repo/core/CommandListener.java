package pl.ayeo.repo.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface CommandListener extends AutoCloseable {

  void listen(Function<Instruction, CompletableFuture<Reply>> desk);
  @Override
  default void close() {}
}
