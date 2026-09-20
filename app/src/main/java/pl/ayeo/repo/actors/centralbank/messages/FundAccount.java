package pl.ayeo.repo.actors.centralbank.messages;

import java.math.BigDecimal;
import pl.ayeo.repo.actors.domain.Currency;

public record FundAccount(String owner, Currency currency, BigDecimal amount, boolean again) implements CentralBankMessage {
}
