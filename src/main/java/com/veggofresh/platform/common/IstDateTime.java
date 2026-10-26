package com.veggofresh.platform.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Admin screens enter dates in Indian time. A value without an offset ("2026-11-01T00:00:00") is read as IST; one with
 * an offset or "Z" is taken as given. Everything is stored as a UTC {@link Instant}.
 */
public final class IstDateTime {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter IST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private IstDateTime() {
    }

    /** Blank = empty. Throws {@link DateTimeParseException} when the text is not an ISO date-time. */
    public static Optional<Instant> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.trim();
        if (t.endsWith("Z") || t.matches(".*[+-]\\d{2}:\\d{2}$")) {
            return Optional.of(OffsetDateTime.parse(t).toInstant());
        }
        return Optional.of(LocalDateTime.parse(t).atZone(IST).toInstant());
    }

    /** "yyyy-MM-dd'T'HH:mm:ss" in Indian time, or null. */
    public static String format(Instant instant) {
        return instant == null ? null : IST_FORMAT.format(instant.atZone(IST));
    }
}
