package pl.ayeo.repo.ledger;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Refusal(String says, boolean byModel) {

  private static final Set<String> LOST_RACE = Set.of("2", "11");


  private static final Pattern CODED = Pattern.compile("^([A-Z][A-Z_0-9]*)\\((\\d+),[^)]*\\): ?(.*)$");

  // Empty when the failure is our own code's: only a gRPC status comes from the ledger.
  public static Optional<Refusal> of(Throwable error) {
    return ledger(error).map(Refusal::read);
  }

  private static Refusal read(StatusRuntimeException failure) {
    String described = Optional.ofNullable(failure.getStatus().getDescription()).orElse("");
    String firstLine = described.lines().findFirst().orElse(described);
    Matcher coded = CODED.matcher(firstLine);
    boolean carriesItsCode = coded.matches();

    Optional<String> category = category(failure).or(() -> carriesItsCode ? Optional.of(coded.group(2)) : Optional.empty());
    boolean lostRace =
        category.map(LOST_RACE::contains)
            // A rejection can arrive as prose, with no code and no metadata.
            .orElseGet(() -> firstLine.contains("inactive contracts"));

    boolean transportFailure = switch (failure.getStatus().getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, UNKNOWN, INTERNAL -> true;
      default -> false;
    };
    boolean modelFailure = !transportFailure && carriesItsCode
        && coded.group(1).startsWith("DAML_");
    String reason = carriesItsCode ? coded.group(3) : firstLine;
    if (reason.isBlank()) {
      reason = failure.getStatus().getCode().name();
    }
    return lostRace ? new Refusal("another party got there first", false)
        : new Refusal(reason, modelFailure);
  }

  // The category Canton put in the error's metadata, when the status carried it.
  private static Optional<String> category(StatusRuntimeException failure) {
    com.google.rpc.Status status = StatusProto.fromThrowable(failure);
    if (status == null) {
      return Optional.empty();
    }
    for (Any detail : status.getDetailsList()) {
      if (detail.is(ErrorInfo.class)) {
        try {
          return Optional.ofNullable(detail.unpack(ErrorInfo.class).getMetadataMap().get("category"));
        } catch (InvalidProtocolBufferException malformed) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<StatusRuntimeException> ledger(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof StatusRuntimeException status) {
        return Optional.of(status);
      }
    }
    return Optional.empty();
  }
}
