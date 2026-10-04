package uk.gov.hmcts.cp.resultsstore.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.resultsstore.application.PullQuery;
import uk.gov.hmcts.cp.resultsstore.application.SearchQuery;
import uk.gov.hmcts.cp.resultsstore.application.ShareQueries;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadForm;
import uk.gov.hmcts.cp.resultsstore.domain.ProjectionStatus;
import uk.gov.hmcts.cp.resultsstore.domain.SearchCursor;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.domain.StoredPayload;

/**
 * The read queries (data-model.md *Read queries*; research R4, R7, R9, R10, R15). Each call is one autocommit
 * statement, read-only, over the {@link JdbcTemplate} it is given, whose query timeout bounds it (FR-045).
 *
 * <p>Every statement is a fixed constant with bound parameters. The variants of pull (day filter × court) and
 * search (day filter × latest only × cursor) are built once, when the class loads, from fixed fragments, and
 * chosen by the checked query's values; nothing a caller sends becomes SQL text. Only {@link #PAYLOAD_SQL} and
 * {@link #ARRIVED_SQL} name {@code hearing_share_payload} (Principle III). The working copy is read without
 * {@code _metadata} ({@code payload_json - '_metadata'}); the arrived text, read by the payload query only when
 * there is no working copy and by the arrived query always, is returned as stored and stripped by the service
 * (E8). Neither reads {@code payload_sha256}, which is never served.
 *
 * <p>Pull safety: a pull returns only rows at or below the visibility bound, the highest {@code stored_seq}
 * stored at or before the database's {@code now()} minus the lag, worked out in the same statement as the
 * page, which also returns that bound and {@code visibleUpTo}. The filter is on the bound, never on each row's
 * own {@code stored_at} (research R4).
 */
public class JdbcShareQueries implements ShareQueries {

    /** The share item's columns, in {@link #view} order; every read query but the payload's selects them. */
    private static final String ITEM_COLUMNS = """
            s.share_id, s.hearing_id, s.hearing_day, s.shared_at, s.stored_seq, s.stored_at,
                   s.shared_day_london, s.shared_day_utc, s.court_centre_id, s.court_room_id, s.lja_code,
                   s.jurisdiction_type, s.is_sjp, s.is_group_proceedings, s.youth_court_id, s.is_reshare,
                   s.any_subject_is_youth, s.day_youth_seen, s.is_latest, s.predecessor_share_id,
                   s.arrived_out_of_order, s.enrichment_applied, s.projection_status, s.projection_version,
                   s.projected_at,""";

    /** The share's place in its day by {@code shared_at}, on {@code hearing_share_identity_uk}. */
    private static final String VERSION_NUMBER = """
             (SELECT count(*) FROM hearing_share v
                     WHERE v.hearing_id = s.hearing_id AND v.hearing_day = s.hearing_day
                       AND v.shared_at <= s.shared_at) AS version_number""";

    private static final String PULL_HEAD = """
            WITH bound AS (
                SELECT now() - make_interval(secs => :lagSeconds) AS visible_up_to,
                       (SELECT b.stored_seq FROM hearing_share b
                         WHERE b.stored_at <= now() - make_interval(secs => :lagSeconds)
                         ORDER BY b.stored_seq DESC
                         LIMIT 1) AS max_seq
            )
            SELECT bound.visible_up_to, bound.max_seq, page.*
              FROM bound
              LEFT JOIN LATERAL (
                    SELECT\s""" + ITEM_COLUMNS + VERSION_NUMBER + """

                      FROM hearing_share s
                     WHERE s.stored_seq > :storedAfterSeq
                       AND s.stored_seq <= bound.max_seq
            """;

    private static final String PULL_TAIL = """
                     ORDER BY s.stored_seq
                     LIMIT :rowLimit) page ON TRUE
            """;

    private static final String SEARCH_HEAD = "SELECT " + ITEM_COLUMNS + VERSION_NUMBER + """

              FROM hearing_share s
             WHERE s.court_centre_id = :courtCentreId
               AND s.shared_at >= :sharedFrom
               AND s.shared_at < :sharedTo
            """;

    private static final String SEARCH_TAIL = """
             ORDER BY s.shared_at, s.share_id
             LIMIT :rowLimit
            """;

    private static final String COURT = "   AND s.court_centre_id = :courtCentreId\n";

    private static final String LATEST_ONLY = "   AND s.is_latest\n";

    private static final String AFTER_CURSOR = "   AND (s.shared_at, s.share_id) > (:cursorAt, :cursorId)\n";

    /** One share, on {@code hearing_share_pk}. */
    /* default */ static final String SHARE_SQL = "SELECT " + ITEM_COLUMNS + VERSION_NUMBER + """

              FROM hearing_share s
             WHERE s.share_id = :shareId
            """;

