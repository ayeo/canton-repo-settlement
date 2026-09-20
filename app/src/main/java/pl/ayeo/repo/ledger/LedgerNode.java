package pl.ayeo.repo.ledger;

import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.Event;
import com.daml.ledger.javaapi.data.codegen.Update;
import java.util.List;
import java.util.function.Consumer;
import pl.ayeo.repo.core.ContractRef;

public record LedgerNode(LedgerClient client, String key, LedgerConfig config)
    implements Node {

  @Override
  public Party party() {
    return new Party(config.role(key).party());
  }

  @Override
  public Party party(String other) {
    return new Party(config.role(other).party());
  }

  @Override
  public List<Event> submit(
      String intent, List<Update<?>> commands, List<DisclosedContract> disclosed) {
    return client.submit(party().id(), intent, commands, disclosed);
  }

  @Override
  public DisclosedContract disclosure(ContractRef contractId) {
    return client.disclosure(party().id(), contractId.id());
  }

  @Override
  public void replay(long fromExclusive, long toInclusive, Consumer<Event> handler) {
    client
        .transactions(party().id(), fromExclusive, toInclusive)
        .forEach(transaction -> transaction.getEvents().forEach(handler));
  }

  @Override
  public long ledgerEnd() {
    return client.ledgerEnd();
  }

  @Override
  public void subscribe(long fromOffset, Consumer<Event> handler) {
    client.watchAll(party().id(), fromOffset, handler);
  }
}
