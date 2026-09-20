package pl.ayeo.repo.actors.lender.messages;

import com.daml.ledger.javaapi.data.DisclosedContract;
import pl.ayeo.repo.core.ContractRef;

public record CollateralShown(DisclosedContract contract) implements LenderMessage {

  public ContractRef contractId() {
    return new ContractRef(
        contract.contractId.orElseThrow(
            () -> new IllegalStateException("a disclosed contract with no identifier")));
  }
}
