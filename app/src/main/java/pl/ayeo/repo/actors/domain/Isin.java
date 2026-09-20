package pl.ayeo.repo.actors.domain;

public record Isin(String code) {

  public Isin {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("an instrument has an ISIN");
    }
  }

  @Override
  public String toString() {
    return code;
  }
}
