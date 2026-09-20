package pl.ayeo.repo.core.render;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public final class Amounts {

  private static final BigDecimal THOUSAND = new BigDecimal("1000");
  private static final BigDecimal MILLION = new BigDecimal("1000000");
  private static final BigDecimal BILLION = new BigDecimal("1000000000");

  public static String money(BigDecimal amount) {
    return shorthand(amount)
        .orElseGet(() -> amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString());
  }

  public static String quantity(BigDecimal quantity) {
    return shorthand(quantity).orElseGet(() -> quantity.stripTrailingZeros().toPlainString());
  }

  private static Optional<String> shorthand(BigDecimal amount) {
    BigDecimal size = amount.abs();
    String sign = amount.signum() < 0 ? "-" : "";
    if (size.compareTo(BILLION) >= 0 && divides(size, BILLION)) {
      return Optional.of(sign + whole(size, BILLION) + "kkk");
    }
    if (size.compareTo(MILLION) >= 0 && divides(size, MILLION)) {
      return Optional.of(sign + whole(size, MILLION) + "kk");
    }
    if (size.compareTo(THOUSAND) >= 0 && divides(size, THOUSAND)) {
      return Optional.of(sign + whole(size, THOUSAND) + "k");
    }
    return Optional.empty();
  }

  private static boolean divides(BigDecimal amount, BigDecimal unit) {
    return amount.remainder(unit).compareTo(BigDecimal.ZERO) == 0;
  }

  private static String whole(BigDecimal amount, BigDecimal unit) {
    return amount.divide(unit).stripTrailingZeros().toPlainString();
  }

  public static String price(BigDecimal price) {
    return price.stripTrailingZeros().toPlainString();
  }

  public static String percent(BigDecimal rate) {
    return rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%";
  }

  private Amounts() {}
}
