package pl.ayeo.repo.actors.lender.messages;

import java.math.BigDecimal;
import java.time.LocalDate;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;

public record PublishSchedule(
    Isin isin,
    BigDecimal minHaircut,
    BigDecimal maxQuantity,
    BigDecimal maxCash,
    Currency currency,
    BigDecimal rate,
    LocalDate validUntil)
    implements LenderMessage {
}
