package pl.ayeo.repo.core.render;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.Reference;
import pl.ayeo.repo.actors.domain.Terms;
import pl.ayeo.repo.core.Contracts;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.ledger.Party;
import pl.ayeo.repo.model.cash.AllocatedCash;
import pl.ayeo.repo.model.cash.CashBalance;
import pl.ayeo.repo.model.custody.AllocatedSecurity;
import pl.ayeo.repo.model.custody.SecurityPosition;
import pl.ayeo.repo.model.repo.OpenRepo;
import pl.ayeo.repo.model.repo.RepoTrade;

public final class Show {

  public static String positions(Party us, Party icsd, Contracts book) {
    Lines out = new Lines();
    book.every(SecurityPosition.Contract.class)
        .forEach(
            held ->
                out.add(
                    "  free      %-14s %s%s",
                    new Isin(held.data.isin),
                    Amounts.quantity(held.data.quantity),
                    elsewhere(icsd, held.data.icsd)));
    book.every(AllocatedSecurity.Contract.class)
        .forEach(
            hold ->
                out.add(
                    "  committed %-14s %s%s  to %s for %s",
                    new Isin(hold.data.isin),
                    Amounts.quantity(hold.data.quantity),
                    elsewhere(icsd, hold.data.icsd),
                    ours(us, hold.data.owner)
                        ? hold.data.takenBy.map(LedgerConfig::displayName).orElse("nobody yet")
                        : LedgerConfig.displayName(hold.data.owner),
                    Reference.of(hold.data.reference)));
    return out.orElse("  nothing held");
  }

  public static String cash(Party us, Contracts book) {
    Lines out = new Lines();
    // One line per currency: a bank that holds two should say so, and a bank
    // that holds none should not claim a balance of zero in something.
    currencies(book)
        .forEach(
            currency ->
                out.add("  free      %s %s", Amounts.money(freeCash(book, currency)), currency));
    book.every(AllocatedCash.Contract.class)
        .forEach(
            hold ->
                out.add(
                    "  committed %s %s  to %s for %s",
                    Amounts.money(hold.data.amount),
                    new Currency(hold.data.currency),
                    LedgerConfig.displayName(
                        ours(us, hold.data.owner) ? hold.data.counterparty : hold.data.owner),
                    Reference.of(hold.data.reference)));
    return out.orElse("  no balance");
  }

  public static String trades(Contracts book) {
    Lines out = new Lines();
    book.every(RepoTrade.Contract.class)
        .forEach(
            held -> {
              var repo = held.data;
              var terms = Terms.of(repo.terms);
              String opening = terms.openingReference().name();
              boolean collateral =
                  book.every(AllocatedSecurity.Contract.class).stream()
                      .anyMatch(hold -> hold.data.reference.equals(opening));
              boolean paid =
                  book.every(AllocatedCash.Contract.class).stream()
                      .anyMatch(hold -> hold.data.reference.equals(opening));
              out.add("  %s  agreed, settles %s", terms.tradeId(), terms.purchaseDate());
              out.add(
                  "      [%s] collateral   [%s] cash   waiting for: %s",
                  collateral ? "x" : " ",
                  paid ? "x" : " ",
                  collateral && paid
                      ? LedgerConfig.displayName(repo.buyer) + " to settle"
                      : collateral
                          ? LedgerConfig.displayName(repo.buyer)
                          : LedgerConfig.displayName(repo.seller));
            });
    book.every(OpenRepo.Contract.class)
        .forEach(
            held -> {
              var terms = Terms.of(held.data.terms);
              out.add(
                  "  %s  open, repurchase %s for %s",
                  terms.tradeId(), terms.repurchaseDate(), Amounts.money(terms.repurchasePrice()));
              out.add(
                  "      %s holds the collateral", LedgerConfig.displayName(held.data.buyer));
            });
    return out.orElse("  no trades");
  }

  private static List<Currency> currencies(Contracts book) {
    return book.every(CashBalance.Contract.class).stream()
        .map(held -> new Currency(held.data.currency))
        .distinct()
        .sorted(Comparator.comparing(Currency::code))
        .toList();
  }

  private static BigDecimal freeCash(Contracts book, Currency currency) {
    return book.every(CashBalance.Contract.class).stream()
        .filter(held -> new Currency(held.data.currency).equals(currency))
        .map(held -> held.data.amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  // Paper from a register nobody agreed on looks the same otherwise: same ISIN,
  // same quantity, and no repo will ever settle against it.
  private static String elsewhere(Party icsd, String register) {
    return icsd.id().equals(register) ? "" : "  (" + LedgerConfig.displayName(register) + "'s register)";
  }

  private static boolean ours(Party us, String owner) {
    return new Party(owner).equals(us);
  }

  private static final class Lines {

    private final List<String> lines = new ArrayList<>();

    void add(String format, Object... arguments) {
      lines.add(String.format(format, arguments).stripTrailing());
    }

    String orElse(String whenEmpty) {
      return lines.isEmpty() ? whenEmpty : String.join("\n", lines);
    }
  }

  private Show() {}
}
