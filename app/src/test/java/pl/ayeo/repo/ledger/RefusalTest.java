package pl.ayeo.repo.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;

class RefusalTest {

  @Test
  void readsContentionAsNotTheModelsAnswer() {
    Refusal refusal =
        Refusal.of(ledger("LOCAL_VERDICT_LOCKED_CONTRACTS", 2, "Inactive contracts: ...")).orElseThrow();

    assertFalse(refusal.byModel());
    assertEquals("another party got there first", refusal.says());
  }

  @Test
  void readsAMissingContractAsNotTheModelsAnswer() {
    Refusal refusal =
        Refusal.of(ledger("CONTRACT_NOT_FOUND", 11, "Contract could not be found")).orElseThrow();

    assertFalse(refusal.byModel());
    assertEquals("another party got there first", refusal.says());
  }

  @Test
  void keepsTheSentenceTheModelWrote() {
    Refusal refusal =
        Refusal.of(
                ledger(
                    "DAML_INTERPRETATION_ERROR",
                    9,
                    "the collateral was committed to another instruction"))
            .orElseThrow();

    assertTrue(refusal.byModel());
    assertEquals("the collateral was committed to another instruction", refusal.says());
  }

  @Test
  void readsTheCodeInTheMessageWhenTheMetadataIsMissing() {
    Refusal refusal =
        Refusal.of(bare("LOCAL_VERDICT_LOCKED_CONTRACTS(2,abc): locked")).orElseThrow();

    assertFalse(refusal.byModel());
  }

  @Test
  void readsARejectionThatCarriesNoCodeAtAll() {
    Refusal refusal =
        Refusal.of(bare("Rejected transaction is referring to inactive contracts")).orElseThrow();

    assertFalse(refusal.byModel());
  }

  @Test
  void looksThroughTheCauseChain() {
    Refusal refusal =
        Refusal.of(
                new RuntimeException(
                    "submission failed", ledger("LOCAL_VERDICT_LOCKED_CONTRACTS", 2, "locked")))
            .orElseThrow();

    assertFalse(refusal.byModel());
  }

  @Test
  void isNothingWhenOurOwnCodeFailed() {
    assertTrue(
        Refusal.of(new IllegalStateException("a dealer kept acting on the same message 20 times"))
            .isEmpty());
  }

  @Test
  void doesNotTreatTransportFailuresOrUnknownStatusesAsModelRejections() {
    for (Status status : new Status[] {Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED,
        Status.CANCELLED, Status.UNKNOWN, Status.INTERNAL, Status.PERMISSION_DENIED}) {
      Refusal refusal = Refusal.of(status.asRuntimeException()).orElseThrow();
      assertFalse(refusal.byModel());
      assertFalse(refusal.says().isBlank());
    }
  }

  // A ledger error as it arrives: the code in the message, the category in the metadata.
  private static StatusRuntimeException ledger(String id, int category, String message) {
    com.google.rpc.Status status =
        com.google.rpc.Status.newBuilder()
            .setCode(Code.ABORTED_VALUE)
            .setMessage(id + "(" + category + ",abc123): " + message)
            .addDetails(
                Any.pack(
                    ErrorInfo.newBuilder()
                        .setReason(id)
                        .putMetadata("category", String.valueOf(category))
                        .build()))
            .build();
    return StatusProto.toStatusRuntimeException(status);
  }

  // A status that lost its metadata on the way.
  private static StatusRuntimeException bare(String description) {
    return new StatusRuntimeException(Status.ABORTED.withDescription(description));
  }
}
