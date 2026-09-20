package pl.ayeo.repo.actors.dealer.messages;

import java.math.BigDecimal;
import java.util.List;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.ledger.Party;

public record ShowQuote(
    TradeId tradeId,
    Isin isin,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal haircut,
    Currency currency,
    BigDecimal rate,
    int term,
    int settles,
    List<Party> lenders)
    implements DealerMessage {
}
