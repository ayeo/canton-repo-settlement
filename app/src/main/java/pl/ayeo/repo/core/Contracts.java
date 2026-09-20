package pl.ayeo.repo.core;

import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.codegen.Contract;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Contracts {

  public record Seen(CreatedEvent created, Optional<Contract<?, ?>> contract, boolean archived) {

    public String template() {
      return created.getTemplateId().getEntityName();
    }
  }

  private final Map<ContractRef, Seen> seen = new LinkedHashMap<>();

  private final Set<ContractRef> archived = new HashSet<>();

  private final Codegen codegen = new Codegen();

  public boolean saw(CreatedEvent created) {
    ContractRef id = new ContractRef(created.getContractId());
    if (seen.containsKey(id) || archived.contains(id)) {
      return false;
    }
    seen.put(id, new Seen(created, codegen.read(created), false));
    return true;
  }

  public boolean archived(ContractRef id) {
    if (!archived.add(id)) {
      return false;
    }
    Seen was = seen.get(id);
    if (was != null) {
      seen.put(id, new Seen(was.created(), was.contract(), true));
    }
    return true;
  }

  public <C> List<C> every(Class<C> template) {
    return seen.values().stream()
        .filter(row -> !row.archived())
        .map(Seen::contract)
        .flatMap(Optional::stream)
        .filter(template::isInstance)
        .map(template::cast)
        .toList();
  }
}
