package pl.ayeo.repo.actors.domain;

public record Currency(String code) {

  public Currency {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("a currency has a code");
    }
  }

  @Override
  public String toString() {
    return code;
  }
}
