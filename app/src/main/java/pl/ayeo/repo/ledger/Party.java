package pl.ayeo.repo.ledger;

import java.util.List;

public record Party(String id) {

  public Party {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a party has an identifier");
    }
  }

  public static List<String> asText(List<Party> parties) {
    return parties.stream().map(Party::id).toList();
  }

  @Override
  public String toString() {
    return id;
  }
}
