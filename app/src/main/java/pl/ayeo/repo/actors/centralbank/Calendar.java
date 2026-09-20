package pl.ayeo.repo.actors.centralbank;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Set;

public final class Calendar {

  private static final Set<MonthDay> HOLIDAYS =
      Set.of(
          MonthDay.of(1, 1), // New Year's Day
          MonthDay.of(5, 1), // Labour Day
          MonthDay.of(12, 25), // Christmas Day
          MonthDay.of(12, 26)); // Boxing Day

  public static boolean isBusinessDay(LocalDate day) {
    return day.getDayOfWeek() != DayOfWeek.SATURDAY
        && day.getDayOfWeek() != DayOfWeek.SUNDAY
        && !HOLIDAYS.contains(MonthDay.from(day));
  }

  public static LocalDate nextBusinessDay(LocalDate day) {
    LocalDate candidate = day.plusDays(1);
    while (!isBusinessDay(candidate)) {
      candidate = candidate.plusDays(1);
    }
    return candidate;
  }

  public static LocalDate rollModifiedFollowing(LocalDate day) {
    if (isBusinessDay(day)) {
      return day;
    }
    LocalDate forward = nextBusinessDay(day);
    if (forward.getMonth() == day.getMonth()) {
      return forward;
    }
    LocalDate backward = day.minusDays(1);
    while (!isBusinessDay(backward)) {
      backward = backward.minusDays(1);
    }
    return backward;
  }

  public static LocalDate addBusinessDays(LocalDate day, int days) {
    LocalDate result = day;
    for (int i = 0; i < days; i++) {
      result = nextBusinessDay(result);
    }
    return result;
  }

  private Calendar() {}
}
