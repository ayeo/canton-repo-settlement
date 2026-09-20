package pl.ayeo.repo.core.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountsTest {

  @Test
  void saysRoundMillionsAndThousandsTheWayADeskDoes() {
    assertEquals("2kk", Amounts.money(new BigDecimal("2000000.0000000000")));
    assertEquals("1kk", Amounts.quantity(new BigDecimal("1000000")));
    assertEquals("10kk", Amounts.quantity(new BigDecimal("10000000")));
    assertEquals("9kk", Amounts.quantity(new BigDecimal("9000000")));
    assertEquals("1500k", Amounts.money(new BigDecimal("1500000")));
    assertEquals("25k", Amounts.money(new BigDecimal("25000.00")));
    assertEquals("-1kk", Amounts.quantity(new BigDecimal("-1000000")));
  }

  @Test
  void saysRoundBillionsInTheBiggestUnitThatFits() {
    assertEquals("1kkk", Amounts.quantity(new BigDecimal("1000000000")));
    assertEquals("-2kkk", Amounts.money(new BigDecimal("-2000000000.00")));
    assertEquals("1500kk", Amounts.quantity(new BigDecimal("1500000000")));
    assertEquals("635kk", Amounts.quantity(new BigDecimal("635000000")));
  }

  @Test
  void printsAnythingThatWouldHaveToBeRoundedInFull() {
    assertEquals("958526.89", Amounts.money(new BigDecimal("958526.8900000000")));
    assertEquals("957875.00", Amounts.money(new BigDecimal("957875")));
    assertEquals("1999348.11", Amounts.money(new BigDecimal("1999348.11")));
    assertEquals("999.00", Amounts.money(new BigDecimal("999")));
    assertEquals("9000001", Amounts.quantity(new BigDecimal("9000001")));
  }
}
