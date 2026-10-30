package me.lovelace.loveAuth.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class AnniversaryTest {
    private static final ZoneId ZONE = ZoneOffset.UTC;

    private static long at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atTime(12, 0).toEpochSecond(ZoneOffset.UTC);
    }

    @Test
    void theSameDayAndMonthAYearLaterCounts() {
        assertEquals(1, Anniversary.years(at(2025, 10, 30), LocalDate.of(2026, 10, 30), ZONE));
        assertEquals(3, Anniversary.years(at(2023, 10, 30), LocalDate.of(2026, 10, 30), ZONE));
    }

    @Test
    void anyOtherDayIsOrdinary() {
        assertEquals(0, Anniversary.years(at(2025, 10, 30), LocalDate.of(2026, 10, 29), ZONE));
        assertEquals(0, Anniversary.years(at(2025, 10, 30), LocalDate.of(2026, 10, 31), ZONE));
        assertEquals(0, Anniversary.years(at(2025, 10, 30), LocalDate.of(2026, 11, 30), ZONE));
    }

    @Test
    void theRegistrationDayItselfIsNotAnAnniversary() {
        assertEquals(0, Anniversary.years(at(2026, 10, 30), LocalDate.of(2026, 10, 30), ZONE));
        assertEquals(0, Anniversary.years(at(2026, 10, 30), LocalDate.of(2025, 10, 30), ZONE), "a clock set back");
    }

    @Test
    void aLeapDayIsCelebratedOnTheTwentyEighthInOrdinaryYears() {
        assertEquals(1, Anniversary.years(at(2024, 2, 29), LocalDate.of(2025, 2, 28), ZONE));
        assertEquals(0, Anniversary.years(at(2024, 2, 29), LocalDate.of(2025, 3, 1), ZONE));
        assertEquals(4, Anniversary.years(at(2024, 2, 29), LocalDate.of(2028, 2, 29), ZONE));
        assertEquals(0, Anniversary.years(at(2024, 2, 29), LocalDate.of(2028, 2, 28), ZONE), "28th is for ordinary years only");
    }

    @Test
    void missingDataIsNoAnniversary() {
        assertEquals(0, Anniversary.years(0, LocalDate.of(2026, 10, 30), ZONE));
        assertEquals(0, Anniversary.years(-5, LocalDate.of(2026, 10, 30), ZONE));
        assertEquals(0, Anniversary.years(at(2025, 10, 30), null, ZONE));
    }

    @Test
    void theZoneDecidesWhichDayItWas() {
        long lateEvening = LocalDate.of(2025, 10, 30).atTime(23, 30).toEpochSecond(ZoneOffset.UTC);
        assertEquals(1, Anniversary.years(lateEvening, LocalDate.of(2026, 10, 30), ZoneOffset.UTC));
        assertEquals(1, Anniversary.years(lateEvening, LocalDate.of(2026, 10, 31), ZoneOffset.ofHours(3)));
    }
}
