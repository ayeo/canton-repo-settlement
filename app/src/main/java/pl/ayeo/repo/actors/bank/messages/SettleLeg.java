package pl.ayeo.repo.actors.bank.messages;

import pl.ayeo.repo.actors.dealer.messages.DealerMessage;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.actors.lender.messages.LenderMessage;

public record SettleLeg(TradeId tradeId) implements DealerMessage, LenderMessage {
}
