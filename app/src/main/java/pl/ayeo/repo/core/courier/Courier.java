package pl.ayeo.repo.core.courier;

import com.daml.ledger.javaapi.data.DisclosedContract;
import java.util.List;
import pl.ayeo.repo.ledger.Party;

public interface Courier {

  void deliver(Party to, DisclosedContract contract);

  List<DisclosedContract> collect(Party party);

  Courier NONE =
      new Courier() {
        @Override
        public void deliver(Party to, DisclosedContract contract) {
          throw new IllegalStateException("this institution has nobody to show anything to");
        }

        @Override
        public List<DisclosedContract> collect(Party party) {
          return List.of();
        }
      };
}
