package pl.ayeo.repo.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public record LedgerConfig(Map<String, Role> everybody) {

  public record Role(String party, String endpoint) {

    public String host() {
      return endpoint.substring(0, endpoint.lastIndexOf(':'));
    }

    public int port() {
      return Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1));
    }

    public int deskPort() {
      return port() + 1000;
    }

    public String displayName() {
      return LedgerConfig.displayName(party);
    }
  }

  public static LedgerConfig load(Path file) throws IOException {
    if (!Files.exists(file)) {
      throw new IllegalStateException(
          "Missing file "
              + file.toAbsolutePath()
              + ". Run the Canton bootstrap first: make bootstrap");
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    }
    Map<String, Role> everybody = new LinkedHashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.endsWith(".party")) {
        continue;
      }
      String key = name.substring(0, name.length() - ".party".length());
      everybody.put(
          key,
          new Role(
              require(properties, key + ".party", file), require(properties, key + ".ledger", file)));
    }
    return new LedgerConfig(everybody);
  }

  private static String require(Properties properties, String key, Path file) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing key '" + key + "' in " + file);
    }
    return value;
  }

  public Role role(String key) {
    Role found = everybody.get(key);
    if (found == null) {
      throw new IllegalStateException(
          "Nobody called '" + key + "' in the deployment. Known: " + everybody.keySet());
    }
    return found;
  }

  public static LedgerConfig loadDefault() throws IOException {
    String configured = System.getProperty("ledger.config");
    if (configured != null) {
      return load(Path.of(configured));
    }
    // Run from the repository root or from app/, and find it either way.
    Path here = Path.of("ledger.properties");
    return load(Files.exists(here) ? here : Path.of("../ledger.properties"));
  }

  public static String displayName(String partyId) {
    int separator = partyId.indexOf("::");
    return separator < 0 ? partyId : partyId.substring(0, separator);
  }
}
