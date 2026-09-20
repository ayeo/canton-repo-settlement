package pl.ayeo.repo.actors;

import java.util.List;
import java.util.Locale;

public enum ActorRole {

  ICSD("icsd", "ICSD", "depository"),

  CENTRAL_BANK("centralbank", "CentralBank", "cb"),

  ALPHA("a", "AlphaBank", "alpha"),

  BRAVO("b", "BravoBank", "bravo"),

  CHARLIE("c", "CharlieBank", "charlie");

  private final String key;
  private final String display;
  private final String[] alsoKnownAs;

  ActorRole(String key, String display, String... alsoKnownAs) {
    this.key = key;
    this.display = display;
    this.alsoKnownAs = alsoKnownAs;
  }

  public String key() {
    return key;
  }

  public String display() {
    return display;
  }

  public static ActorRole of(String typed) {
    String wanted = typed.toLowerCase(Locale.ROOT);
    for (ActorRole role : values()) {
      if (role.key.equals(wanted) || role.display.toLowerCase(Locale.ROOT).equals(wanted)) {
        return role;
      }
      for (String alias : role.alsoKnownAs) {
        if (alias.equals(wanted)) {
          return role;
        }
      }
    }
    throw new IllegalArgumentException(
        "Unknown role '"
            + typed
            + "'. Expected one of: ICSD, CentralBank, AlphaBank, BravoBank, CharlieBank");
  }

  public static List<ActorRole> lenders() {
    return List.of(BRAVO, CHARLIE);
  }
}
