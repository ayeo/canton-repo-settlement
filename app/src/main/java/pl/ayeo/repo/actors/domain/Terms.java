package pl.ayeo.repo.actors.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import pl.ayeo.repo.model.terms.RepoTerms;

public record Terms(
    TradeId tradeId,
    Isin isin,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal haircut,
    Currency currency,
    BigDecimal repoRate,
    LocalDate tradeDate,
    LocalDate purchaseDate,
    LocalDate repurchaseDate) {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private static final int SCALE = 10;

  public static Terms of(RepoTerms terms) {
    return new Terms(
        new TradeId(terms.tradeId), new Isin(terms.isin), terms.quantity, terms.price, terms.haircut,
        new Currency(terms.currency), terms.repoRate, terms.tradeDate, terms.purchaseDate,
        terms.repurchaseDate);
  }

  public RepoTerms toModel() {
    return new RepoTerms(
        tradeId.id(), isin.code(), quantity, price, haircut, currency.code(), repoRate,
        tradeDate, purchaseDate, repurchaseDate);
  }

  public BigDecimal marketValue() {
    return quantity.multiply(price).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN);
  }

  public BigDecimal purchasePrice() {
    return marketValue()
        .multiply(BigDecimal.ONE.subtract(haircut))
        .setScale(2, RoundingMode.HALF_EVEN);
  }

  public BigDecimal interest() {
    return purchasePrice()
        .multiply(repoRate)
        .multiply(BigDecimal.valueOf(days()))
        .divide(BigDecimal.valueOf(360), SCALE, RoundingMode.HALF_EVEN)
        .setScale(2, RoundingMode.HALF_EVEN);
  }

  public BigDecimal repurchasePrice() {
    return purchasePrice().add(interest());
  }

  public long days() {
    return ChronoUnit.DAYS.between(purchaseDate, repurchaseDate);
  }

  public Reference openingReference() {
    return tradeId.opening();
  }

  public Reference closingReference() {
    return tradeId.closing();
  }
}
