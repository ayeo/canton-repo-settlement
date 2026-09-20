package pl.ayeo.repo.ledger;

import java.util.ArrayList;
import java.util.List;

public record Market(Party icsd, Party centralBank, List<Party> dealers, List<Party> lenders) {

  public Market {
    dealers = List.copyOf(dealers);
    lenders = List.copyOf(lenders);
  }

  public List<Party> banks() {
    List<Party> everyone = new ArrayList<>(dealers);
    everyone.addAll(lenders);
    return List.copyOf(everyone);
  }

  public List<Party> everyoneWhoSettles() {
    List<Party> everyone = new ArrayList<>();
    everyone.add(icsd);
    everyone.addAll(banks());
    return List.copyOf(everyone);
  }
}
