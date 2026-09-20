package pl.ayeo.repo.actors.domain;

public record TradeId(String id) {

  public TradeId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a trade has an identifier");
    }
    if (id.contains(":")) {
      // That is what a reference looks like: somebody passed a leg, not a trade.
      throw new IllegalArgumentException("a trade identifier is not a reference: " + id);
    }
  }

  public Reference opening() {
    return new Reference(id + ":opening");
  }

  public Reference closing() {
    return new Reference(id + ":closing");
  }

  @Override
  public String toString() {
    return id;
  }
}
