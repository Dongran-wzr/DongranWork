package com.dongran.work.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScheduleServiceTest {
  Map<String, Object> daily(boolean enabled) {
    return new LinkedHashMap<>(
        Map.of("enabled", enabled, "frequency", "daily", "time", "09:00", "weekdays", List.of()));
  }

  @Test
  void disabledPlanHasNoNextRun() {
    assertThat(ScheduleService.next(daily(false), Instant.now())).isNull();
  }

  @Test
  void dailyPlanSkipsPastOccurrences() {
    ZonedDateTime after = LocalDate.of(2026, 10, 1).atTime(10, 0).atZone(ZoneId.systemDefault());
    assertThat(ScheduleService.next(daily(true), after.toInstant()))
        .isEqualTo(after.plusDays(1).withHour(9).toInstant());
  }

  @Test
  void weeklyPlanChoosesRequestedWeekday() {
    var plan = daily(true);
    plan.put("frequency", "weekly");
    plan.put("weekdays", List.of(1));
    Instant after = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    assertThat(ScheduleService.next(plan, after))
        .isEqualTo(
            LocalDate.of(2026, 10, 5).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant());
  }

  @Test
  void singleOccurrenceDoesNotCatchUp() {
    var plan = daily(true);
    plan.put("frequency", "once");
    plan.put("date", "2026-09-30");
    Instant after = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    assertThat(ScheduleService.next(plan, after)).isNull();
  }
}
