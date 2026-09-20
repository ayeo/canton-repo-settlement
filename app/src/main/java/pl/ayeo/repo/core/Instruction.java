package pl.ayeo.repo.core;

import java.util.Objects;

/** One intentional instruction; reuse its ID only when retrying that instruction. */
public record Instruction(String id, Message message) {
  public Instruction {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("an instruction needs an ID");
    }
    Objects.requireNonNull(message, "message");
  }
}