    /** The day's versions in {@code shared_at} order, on {@code hearing_share_identity_uk}. */
    /* default */ static final String DAY_VERSIONS_SQL = "SELECT " + ITEM_COLUMNS + """
             row_number() OVER (ORDER BY s.shared_at) AS version_number
              FROM hearing_share s
             WHERE s.hearing_id = :hearingId AND s.hearing_day = :hearingDay
             ORDER BY s.shared_at
            """;

    /** The payload: the working copy without {@code _metadata}, or the arrived text when there is none. */
    /* default */ static final String PAYLOAD_SQL = """
            SELECT s.share_id, s.hearing_id, s.hearing_day, s.shared_at, s.enrichment_applied,
                   CASE WHEN p.payload_json IS NULL THEN p.payload_text
                        ELSE (p.payload_json - '_metadata')::text END AS body,
                   p.payload_json IS NULL AS arrived_text
              FROM hearing_share s
              JOIN hearing_share_payload p ON p.share_id = s.share_id
             WHERE s.share_id = :shareId
            """;

    /** The text as it arrived (phase D): {@code payload_text} whatever the working copy holds. */
    /* default */ static final String ARRIVED_SQL = """
            SELECT s.share_id, s.hearing_id, s.hearing_day, s.shared_at, s.enrichment_applied,
                   p.payload_text AS body
              FROM hearing_share s
              JOIN hearing_share_payload p ON p.share_id = s.share_id
             WHERE s.share_id = :shareId
            """;

    /** Pull: indexed by day filter, then court (false, true). */
    private static final Map<DayYouthFilter, List<String>> PULL_SQL = new EnumMap<>(DayYouthFilter.class);

    /** Search: indexed by day filter, then latest only and cursor as two bits (latest × 2 + cursor). */
    private static final Map<DayYouthFilter, List<String>> SEARCH_SQL = new EnumMap<>(DayYouthFilter.class);

    private static final String LAG_SECONDS = "lagSeconds";

    private static final String ROW_LIMIT = "rowLimit";

    private static final String COURT_CENTRE_ID = "courtCentreId";

    private static final String SHARE_ID = "shareId";

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    static {
        for (final DayYouthFilter filter : DayYouthFilter.values()) {
            final String youth = youthPredicate(filter);
            if (filter.allowedOnPull()) {
                PULL_SQL.put(filter, List.of(PULL_HEAD + youth + PULL_TAIL, PULL_HEAD + youth + COURT + PULL_TAIL));
            }
            SEARCH_SQL.put(filter, List.of(SEARCH_HEAD + youth + SEARCH_TAIL,
                    SEARCH_HEAD + youth + AFTER_CURSOR + SEARCH_TAIL,
                    SEARCH_HEAD + LATEST_ONLY + youth + SEARCH_TAIL,
                    SEARCH_HEAD + LATEST_ONLY + youth + AFTER_CURSOR + SEARCH_TAIL));
        }
    }

    private final JdbcClient jdbc;

    /**
     * Creates the queries.
     *
     * @param jdbc the read template; its query timeout bounds every statement
     */
    public JdbcShareQueries(final JdbcTemplate jdbc) {
        this.jdbc = JdbcClient.create(jdbc);
    }

    /**
     * The pull statement of a variant.
     *
     * @param dayYouthSeen the day filter; never {@link DayYouthFilter#FALSE}
     * @param byCourt      whether the pull names a court
     * @return the fixed statement
     */
    /* default */ static String pullSql(final DayYouthFilter dayYouthSeen, final boolean byCourt) {
        if (!dayYouthSeen.allowedOnPull()) {
            throw new IllegalArgumentException("dayYouthSeen=false is not a pull filter");
        }
        return PULL_SQL.get(dayYouthSeen).get(byCourt ? 1 : 0);
    }

    /**
     * The search statement of a variant.
     *
     * @param dayYouthSeen the day filter
     * @param latestOnly   whether only latest shares are wanted
     * @param afterCursor  whether the search continues after a cursor
     * @return the fixed statement
     */
    /* default */ static String searchSql(final DayYouthFilter dayYouthSeen, final boolean latestOnly, final boolean afterCursor) {
        return SEARCH_SQL.get(dayYouthSeen).get((latestOnly ? 2 : 0) + (afterCursor ? 1 : 0));
    }

