package pl.ayeo.repo.core.render;

import java.math.BigDecimal;
import java.time.LocalDate;
import pl.ayeo.repo.actors.domain.Terms;
import pl.ayeo.repo.ledger.LedgerConfig;

public final class Says {

  // ---------------------------------------------------------------- quoting ---

  public static String committed(BigDecimal quantity, String isin, String tradeId) {
    return "committed " + Amounts.quantity(quantity) + " " + isin + " to " + tradeId;
  }

  public static String offered(String lender, Terms terms) {
    return "offered "
        + terms.tradeId()
        + " to "
        + LedgerConfig.displayName(lender)
        + ": "
        + Amounts.money(terms.purchasePrice())
        + " EUR against "
        + Amounts.quantity(terms.quantity())
        + " "
        + terms.isin()
        + ", haircut "
        + Amounts.percent(terms.haircut())
        + ", "
        + Amounts.percent(terms.repoRate())
        + " to "
        + terms.repurchaseDate();
  }

  public static String pulled(String tradeId, String lender) {
    return "pulled "
        + tradeId
        + " from "
        + LedgerConfig.displayName(lender)
        + " - the quote was taken";
  }

  public static String accepted(String tradeId) {
    return "accepted " + tradeId + ", within our schedule";
  }

  // ------------------------------------------------------------- committing ---

  public static String allocatedPaper(BigDecimal quantity, String isin, String reference) {
    return "allocated " + Amounts.quantity(quantity) + " " + isin + " for " + reference;
  }

  public static String allocatedCash(BigDecimal amount, String currency, String reference) {
    return "allocated " + Amounts.money(amount) + " " + currency + " for " + reference;
  }

  public static String returned(String reference) {
    return "allocated the collateral back for " + reference;
  }

  public static String merged(BigDecimal owed, String currency) {
    return "merged two balances to cover " + Amounts.money(owed) + " " + currency;
  }

  // -------------------------------------------------------------- settling ---

  public static String settledOpening(String tradeId) {
    return "settled the opening leg of "
        + tradeId
        + ": collateral and cash moved in one transaction";
  }

  public static String settledClosing(String tradeId, BigDecimal paid) {
    return "settled the closing leg of "
        + tradeId
        + ": collateral returned, "
        + Amounts.money(paid)
        + " EUR paid";
  }

  public static String published(LocalDate validUntil) {
    return "published a schedule valid to " + validUntil;
  }

  // ------------------------------------------------------------- registers ---

  public static String registered(String isin) {
    return "registered " + isin;
  }

  public static String issued(BigDecimal quantity, String isin, String owner) {
    return "issued "
        + Amounts.quantity(quantity)
        + " "
        + isin
        + " to "
        + LedgerConfig.displayName(owner);
  }

  public static String credited(String owner, BigDecimal amount) {
    return "credited "
        + LedgerConfig.displayName(owner)
        + " with "
        + Amounts.money(amount)
        + " EUR";
  }

  public static String openedDay(LocalDate first) {
    return "opened settlement day " + first;
  }

  public static String rolledDay(LocalDate from, LocalDate to) {
    return "end of day: " + from + " -> " + to;
  }

  private Says() {}
}
