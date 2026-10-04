package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.PullQuery;
import uk.gov.hmcts.cp.resultsstore.application.SearchQuery;
import uk.gov.hmcts.cp.resultsstore.application.ShareQueries.PullRows;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadForm;
import uk.gov.hmcts.cp.resultsstore.domain.ProjectionStatus;
import uk.gov.hmcts.cp.resultsstore.domain.SearchCursor;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;
import uk.gov.hmcts.cp.resultsstore.domain.StoredPayload;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;
import uk.gov.hmcts.cp.resultsstore.support.SampleShares;

/**
 * The read queries on PostgreSQL (data-model.md *Read queries* and invariants 2, 3, 6, 7, 8): pull safety and
 * the visibility bound, the filters, both search forms, one share, the day's versions and the payload.
 *
 * <p>Rows that need a chosen {@code stored_at} (or a flag the store would not write) are inserted on a test
 * connection with {@code session_replication_role = replica}, which skips the triggers and the foreign keys
 * for that insert only. The other rows are stored through {@link JdbcShareStore}. Payload assertions compare
 * hashes and booleans, so a failure prints no content.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("read queries")
class JdbcShareQueriesIT {

    private static final Duration LAG = Duration.ofSeconds(60);

    /** Stored long enough ago to be at or below the bound with {@link #LAG}. */
    private static final int OLD = 120;

    private static final UUID COURT_A = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");

    private static final UUID COURT_B = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000000b");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcShareQueries queries;

    private JdbcReceiptStore receipts;

    private JdbcShareStore store;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
        queries = new JdbcShareQueries(new JdbcTemplate(dataSource));
        receipts = new JdbcReceiptStore(jdbc, new TransactionTemplate(transactionManager));
        store = new JdbcShareStore(jdbc, new TransactionTemplate(transactionManager), receipts,
                JdbcShareStore.Timeouts.DEFAULTS);
    }

    /** Leaves no row with a chosen clock or a FAILED status for a later suite, the sweep included. */
    @AfterEach
    void removeRows() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
    }

    @Nested
    @DisplayName("pull")
    class PullQueries {

        @Test
        void pull_should_be_exclusive_of_the_cursor_and_ascending_by_stored_seq() {
            final long first = insert(row().storedSecondsAgo(OLD));
            final long second = insert(row().storedSecondsAgo(OLD));
            final long third = insert(row().storedSecondsAgo(OLD));
            final long fourth = insert(row().storedSecondsAgo(OLD));

            final PullRows rows = queries.pull(pull(first, 10), LAG);

            assertThat(seqs(rows.rows())).containsExactly(second, third, fourth);
            assertThat(rows.boundSeq()).isEqualTo(fourth);
        }

        @Test
        void pull_should_read_at_most_limit_plus_one_rows() {
            final List<Long> stored = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                stored.add(insert(row().storedSecondsAgo(OLD)));
            }

            final PullRows rows = queries.pull(pull(0, 2), LAG);

            assertThat(seqs(rows.rows())).containsExactlyElementsOf(stored.subList(0, 3));
        }

        @Test
        void pull_should_return_nothing_and_keep_the_cursor_when_no_share_is_older_than_the_lag() {
            insert(row().storedSecondsAgo(5));
            insert(row().storedSecondsAgo(1));

            final PullRows rows = queries.pull(pull(0, 10), LAG);

            assertThat(rows.rows()).isEmpty();
            assertThat(rows.boundSeq()).isNull();
            assertThat(rows.visibleUpTo()).isNotNull();
        }

        @Test
        void a_slow_lower_number_should_never_be_overtaken() throws SQLException {
            try (Connection slow = dataSource.getConnection()) {
                slow.setAutoCommit(false);
                final UUID slowShare = UUID.randomUUID();
                final long lower = openShare(slow, slowShare);
                final StoreRequest later = received(SampleShares.share(UUID.randomUUID(), "2026-10-02",
                        "2026-10-02T14:19:50.706Z"));
                store.store(later);
                final long higher = jdbc.sql("SELECT stored_seq FROM hearing_share WHERE share_id = :s")
                        .param("s", later.shareId()).query(Long.class).single();
                assertThat(higher).as("the later share took the higher number").isGreaterThan(lower);

                // Lag 0 shows the race the bound closes: the committed higher number is visible, the open one is not.
                assertThat(seqs(queries.pull(pull(0, 10), Duration.ZERO).rows()))
                        .as("lag 0").contains(higher).doesNotContain(lower);

                // With the lag above the open transaction's age the higher number is withheld.
                final PullRows withLag = queries.pull(pull(0, 10), Duration.ofSeconds(30));
                assertThat(seqs(withLag.rows())).as("lag 30 s").doesNotContain(higher, lower);
                assertThat(withLag.boundSeq()).as("bound while the lower number is open")
                        .satisfiesAnyOf(bound -> assertThat(bound).isNull(),
                                bound -> assertThat(bound).isLessThan(lower));

                slow.commit();
            }

            assertThat(seqs(queries.pull(pull(0, 10), Duration.ZERO).rows())).as("after the commit")
                    .hasSize(2).isSorted();
        }

        @Test
        void a_row_below_the_bound_should_be_returned_even_if_its_own_stored_at_is_after_the_cut_off() {
            final long descheduled = insert(row().storedSecondsAgo(50));
            final long bound = insert(row().storedSecondsAgo(70));

            final PullRows rows = queries.pull(pull(0, 10), LAG);

            assertThat(rows.boundSeq()).isEqualTo(bound);
            assertThat(seqs(rows.rows())).containsExactly(descheduled, bound);
        }

        @Test
        void the_bound_should_be_the_highest_number_older_than_the_lag_whatever_the_filter() {
            insert(row().storedSecondsAgo(OLD).court(COURT_A).dayYouthSeen(false));
            final long highestOld = insert(row().storedSecondsAgo(OLD).court(COURT_A).dayYouthSeen(false));
            insert(row().storedSecondsAgo(1).court(COURT_B).dayYouthSeen(true));

            final PullRows byCourt = queries.pull(new PullQuery(0, 10, DayYouthFilter.ANY, COURT_B), LAG);
            final PullRows byYouth = queries.pull(new PullQuery(0, 10, DayYouthFilter.TRUE, null), LAG);
            final PullRows byBoth = queries.pull(new PullQuery(0, 10, DayYouthFilter.NOT_FALSE, COURT_B), LAG);

            assertThat(List.of(byCourt, byYouth, byBoth)).allSatisfy(rows -> {
                assertThat(rows.rows()).isEmpty();
                assertThat(rows.boundSeq()).isEqualTo(highestOld);
            });
        }

        @Test
        void visible_up_to_should_be_the_database_clock_minus_the_lag() {
            final Instant before = databaseClock();
            final PullRows rows = queries.pull(pull(0, 10), LAG);
            final Instant after = databaseClock();

            assertThat(rows.visibleUpTo()).isBetween(before.minus(LAG), after.minus(LAG));
        }

        @Test
        void not_false_should_include_unknown_days_and_exclude_false_ones() {
            final long youth = insert(row().storedSecondsAgo(OLD).dayYouthSeen(true));
            final long unknown = insert(row().storedSecondsAgo(OLD).dayYouthSeen(null));
            insert(row().storedSecondsAgo(OLD).dayYouthSeen(false));

            assertThat(seqs(queries.pull(new PullQuery(0, 10, DayYouthFilter.NOT_FALSE, null), LAG).rows()))
                    .containsExactly(youth, unknown);
        }

        @Test
        void true_should_include_only_true_days() {
            final long youth = insert(row().storedSecondsAgo(OLD).dayYouthSeen(true));
            insert(row().storedSecondsAgo(OLD).dayYouthSeen(null));
            insert(row().storedSecondsAgo(OLD).dayYouthSeen(false));

            assertThat(seqs(queries.pull(new PullQuery(0, 10, DayYouthFilter.TRUE, null), LAG).rows()))
                    .containsExactly(youth);
        }

        @Test
        void no_filter_should_include_every_day() {
            final long youth = insert(row().storedSecondsAgo(OLD).dayYouthSeen(true));
            final long unknown = insert(row().storedSecondsAgo(OLD).dayYouthSeen(null));
            final long notYouth = insert(row().storedSecondsAgo(OLD).dayYouthSeen(false));
            final long failed = insert(row().storedSecondsAgo(OLD).failed());

            assertThat(seqs(queries.pull(pull(0, 10), LAG).rows())).containsExactly(youth, unknown, notYouth, failed);
        }

        @Test
        void court_filter_should_return_exact_matches_only() {
            final long inA = insert(row().storedSecondsAgo(OLD).court(COURT_A));
            insert(row().storedSecondsAgo(OLD).court(COURT_B));
            final long alsoInA = insert(row().storedSecondsAgo(OLD).court(COURT_A).dayYouthSeen(true));

            assertThat(seqs(queries.pull(new PullQuery(0, 10, DayYouthFilter.ANY, COURT_A), LAG).rows()))
                    .containsExactly(inA, alsoInA);
            assertThat(seqs(queries.pull(new PullQuery(0, 10, DayYouthFilter.TRUE, COURT_A), LAG).rows()))
                    .containsExactly(alsoInA);
        }

        @Test
        void court_filter_should_exclude_failed_rows() {
            insert(row().storedSecondsAgo(OLD).failed().dayYouthSeen(null));
            final long inA = insert(row().storedSecondsAgo(OLD).court(COURT_A).dayYouthSeen(null));

            assertThat(seqs(queries.pull(new PullQuery(0, 10, DayYouthFilter.ANY, COURT_A), LAG).rows()))
                    .containsExactly(inA);
            assertThat(seqs(queries.pull(new PullQuery(0, 10, DayYouthFilter.NOT_FALSE, COURT_A), LAG).rows()))
                    .containsExactly(inA);
        }

        @Test
        void a_failed_share_whose_court_the_sweep_fills_later_should_not_be_presented_to_a_court_pull_from_a_later_cursor() {
            final RowSpec failedRow = row().storedSecondsAgo(OLD).failed();
            insert(failedRow);
            final long inA = insert(row().storedSecondsAgo(OLD).court(COURT_A));
            final PullRows first = queries.pull(new PullQuery(0, 10, DayYouthFilter.ANY, COURT_A), LAG);
            assertThat(seqs(first.rows())).containsExactly(inA);

            jdbc.sql("""
                    UPDATE hearing_share SET court_centre_id = :court, lja_code = '2577', projection_status = 'OK',
                           projection_reason = NULL, projection_attempts = projection_attempts + 1
                     WHERE share_id = :s
                    """).param("court", COURT_A).param("s", failedRow.shareId).update();

            assertThat(queries.pull(new PullQuery(inA, 10, DayYouthFilter.ANY, COURT_A), LAG).rows())
                    .as("the filled share is behind the cursor").isEmpty();
        }

        @Test
        void key_details_should_be_null_for_a_failed_row() {
            final RowSpec failedRow = row().storedSecondsAgo(OLD).failed();
            insert(failedRow);
            final RowSpec okRow = row().storedSecondsAgo(OLD).court(COURT_A);
            insert(okRow);

            final ShareView failed = present(queries.share(failedRow.shareId));
            final ShareView ok = present(queries.share(okRow.shareId));

            assertThat(failed.projectionStatus()).isEqualTo(ProjectionStatus.FAILED);
            assertThat(failed.keyDetails()).isNull();
            assertThat(failed.anySubjectIsYouth()).isNull();
            assertThat(ok.projectionStatus()).isEqualTo(ProjectionStatus.OK);
            assertThat(ok.keyDetails()).isNotNull();
            assertThat(ok.keyDetails().courtCentreId()).isEqualTo(COURT_A);
            assertThat(ok.keyDetails().ljaCode()).isEqualTo("2577");
            assertThat(queries.pull(pull(0, 10), LAG).rows())
                    .extracting(view -> view.keyDetails() == null)
                    .containsExactly(true, false);
        }

        @Test
        void version_number_should_follow_shared_at_and_move_when_an_earlier_share_arrives_late() {
            final UUID hearing = UUID.randomUUID();
            final RowSpec first = row().hearing(hearing).sharedAt("2026-10-02T09:00:00Z").storedSecondsAgo(OLD);
            final RowSpec third = row().hearing(hearing).sharedAt("2026-10-02T15:00:00Z").storedSecondsAgo(OLD);
            insert(first);
            insert(third);
            assertThat(present(queries.share(third.shareId)).versionNumber()).isEqualTo(2);

            final RowSpec second = row().hearing(hearing).sharedAt("2026-10-02T12:00:00Z").storedSecondsAgo(OLD);
            insert(second);

            assertThat(present(queries.share(first.shareId)).versionNumber()).isEqualTo(1);
            assertThat(present(queries.share(second.shareId)).versionNumber()).isEqualTo(2);
            assertThat(present(queries.share(third.shareId)).versionNumber()).isEqualTo(3);
            assertThat(queries.pull(pull(0, 10), LAG).rows()).extracting(ShareView::versionNumber)
                    .containsExactly(1L, 3L, 2L);
        }

        @Test
        void every_column_should_reach_the_view() {
            final StoreRequest request = received(SampleShares.share(UUID.randomUUID(), "2026-10-02",
                    "2026-10-02T14:19:50.706123Z"));
            store.store(request);

            final ShareView view = present(queries.share(request.shareId()));

            assertThat(view.shareId()).isEqualTo(request.shareId());
            assertThat(view.hearingId()).isEqualTo(request.identity().hearingId());
            assertThat(view.hearingDay()).isEqualTo(LocalDate.parse("2026-10-02"));
            assertThat(view.sharedTime()).isEqualTo(Instant.parse("2026-10-02T14:19:50.706123Z"));
            assertThat(view.storedSeq()).isPositive();
            assertThat(view.storedAt()).isNotNull();
            assertThat(view.sharedDayLondon()).isEqualTo(LocalDate.parse("2026-10-02"));
            assertThat(view.sharedDayUtc()).isEqualTo(LocalDate.parse("2026-10-02"));
            assertThat(view.keyDetails().courtCentreId()).isEqualTo(SampleShares.COURT_CENTRE);
            assertThat(view.keyDetails().courtRoomId()).isEqualTo(SampleShares.COURT_ROOM);
            assertThat(view.keyDetails().ljaCode()).isEqualTo("2577");
            assertThat(view.keyDetails().jurisdictionType()).isEqualTo("MAGISTRATES");
            assertThat(view.keyDetails().sjp()).isNull();
            assertThat(view.keyDetails().groupProceedings()).isNull();
            assertThat(view.keyDetails().youthCourtId()).isNull();
            assertThat(view.keyDetails().reshare()).isFalse();
            assertThat(view.anySubjectIsYouth()).isFalse();
            assertThat(view.dayYouthSeen()).isFalse();
            assertThat(view.latest()).isTrue();
            assertThat(view.predecessorShareId()).isNull();
            assertThat(view.arrivedOutOfOrder()).isFalse();
            assertThat(view.enrichmentApplied()).isFalse();
            assertThat(view.projectionStatus()).isEqualTo(ProjectionStatus.OK);
            assertThat(view.projectionVersion()).isPositive();
            assertThat(view.projectedAt()).isNotNull();
            assertThat(view.versionNumber()).isOne();
        }
    }

    @Nested
    @DisplayName("search")
    class SearchQueries {

        @Test
        void search_day_form_should_filter_the_london_day_inclusively_including_a_00_30_bst_share() {
            final RowSpec halfPastMidnightBst = row().court(COURT_A).sharedAt("2026-10-02T23:30:00Z");
            final RowSpec lastMicrosecond = row().court(COURT_A).sharedAt("2026-10-03T22:59:59.999999Z");
            insert(halfPastMidnightBst);
            insert(lastMicrosecond);
            insert(row().court(COURT_A).sharedAt("2026-10-02T22:59:59.999999Z"));
            insert(row().court(COURT_A).sharedAt("2026-10-03T23:00:00Z"));
            insert(row().court(COURT_B).sharedAt("2026-10-03T12:00:00Z"));

            assertThat(ids(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.ANY))))
                    .containsExactly(halfPastMidnightBst.shareId, lastMicrosecond.shareId);
        }

        @ParameterizedTest(name = "{0} to {1}")
        @CsvSource({"2026-03-28, 2026-03-28", "2026-03-29, 2026-03-29", "2026-03-28, 2026-03-30",
            "2026-10-24, 2026-10-24", "2026-10-25, 2026-10-25", "2026-10-25, 2026-10-26"})
        void search_day_form_should_return_the_same_rows_as_shared_day_london_between(final LocalDate from,
                final LocalDate to) {
            for (final String at : List.of("2026-03-28T00:30:00Z", "2026-03-28T23:30:00Z", "2026-03-29T00:30:00Z",
                    "2026-03-29T00:59:59.999999Z", "2026-03-29T01:00:00Z", "2026-03-29T22:59:59.999999Z",
                    "2026-03-29T23:00:00Z", "2026-03-30T12:00:00Z", "2026-10-24T22:59:59.999999Z",
                    "2026-10-24T23:00:00Z", "2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z",
                    "2026-10-25T23:59:59.999999Z", "2026-10-26T00:00:00Z", "2026-10-26T23:59:59.999999Z")) {
                insert(row().court(COURT_A).sharedAt(at));
            }
            final List<UUID> byLondonDay = jdbc.sql("""
                    SELECT share_id FROM hearing_share
                     WHERE court_centre_id = :court AND shared_day_london BETWEEN :from AND :to
                     ORDER BY shared_at, share_id
                    """).param("court", COURT_A).param("from", from).param("to", to).query(UUID.class).list();

            assertThat(byLondonDay).isNotEmpty();
            assertThat(ids(queries.search(dayForm(COURT_A, from.toString(), to.toString(), DayYouthFilter.ANY))))
                    .containsExactlyElementsOf(byLondonDay);
        }

        @Test
        void search_time_form_should_be_half_open_on_shared_at() {
            final RowSpec atFrom = row().court(COURT_A).sharedAt("2026-10-03T09:00:00Z");
            final RowSpec justBeforeTo = row().court(COURT_A).sharedAt("2026-10-03T16:59:59.999999Z");
            insert(atFrom);
            insert(justBeforeTo);
            insert(row().court(COURT_A).sharedAt("2026-10-03T17:00:00Z"));
            insert(row().court(COURT_A).sharedAt("2026-10-03T08:59:59.999999Z"));

            assertThat(ids(queries.search(new SearchQuery(COURT_A, Instant.parse("2026-10-03T09:00:00Z"),
                    Instant.parse("2026-10-03T17:00:00Z"), DayYouthFilter.ANY, false, null, 100))))
                    .containsExactly(atFrom.shareId, justBeforeTo.shareId);
        }

        @Test
        void search_should_order_by_shared_at_then_share_id() {
            final List<RowSpec> sameInstant = List.of(row().court(COURT_A).sharedAt("2026-10-03T10:00:00Z"),
                    row().court(COURT_A).sharedAt("2026-10-03T10:00:00Z"),
                    row().court(COURT_A).sharedAt("2026-10-03T10:00:00Z"));
            final RowSpec earlier = row().court(COURT_A).sharedAt("2026-10-03T09:00:00Z");
            sameInstant.forEach(JdbcShareQueriesIT.this::insert);
            insert(earlier);
            final List<UUID> expected = new ArrayList<>();
            expected.add(earlier.shareId);
            // PostgreSQL orders uuid by its bytes, unsigned: the order of the lower-case text, not UUID.compareTo.
            sameInstant.stream().map(spec -> spec.shareId).sorted(Comparator.comparing(UUID::toString))
                    .forEach(expected::add);

            assertThat(ids(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.ANY))))
                    .containsExactlyElementsOf(expected);
        }

        @Test
        void search_keyset_pages_should_neither_repeat_nor_miss_rows() {
            for (final String day : List.of("2026-10-01", "2026-10-02", "2026-10-03")) {
                for (final String time : List.of("08:00:00Z", "12:00:00Z", "12:00:00Z", "23:30:00Z")) {
                    insert(row().court(COURT_A).sharedAt(day + "T" + time));
                    insert(row().court(COURT_B).sharedAt(day + "T" + time));
                }
            }
            final SearchQuery dayForm = dayForm(COURT_A, "2026-10-01", "2026-10-03", DayYouthFilter.ANY);
            final SearchQuery timeForm = new SearchQuery(COURT_A, Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-04T00:00:00Z"), DayYouthFilter.ANY, false, null, 100);

            for (final SearchQuery query : List.of(dayForm, timeForm)) {
                final List<UUID> all = ids(queries.search(query));
                assertThat(all).hasSizeGreaterThan(5).doesNotHaveDuplicates();
                assertThat(pageByOne(query)).containsExactlyElementsOf(all);
            }
        }

        @Test
        void search_latest_only_should_return_only_is_latest() {
            final RowSpec latest = row().court(COURT_A).sharedAt("2026-10-03T12:00:00Z").latest();
            insert(row().court(COURT_A).sharedAt("2026-10-03T10:00:00Z"));
            insert(latest);

            final SearchQuery any = dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.ANY);
            assertThat(ids(queries.search(new SearchQuery(any.courtCentreId(), any.sharedFrom(), any.sharedTo(),
                    DayYouthFilter.ANY, true, null, 100)))).containsExactly(latest.shareId);
            assertThat(queries.search(any)).hasSize(2);
        }

        @Test
        void search_day_youth_seen_variants_should_include_false() {
            final RowSpec youth = row().court(COURT_A).sharedAt("2026-10-03T09:00:00Z").dayYouthSeen(true);
            final RowSpec unknown = row().court(COURT_A).sharedAt("2026-10-03T10:00:00Z").dayYouthSeen(null);
            final RowSpec notYouth = row().court(COURT_A).sharedAt("2026-10-03T11:00:00Z").dayYouthSeen(false);
            List.of(youth, unknown, notYouth).forEach(JdbcShareQueriesIT.this::insert);

            assertThat(ids(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.ANY))))
                    .containsExactly(youth.shareId, unknown.shareId, notYouth.shareId);
            assertThat(ids(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.NOT_FALSE))))
                    .containsExactly(youth.shareId, unknown.shareId);
            assertThat(ids(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.TRUE))))
                    .containsExactly(youth.shareId);
            assertThat(ids(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.FALSE))))
                    .containsExactly(notYouth.shareId);
        }

        @Test
        void search_should_never_return_a_failed_row() {
            final RowSpec ok = row().court(COURT_A).sharedAt("2026-10-03T09:00:00Z");
            insert(ok);
            insert(row().sharedAt("2026-10-03T10:00:00Z").failed());

            assertThat(queries.search(dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.ANY)))
                    .extracting(ShareView::shareId, ShareView::projectionStatus)
                    .containsExactly(tuple(ok.shareId, ProjectionStatus.OK));
        }

        @Test
        void search_should_read_at_most_limit_plus_one_rows() {
            for (int hour = 1; hour <= 5; hour++) {
                insert(row().court(COURT_A).sharedAt("2026-10-03T0" + hour + ":00:00Z"));
            }
            final SearchQuery all = dayForm(COURT_A, "2026-10-03", "2026-10-03", DayYouthFilter.ANY);

            assertThat(queries.search(new SearchQuery(all.courtCentreId(), all.sharedFrom(), all.sharedTo(),
                    DayYouthFilter.ANY, false, null, 2))).hasSize(3);
        }

        private List<UUID> pageByOne(final SearchQuery query) {
            final List<UUID> seen = new ArrayList<>();
            SearchCursor cursor = null;
            boolean more = true;
            while (more) {
                final List<ShareView> rows = queries.search(new SearchQuery(query.courtCentreId(), query.sharedFrom(),
                        query.sharedTo(), query.dayYouthSeen(), query.latestOnly(), cursor, 1));
                assertThat(rows).as("limit + 1 rows at most").hasSizeLessThanOrEqualTo(2);
                if (rows.isEmpty()) {
                    more = false;
                } else {
                    final ShareView item = rows.getFirst();
                    seen.add(item.shareId());
                    cursor = SearchCursor.after(item.sharedTime(), item.shareId());
                    more = rows.size() > 1;
                }
            }
            return seen;
        }
    }

    @Nested
    @DisplayName("one share, the day's versions and the payload")
    class OneShare {

        @Test
        void day_versions_should_be_in_shared_at_order_with_row_numbers() {
            final UUID hearing = UUID.randomUUID();
            final RowSpec third = row().hearing(hearing).sharedAt("2026-10-02T15:00:00Z").latest();
            final RowSpec first = row().hearing(hearing).sharedAt("2026-10-02T09:00:00Z");
            final RowSpec second = row().hearing(hearing).sharedAt("2026-10-02T12:00:00Z");
            List.of(third, first, second).forEach(JdbcShareQueriesIT.this::insert);
            insert(row().sharedAt("2026-10-02T10:00:00Z"));

            assertThat(queries.dayVersions(hearing, LocalDate.parse("2026-10-02")))
                    .extracting(ShareView::shareId, ShareView::versionNumber)
                    .containsExactly(tuple(first.shareId, 1L),
                            tuple(second.shareId, 2L),
                            tuple(third.shareId, 3L));
        }

        @Test
        void payload_should_return_the_working_copy_without_metadata_as_the_database_writes_it() {
            final String text = SampleShares.share(UUID.randomUUID(), "2026-10-02", "2026-10-02T14:19:50.706Z");
            final StoreRequest request = received(text);
            store.store(request);
            final String databaseText = jdbc.sql(
                    "SELECT (payload_json - '_metadata')::text FROM hearing_share_payload WHERE share_id = :s")
                    .param("s", request.shareId()).query(String.class).single();

            final StoredPayload payload = present(queries.payload(request.shareId()));

            assertThat(payload.form()).isEqualTo(PayloadForm.WORKING_COPY);
            assertThat(PayloadChecksum.sha256Hex(payload.body())).as("body hash")
                    .isEqualTo(PayloadChecksum.sha256Hex(databaseText));
            assertThat(JSON.readTree(payload.body()).has("_metadata")).as("has _metadata").isFalse();
            assertThat(JSON.readTree(payload.body()).has("hearing")).as("has hearing").isTrue();
            assertThat(payload.shareId()).isEqualTo(request.shareId());
            assertThat(payload.hearingId()).isEqualTo(request.identity().hearingId());
            assertThat(payload.hearingDay()).isEqualTo(LocalDate.parse("2026-10-02"));
            assertThat(payload.sharedTime()).isEqualTo(Instant.parse("2026-10-02T14:19:50.706Z"));
            assertThat(payload.enrichmentApplied()).isFalse();
        }

        @Test
        void payload_should_return_payload_text_and_the_arrived_form_when_the_working_copy_is_null() {
            final String text = SampleShares.share(UUID.randomUUID(), "2026-10-02", "2026-10-02T14:19:50.706Z",
                    "false", "a\\u0000b");
            final StoreRequest request = received(text);
            store.store(request);

            final StoredPayload payload = present(queries.payload(request.shareId()));

            assertThat(payload.form()).isEqualTo(PayloadForm.ARRIVED_TEXT);
            assertThat(PayloadChecksum.sha256Hex(payload.body())).as("body hash")
                    .isEqualTo(PayloadChecksum.sha256Hex(text));
        }

        @Test
        void an_unknown_share_payload_or_day_should_be_empty() {
            insert(row().sharedAt("2026-10-02T10:00:00Z"));

            assertThat(queries.share(UUID.randomUUID())).isEmpty();
            assertThat(queries.payload(UUID.randomUUID())).isEmpty();
            assertThat(queries.dayVersions(UUID.randomUUID(), LocalDate.parse("2026-10-02"))).isEmpty();
        }

        @Test
        void the_read_query_timeout_should_cancel_a_query_held_by_a_lock() throws SQLException {
            final JdbcTemplate bounded = new JdbcTemplate(dataSource);
            bounded.setQueryTimeout(1);
            final JdbcShareQueries boundedQueries = new JdbcShareQueries(bounded);
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try (Statement lock = holder.createStatement()) {
                    lock.execute("LOCK TABLE hearing_share IN ACCESS EXCLUSIVE MODE");
                }
                final long start = System.nanoTime();

                assertThatThrownBy(() -> boundedQueries.share(UUID.randomUUID()))
                        .isInstanceOf(QueryTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
                holder.rollback();
            }
        }
    }

    /** The value, asserted present, so an absent one fails as an assertion. */
    private static <T> T present(final Optional<T> value) {
        assertThat(value).as("present").isPresent();
        return value.get();
    }

    private SearchQuery dayForm(final UUID court, final String from, final String to, final DayYouthFilter filter) {
        final SharedDays.InstantRange range = SharedDays.londonDays(LocalDate.parse(from), LocalDate.parse(to));
        return new SearchQuery(court, range.from(), range.to(), filter, false, null, 100);
    }

    private static PullQuery pull(final long storedAfterSeq, final int limit) {
        return new PullQuery(storedAfterSeq, limit, DayYouthFilter.ANY, null);
    }

    private static List<Long> seqs(final List<ShareView> rows) {
        return rows.stream().map(ShareView::storedSeq).toList();
    }

    private static List<UUID> ids(final List<ShareView> rows) {
        return rows.stream().map(ShareView::shareId).toList();
    }

    private Instant databaseClock() {
        return jdbc.sql("SELECT clock_timestamp()")
                .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    private StoreRequest received(final String text) {
        final String messageId = "ID:" + UUID.randomUUID();
        receipts.recordArrival(SampleShares.arrival(messageId, text));
        return SampleShares.request(messageId, text);
    }

    /**
     * Inserts the first share of a new day on the connection without committing: its number is taken and its
     * transaction stays open.
     */
    private static long openShare(final Connection connection, final UUID shareId) throws SQLException {
        final UUID hearing = UUID.randomUUID();
        try (PreparedStatement day = connection.prepareStatement(
                "INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (?, DATE '2026-10-02')")) {
            day.setObject(1, hearing);
            day.executeUpdate();
        }
        final long seq;
        try (PreparedStatement share = connection.prepareStatement("""
                INSERT INTO hearing_share (share_id, hearing_id, hearing_day, shared_at, shared_day_london,
                    shared_day_utc, payload_sha256, is_latest, arrived_out_of_order, projection_status,
                    projection_version)
                VALUES (?, ?, DATE '2026-10-02', TIMESTAMPTZ '2026-10-02T10:00:00Z', DATE '2026-10-02',
                    DATE '2026-10-02', repeat('a', 64), TRUE, FALSE, 'OK', 1)
                RETURNING stored_seq
                """)) {
            share.setObject(1, shareId);
            share.setObject(2, hearing);
            try (ResultSet returned = share.executeQuery()) {
                if (!returned.next()) {
                    throw new IllegalStateException("the insert returned no stored_seq");
                }
                seq = returned.getLong(1);
            }
        }
        try (PreparedStatement latest = connection.prepareStatement(
                "UPDATE hearing_day_head SET latest_share_id = ?, share_count = 1 WHERE hearing_id = ?")) {
            latest.setObject(1, shareId);
            latest.setObject(2, hearing);
            latest.executeUpdate();
        }
        return seq;
    }

    private static RowSpec row() {
        return new RowSpec();
    }

    /**
     * Inserts the row with its {@code stored_at} chosen, skipping the triggers and foreign keys for this insert.
     *
     * @return its {@code stored_seq}
     */
    private long insert(final RowSpec spec) {
        final SharedDays days = SharedDays.from(spec.sharedInstant);
        final boolean failed = spec.isFailed;
        try (Connection connection = dataSource.getConnection()) {
            try (Statement replica = connection.createStatement()) {
                replica.execute("SET session_replication_role = replica");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO hearing_share (share_id, hearing_id, hearing_day, shared_at, shared_day_london,
                        shared_day_utc, stored_at, payload_sha256, court_centre_id, lja_code, any_subject_is_youth,
                        day_youth_seen, is_latest, arrived_out_of_order, projection_status, projection_reason,
                        projection_version)
                    VALUES (?, ?, ?, ?, ?, ?, now() - make_interval(secs => ?), repeat('a', 64), ?, ?, ?, ?, ?,
                        FALSE, ?, ?, 1)
                    RETURNING stored_seq
                    """)) {
                insert.setObject(1, spec.shareId);
                insert.setObject(2, spec.hearingId);
                insert.setObject(3, spec.hearingDay);
                insert.setObject(4, OffsetDateTime.ofInstant(spec.sharedInstant, ZoneOffset.UTC));
                insert.setObject(5, days.london());
                insert.setObject(6, days.utc());
                insert.setDouble(7, spec.storedAgo);
                insert.setObject(8, failed ? null : spec.courtCentreId);
                insert.setObject(9, failed ? null : "2577");
                insert.setObject(10, failed ? null : Boolean.FALSE);
                insert.setObject(11, spec.dayFlag);
                insert.setBoolean(12, spec.isLatest);
                insert.setString(13, failed ? "FAILED" : "OK");
                insert.setString(14, failed ? "UNEXPECTED_TEST" : null);
                try (ResultSet returned = insert.executeQuery()) {
                    if (!returned.next()) {
                        throw new IllegalStateException("the insert returned no stored_seq");
                    }
                    return returned.getLong(1);
                }
            } finally {
                try (Statement origin = connection.createStatement()) {
                    origin.execute("RESET session_replication_role");
                }
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException("test row insert failed", failure);
        }
    }

    /** A share row to insert directly. Defaults: an {@code OK} share of a new hearing, stored just now. */
    private static final class RowSpec {

        private final UUID shareId = UUID.randomUUID();

        private UUID hearingId = UUID.randomUUID();

        private LocalDate hearingDay = LocalDate.parse("2026-10-02");

        private Instant sharedInstant = Instant.parse("2026-10-02T10:00:00Z");

        private double storedAgo;

        private UUID courtCentreId = COURT_A;

        private Boolean dayFlag = Boolean.FALSE;

        private boolean isLatest;

        private boolean isFailed;

        RowSpec hearing(final UUID hearing) {
            this.hearingId = hearing;
            return this;
        }

        RowSpec sharedAt(final String instant) {
            this.sharedInstant = Instant.parse(instant);
            this.hearingDay = SharedDays.from(sharedInstant).london();
            return this;
        }

        RowSpec storedSecondsAgo(final double seconds) {
            this.storedAgo = seconds;
            return this;
        }

        RowSpec court(final UUID courtCentre) {
            this.courtCentreId = courtCentre;
            return this;
        }

        RowSpec dayYouthSeen(final Boolean seen) {
            this.dayFlag = seen;
            return this;
        }

        RowSpec latest() {
            this.isLatest = true;
            return this;
        }

        RowSpec failed() {
            this.isFailed = true;
            return this;
        }
    }
}
