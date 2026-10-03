package uk.gov.hmcts.cp.resultsstore.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The calendar day a share was made on, by the UK clock and by UTC (R6). They differ between
 * 00:00 and 01:00 while British Summer Time is in force.
 *
 * @param london the day in {@code Europe/London}
 * @param utc    the day in UTC
 */
public record SharedDays(LocalDate london, LocalDate utc) {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * The {@code shared_at} range of London register days, both ends included: London midnight at the start
     * of {@code from} to London midnight at the start of the day after {@code to}, half-open, so a 23- or
     * 25-hour day at a clock change is covered exactly (FR-027; research R9).
     *
     * @param from the first London day
     * @param to   the last London day, included
     * @return the half-open instant range
     */
    public static InstantRange londonDays(final LocalDate from, final LocalDate to) {
        return new InstantRange(from.atStartOfDay(LONDON).toInstant(),
                to.plusDays(1).atStartOfDay(LONDON).toInstant());
    }

    /**
     * Works out both days of an instant.
     *
     * @param sharedAt the share's {@code sharedTime}
     * @return its London and UTC days
     */
    public static SharedDays from(final Instant sharedAt) {
        return new SharedDays(sharedAt.atZone(LONDON).toLocalDate(),
                sharedAt.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    /**
     * A half-open range of instants, [{@code from}, {@code to}).
     *
     * @param from the first instant included
     * @param to   the first instant excluded
     */
    public record InstantRange(Instant from, Instant to) {
    }
}
