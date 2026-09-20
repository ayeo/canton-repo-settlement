package pl.ayeo.repo.actors.icsd.messages;

import java.math.BigDecimal;
import pl.ayeo.repo.actors.domain.Isin;

public record IssueSecurities(Isin isin, BigDecimal quantity, String owner, boolean again)
    implements IcsdMessage {
}
