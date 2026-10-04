package uk.gov.hmcts.cp.resultsstore.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;

/**
 * The chain of a hearing day's shares, worked out under the day lock (FR-022 to FR-025, data-model
 * "Chain rules"). Latest is the greatest {@code shared_at}, never the arrival order.
 *
 * <p>{@link #place} runs before the share is inserted, because the share's predecessor and its
 * {@code arrived_out_of_order} flag are written with it and never change. {@link #join} runs after:
 * the newest share of the day takes over as latest (the old latest cleared first, as the one-latest
 * index is checked per statement); a late share becomes its successor's predecessor and leaves the
 * latest alone. Either way the day row counts the share.
 */
public final class ShareChain {

    private static final String PREDECESSOR = """
            SELECT share_id FROM hearing_share
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay AND shared_at < :sharedAt
             ORDER BY shared_at DESC LIMIT 1
            """;

    private static final String SUCCESSOR = """
            SELECT share_id FROM hearing_share
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay AND shared_at > :sharedAt
             ORDER BY shared_at LIMIT 1
            """;

    private static final String CLEAR_LATEST = """
            UPDATE hearing_share SET is_latest = FALSE
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay AND is_latest
            """;

    private static final String SET_LATEST = "UPDATE hearing_share SET is_latest = TRUE WHERE share_id = :shareId";

    private static final String RELINK = """
            UPDATE hearing_share SET predecessor_share_id = :shareId WHERE share_id = :successor
            """;

    /** Latest and count move together, in one statement; a late share leaves the latest as it is. */
    private static final String COUNT_DAY = """
            UPDATE hearing_day_head
               SET latest_share_id = COALESCE(CAST(:latest AS uuid), latest_share_id), share_count = share_count + 1
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay
            """;

    private static final String SHARE_ID = "shareId";

    private static final String HEARING_ID = "hearingId";

    private static final String HEARING_DAY = "hearingDay";

    private static final String SHARED_AT = "sharedAt";

    private final JdbcClient jdbc;

    /**
     * Creates the chain over the store's connection.
     *
     * @param jdbc the database, inside the store transaction
     */
    public ShareChain(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Where a share goes in its day's chain: the share shared just before it and the one just after.
     *
     * @param identity the share's identity
     * @return its place
     */
    public Place place(final ShareIdentity identity) {
        return new Place(neighbour(PREDECESSOR, identity), neighbour(SUCCESSOR, identity));
    }

    /**
     * Links an inserted share into its day's chain and counts it on the day row.
     *
     * @param identity the share's identity
     * @param shareId  the share
     * @param place    its place, found before it was inserted
     */
    public void join(final ShareIdentity identity, final UUID shareId, final Place place) {
        if (place.isLate()) {
            jdbc.sql(RELINK).param(SHARE_ID, shareId).param("successor", place.successor()).update();
        } else {
            day(CLEAR_LATEST, identity).update();
            jdbc.sql(SET_LATEST).param(SHARE_ID, shareId).update();
        }
        day(COUNT_DAY, identity).param("latest", place.isLate() ? null : shareId).update();
    }

    private UUID neighbour(final String sql, final ShareIdentity identity) {
        return day(sql, identity)
                .param(SHARED_AT, OffsetDateTime.ofInstant(identity.sharedAt(), ZoneOffset.UTC))
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private JdbcClient.StatementSpec day(final String sql, final ShareIdentity identity) {
        return jdbc.sql(sql).param(HEARING_ID, identity.hearingId()).param(HEARING_DAY, identity.hearingDay());
    }

    /**
     * A share's place in its day's chain.
     *
     * @param predecessor the share shared just before it, or {@code null} for the earliest
     * @param successor   the share shared just after it, or {@code null} for the newest
     */
    public record Place(UUID predecessor, UUID successor) {

        /** Whether a later share of the day was stored first ({@code arrived_out_of_order}). */
        public boolean isLate() {
            return successor != null;
        }
    }
}
