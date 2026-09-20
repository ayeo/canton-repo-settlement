package pl.ayeo.repo.core;

public record Reply(Status status, String message) {
  public enum Status { OK, NOT_READY, ERROR }

  public static Reply ok(String message) { return new Reply(Status.OK, message); }
  public static Reply notReady(String message) { return new Reply(Status.NOT_READY, message); }
  public static Reply error(String message) { return new Reply(Status.ERROR, message); }

  public int exitCode() {
    return switch (status) {
      case OK -> 0;
      case NOT_READY -> 2;
      case ERROR -> 1;
    };
  }
}