    @Override
    public PullRows pull(final PullQuery query, final Duration lag) {
        final List<PullRow> rows = jdbc.sql(pullSql(query.dayYouthSeen(), query.courtCentreId() != null))
                .param(LAG_SECONDS, lag.toNanos() / NANOS_PER_SECOND)
                .param("storedAfterSeq", query.storedAfterSeq())
                .param(COURT_CENTRE_ID, query.courtCentreId())
                .param(ROW_LIMIT, query.limit() + 1)
                .query((row, rowNumber) -> new PullRow(instant(row, "visible_up_to"), nullableLong(row, "max_seq"),
                        row.getObject("share_id", UUID.class) == null ? null : view(row)))
                .list();
        // The LEFT JOIN on the one-row bound always gives at least one row; an empty page has no share.
        final PullRow first = rows.getFirst();
        final List<ShareView> page = rows.stream()
                .map(PullRow::view)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ShareView::storedSeq))
                .toList();
        return new PullRows(first.visibleUpTo(), first.boundSeq(), page);
    }

    @Override
    public List<ShareView> search(final SearchQuery query) {
        final SearchCursor after = query.after();
        return jdbc.sql(searchSql(query.dayYouthSeen(), query.latestOnly(), after != null))
                .param(COURT_CENTRE_ID, query.courtCentreId())
                .param("sharedFrom", utc(query.sharedFrom()))
                .param("sharedTo", utc(query.sharedTo()))
                .param("cursorAt", after == null ? null : utc(after.sharedAt()))
                .param("cursorId", after == null ? null : after.shareId())
                .param(ROW_LIMIT, query.limit() + 1)
                .query((row, rowNumber) -> view(row))
                .list();
    }

    @Override
    public Optional<ShareView> share(final UUID shareId) {
        return jdbc.sql(SHARE_SQL).param(SHARE_ID, shareId).query((row, rowNumber) -> view(row)).optional();
    }

    @Override
    public List<ShareView> dayVersions(final UUID hearingId, final LocalDate hearingDay) {
        return jdbc.sql(DAY_VERSIONS_SQL)
                .param("hearingId", hearingId)
                .param("hearingDay", hearingDay)
                .query((row, rowNumber) -> view(row))
                .list();
    }

    @Override
    public Optional<StoredPayload> payload(final UUID shareId) {
        return jdbc.sql(PAYLOAD_SQL)
                .param(SHARE_ID, shareId)
                .query((row, rowNumber) -> storedPayload(row,
                        row.getBoolean("arrived_text") ? PayloadForm.ARRIVED_TEXT : PayloadForm.WORKING_COPY))
                .optional();
    }

    @Override
    public Optional<StoredPayload> arrivedText(final UUID shareId) {
        return jdbc.sql(ARRIVED_SQL)
                .param(SHARE_ID, shareId)
                .query((row, rowNumber) -> storedPayload(row, PayloadForm.ARRIVED_TEXT))
                .optional();
    }

    private static StoredPayload storedPayload(final ResultSet row, final PayloadForm form) throws SQLException {
        return new StoredPayload(row.getObject("share_id", UUID.class), row.getObject("hearing_id", UUID.class),
                row.getObject("hearing_day", LocalDate.class), instant(row, "shared_at"),
                row.getBoolean("enrichment_applied"), row.getString("body"), form);
    }

    private static String youthPredicate(final DayYouthFilter filter) {
        return switch (filter) {
            case ANY -> "";
            case NOT_FALSE -> "   AND s.day_youth_seen IS NOT FALSE\n";
            // Written so the planner can prove hearing_share_youth_feed_ix's predicate.
            case TRUE -> "   AND s.day_youth_seen IS NOT FALSE AND s.day_youth_seen\n";
            case FALSE -> "   AND s.day_youth_seen IS FALSE\n";
        };
    }

    private static ShareView view(final ResultSet row) throws SQLException {
        final ProjectionStatus status = ProjectionStatus.valueOf(row.getString("projection_status"));
        final KeyDetails keyDetails = status == ProjectionStatus.FAILED ? null
                : new KeyDetails(row.getObject("court_centre_id", UUID.class),
                        row.getObject("court_room_id", UUID.class), row.getString("lja_code"),
                        row.getString("jurisdiction_type"), row.getObject("is_sjp", Boolean.class),
                        row.getObject("is_group_proceedings", Boolean.class),
                        row.getObject("youth_court_id", UUID.class), row.getObject("is_reshare", Boolean.class));
        return new ShareView(row.getObject("share_id", UUID.class), row.getObject("hearing_id", UUID.class),
                row.getObject("hearing_day", LocalDate.class), instant(row, "shared_at"), row.getLong("stored_seq"),
                instant(row, "stored_at"), row.getObject("shared_day_london", LocalDate.class),
                row.getObject("shared_day_utc", LocalDate.class), keyDetails,
                row.getObject("any_subject_is_youth", Boolean.class), row.getObject("day_youth_seen", Boolean.class),
                row.getBoolean("is_latest"), row.getObject("predecessor_share_id", UUID.class),
                row.getBoolean("arrived_out_of_order"), row.getBoolean("enrichment_applied"), status,
                row.getInt("projection_version"), instant(row, "projected_at"), row.getLong("version_number"));
    }

    private static Instant instant(final ResultSet row, final String column) throws SQLException {
        final OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(final ResultSet row, final String column) throws SQLException {
        final long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    /** {@code timestamptz} bound as a {@code java.time} value at UTC. */
    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One row of the pull statement: the bound's two values, and the share when the page has one. */
    private record PullRow(Instant visibleUpTo, Long boundSeq, ShareView view) {
    }
}
