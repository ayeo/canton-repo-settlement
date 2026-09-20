package pl.ayeo.repo.core;

import com.daml.ledger.javaapi.data.ArchivedEvent;
import com.daml.ledger.javaapi.data.codegen.Contract;
import com.daml.ledger.javaapi.data.codegen.ContractId;

public record ContractRef(String id) {

  public ContractRef {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("a contract identifier is not empty");
    }
  }

  public static ContractRef of(Contract<?, ?> contract) {
    return new ContractRef(((ContractId<?>) contract.id).contractId);
  }

  public static ContractRef of(ArchivedEvent archived) {
    return new ContractRef(archived.getContractId());
  }

  @Override
  public String toString() {
    return id;
  }
}
