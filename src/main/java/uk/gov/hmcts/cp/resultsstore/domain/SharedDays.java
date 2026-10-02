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
     * Works out both days of an instant.
     *
     * @param sharedAt the share's {@code sharedTime}
     * @return its London and UTC days
     */
    public static SharedDays from(final Instant sharedAt) {
        return new SharedDays(sharedAt.atZone(LONDON).toLocalDate(),
                sharedAt.atOffset(ZoneOffset.UTC).toLocalDate());
    }
}
