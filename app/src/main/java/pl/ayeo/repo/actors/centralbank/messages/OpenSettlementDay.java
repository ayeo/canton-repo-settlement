package pl.ayeo.repo.actors.centralbank.messages;

import java.time.LocalDate;

public record OpenSettlementDay(LocalDate target) implements CentralBankMessage {
}
