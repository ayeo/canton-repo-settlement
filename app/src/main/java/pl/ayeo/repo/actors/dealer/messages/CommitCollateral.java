package pl.ayeo.repo.actors.dealer.messages;

import java.math.BigDecimal;
import java.util.List;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.ledger.Party;

public record CommitCollateral(TradeId tradeId, Isin isin, BigDecimal quantity)
    implements DealerMessage {
}
