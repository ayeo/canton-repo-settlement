package pl.ayeo.repo.actors.domain;

public record Reference(String name) {

  public Reference {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a settlement reference is not empty");
    }
  }

  public static Reference of(String name) {
    return new Reference(name);
  }

  @Override
  public String toString() {
    return name;
  }
}
