package me.lovelace.loveAuth.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Pure date rules of the registration anniversary (no Bukkit types), so they can be unit-tested. */
public final class Anniversary {
    private Anniversary() {
    }

    /**
     * Full years since registration when {@code today} is the anniversary, otherwise 0. A registration on 29 February
     * is celebrated on 29 February in a leap year and on 28 February otherwise.
     *
     * @param registeredAtEpochSeconds the registered_at column
     */
    public static int years(long registeredAtEpochSeconds, LocalDate today, ZoneId zone) {
        if (registeredAtEpochSeconds <= 0 || today == null) return 0;
        LocalDate registered = Instant.ofEpochSecond(registeredAtEpochSeconds).atZone(zone).toLocalDate();
        int years = today.getYear() - registered.getYear();
        if (years < 1) return 0;
        LocalDate anniversary = registered.plusYears(years);
        return anniversary.equals(today) ? years : 0;
    }
}
