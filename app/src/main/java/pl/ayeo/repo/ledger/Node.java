package pl.ayeo.repo.ledger;

import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.Event;
import com.daml.ledger.javaapi.data.codegen.Update;
import java.util.List;
import java.util.function.Consumer;
import pl.ayeo.repo.core.ContractRef;

public interface Node {

  Party party();

  Party party(String key);

  List<Event> submit(String intent, List<Update<?>> commands, List<DisclosedContract> disclosed);

  default List<Event> submit(String intent, Update<?> command) {
    return submit(intent, List.of(command), List.of());
  }

  DisclosedContract disclosure(ContractRef contractId);

  long ledgerEnd();

  void replay(long fromExclusive, long toInclusive, Consumer<Event> handler);

  void subscribe(long fromOffset, Consumer<Event> handler);
}
