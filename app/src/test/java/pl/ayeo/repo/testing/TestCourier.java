package pl.ayeo.repo.testing;

import com.daml.ledger.javaapi.data.DisclosedContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.ayeo.repo.core.courier.Courier;
import pl.ayeo.repo.ledger.Party;

public final class TestCourier implements Courier {

  private final Map<Party, List<DisclosedContract>> inboxes = new LinkedHashMap<>();

  @Override
  public void deliver(Party to, DisclosedContract contract) {
    inboxes.computeIfAbsent(to, ignored -> new ArrayList<>()).add(contract);
  }

  @Override
  public List<DisclosedContract> collect(Party party) {
    return List.copyOf(inboxes.getOrDefault(party, List.of()));
  }

  public TestCourier shown(Party to, String contractId) {
    deliver(to, TestNode.disclosed(contractId));
    return this;
  }
}
