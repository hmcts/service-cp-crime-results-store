package uk.gov.hmcts.cp.resultsstore.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Every instant the read API writes (FR-005, research R16): UTC, {@code Z}, exactly six fraction digits, for
 * example {@code 2026-10-03T09:15:00.120000Z}. Jackson's own form drops a zero fraction, which would break a
 * consumer that compares the strings.
 */
public final class InstantFormat {

    private static final DateTimeFormatter SIX_DIGITS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private InstantFormat() {
        // Static functions only.
    }

    /**
     * Writes the instant, cut to the microsecond.
     *
     * @param instant the instant
     * @return its text
     */
    public static String format(final Instant instant) {
        return SIX_DIGITS.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * The Jackson module that writes every {@link Instant} this way.
     *
     * @return the module
     */
    public static SimpleModule module() {
        return new SimpleModule("results-store-instants").addSerializer(Instant.class, new Serializer());
    }

    /** Writes an {@link Instant} through {@link #format(Instant)}. */
    public static final class Serializer extends StdSerializer<Instant> {

        /** Creates the serializer. */
        public Serializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(final Instant value, final JsonGenerator generator, final SerializationContext context) {
            generator.writeString(format(value));
        }
    }
}
