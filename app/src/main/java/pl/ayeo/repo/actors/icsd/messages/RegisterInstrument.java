package pl.ayeo.repo.actors.icsd.messages;

import java.math.BigDecimal;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;

public record RegisterInstrument(Isin isin, String issuer, Currency currency, BigDecimal price) implements IcsdMessage {
}
