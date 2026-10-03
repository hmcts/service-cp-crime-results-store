package uk.gov.hmcts.cp.resultsstore.persistence;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The day's youth flag, three values, recomputed under the day lock after each share is stored
 * (FR-026 to FR-028, research R15).
 *
 * <p>{@code youth_seen} is TRUE if any share of the day is TRUE; else NULL if any share is unknown;
 * else FALSE. TRUE stays TRUE by construction: a share's own value never changes once known. The
 * day's value is then copied to every share of the day that does not already carry it, which covers
 * the new share and, when the day's value changed, every older one.
 */
public final class YouthFlags {

    private static final String SUMMARY = """
            SELECT COALESCE(bool_or(any_subject_is_youth), FALSE) AS any_true,
                   COALESCE(bool_or(any_subject_is_youth IS NULL), FALSE) AS any_unknown
              FROM hearing_share
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay
            """;

    private static final String PROPAGATE = """
            UPDATE hearing_share SET day_youth_seen = CAST(:seen AS boolean)
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay
               AND day_youth_seen IS DISTINCT FROM CAST(:seen AS boolean)
            """;

    private static final String SET_DAY = """
            UPDATE hearing_day_head SET youth_seen = CAST(:seen AS boolean)
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay
               AND youth_seen IS DISTINCT FROM CAST(:seen AS boolean)
            """;

    private static final String HEARING_ID = "hearingId";

    private static final String HEARING_DAY = "hearingDay";

    private static final String SEEN = "seen";

    private final JdbcClient jdbc;

    /**
     * Creates the flags over the store's connection.
     *
     * @param jdbc the database, inside a transaction that holds the day lock
     */
    public YouthFlags(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Recomputes a day's flag and copies it to every share of the day.
     *
     * @param hearingId  the hearing
     * @param hearingDay the day
     * @return the day's flag: TRUE, FALSE, or {@code null} for unknown
     */
    public Boolean recompute(final UUID hearingId, final LocalDate hearingDay) {
        final Boolean seen = jdbc.sql(SUMMARY)
                .param(HEARING_ID, hearingId)
                .param(HEARING_DAY, hearingDay)
                .query((row, rowNumber) -> daySeen(row.getBoolean("any_true"), row.getBoolean("any_unknown")))
                .list()
                .getFirst();
        jdbc.sql(PROPAGATE).param(SEEN, seen).param(HEARING_ID, hearingId).param(HEARING_DAY, hearingDay).update();
        jdbc.sql(SET_DAY).param(SEEN, seen).param(HEARING_ID, hearingId).param(HEARING_DAY, hearingDay).update();
        return seen;
    }

    /**
     * The day's flag from its shares' values.
     *
     * @param anyTrue    whether any share is TRUE
     * @param anyUnknown whether any share is unknown
     * @return TRUE if any is TRUE; else {@code null} if any is unknown; else FALSE
     */
    public static Boolean daySeen(final boolean anyTrue, final boolean anyUnknown) {
        final Boolean seen;
        if (anyTrue) {
            seen = Boolean.TRUE;
        } else if (anyUnknown) {
            seen = null;
        } else {
            seen = Boolean.FALSE;
        }
        return seen;
    }
}
