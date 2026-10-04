package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * Ties each read query to its index (research R15; contracts/schema.md rule 4): {@code EXPLAIN (FORMAT JSON)}
 * of the {@link JdbcShareQueries} constants themselves, with bound values, and {@code enable_seqscan} and
 * {@code enable_bitmapscan} off.
 * {@code SET LOCAL} does nothing outside a transaction, so each plan is read inside the test's own one.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("read query plans")
class ReadQueriesPlanIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String YOUTH_FEED = "hearing_share_youth_feed_ix";

    private static final String CENTRE_FEED = "hearing_share_centre_feed_ix";

    private static final String CENTRE_SHARED_AT = "hearing_share_centre_shared_at_ix";

    private static final String STORED_SEQ = "hearing_share_stored_seq_uk";

    private static final String IDENTITY = "hearing_share_identity_uk";

    private static final String DAY_SHARE = "hearing_share_day_share_uk";

    private static final UUID COURT = UUID.fromString("cccccccc-0000-4000-8000-000000000007");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    /**
     * Enough rows for the planner's statistics: 2,500 hearing days of two shares each (the later one latest), over
     * fifty courts, with every youth flag and both statuses.
     */
    @BeforeAll
    void rows() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute("""
                    INSERT INTO hearing_share (share_id, hearing_id, hearing_day, shared_at, shared_day_london,
                        shared_day_utc, payload_sha256, court_centre_id, day_youth_seen, is_latest,
                        arrived_out_of_order, projection_status, projection_reason, projection_version)
                    SELECT gen_random_uuid(), md5('plan-hearing-' || (i % 2500))::uuid,
                           DATE '2026-09-01' + (i % 2500) % 30,
                           TIMESTAMPTZ '2026-09-01T09:00:00Z' + make_interval(mins => i * 7),
                           DATE '2026-09-01' + (i % 2500) % 30, DATE '2026-09-01' + (i % 2500) % 30, repeat('b', 64),
                           CASE WHEN i % 40 = 0 THEN NULL
                                ELSE ('cccccccc-0000-4000-8000-' || lpad((i % 50)::text, 12, '0'))::uuid END,
                           CASE i % 3 WHEN 0 THEN TRUE WHEN 1 THEN FALSE END, i > 2500, FALSE,
                           CASE WHEN i % 40 = 0 THEN 'FAILED' ELSE 'OK' END,
                           CASE WHEN i % 40 = 0 THEN 'PLAN_TEST' END, 1
                      FROM generate_series(1, 5000) AS i
                    """);
            statement.execute("RESET session_replication_role");
            // VACUUM sets the visibility map, as autovacuum does on a live table, so an index-only scan is costed as one.
            statement.execute("VACUUM ANALYZE hearing_share");
        }
    }

    /** The rows are the planner's only; no other suite should find them, the sweep included. */
    @AfterAll
    void removeRows() {
        jdbc.sql("TRUNCATE event_receipt, share_defendant, hearing_share_payload, hearing_share, hearing_day_head")
                .update();
    }

    @ParameterizedTest
    @EnumSource(value = DayYouthFilter.class, names = {"NOT_FALSE", "TRUE"})
    void pull_not_false_and_true_should_use_hearing_share_youth_feed_ix(final DayYouthFilter filter) {
        final QueryPlan plan = explain(JdbcShareQueries.pullSql(filter, false));

        assertThat(plan.indexesOf("s")).containsExactly(YOUTH_FEED);
        assertThat(plan.nodeTypes()).doesNotContain("Sort", "Incremental Sort");
    }

    @Test
    void unfiltered_pull_should_use_hearing_share_stored_seq_uk() {
        final QueryPlan plan = explain(JdbcShareQueries.pullSql(DayYouthFilter.ANY, false));

        assertThat(plan.indexesOf("s")).containsExactly(STORED_SEQ);
        assertThat(plan.nodeTypes()).doesNotContain("Sort", "Incremental Sort");
    }

    @ParameterizedTest
    @EnumSource(value = DayYouthFilter.class, names = {"ANY", "NOT_FALSE", "TRUE"})
    void court_pull_should_use_hearing_share_centre_feed_ix_with_no_sort_node(final DayYouthFilter filter) {
        final QueryPlan plan = explain(JdbcShareQueries.pullSql(filter, true));

        assertThat(plan.indexesOf("s")).containsExactly(CENTRE_FEED);
        assertThat(plan.nodeTypes()).doesNotContain("Sort", "Incremental Sort");
    }

    @ParameterizedTest
    @EnumSource(value = DayYouthFilter.class, names = {"ANY", "NOT_FALSE", "TRUE"})
    void the_bound_should_scan_hearing_share_stored_seq_uk_backward(final DayYouthFilter filter) {
        for (final boolean byCourt : List.of(false, true)) {
            final QueryPlan plan = explain(JdbcShareQueries.pullSql(filter, byCourt));

            assertThat(plan.indexesOf("b")).containsExactly(STORED_SEQ);
            assertThat(plan.scanDirectionsOf("b")).containsExactly("Backward");
        }
    }

    @ParameterizedTest
    @EnumSource(DayYouthFilter.class)
    void search_day_form_and_time_form_should_both_use_hearing_share_centre_shared_at_ix_with_no_sort_node(
            final DayYouthFilter filter) {
        for (final boolean latestOnly : List.of(false, true)) {
            for (final boolean afterCursor : List.of(false, true)) {
                final QueryPlan plan = explain(JdbcShareQueries.searchSql(filter, latestOnly, afterCursor));

                assertThat(plan.indexesOf("s")).as("latestOnly %s, cursor %s", latestOnly, afterCursor)
                        .containsExactly(CENTRE_SHARED_AT);
                assertThat(plan.nodeTypes()).doesNotContain("Sort", "Incremental Sort");
            }
        }
    }

    @Test
    void day_versions_and_version_number_should_use_hearing_share_identity_uk() {
        assertThat(explain(JdbcShareQueries.DAY_VERSIONS_SQL).indexesOf("s")).as("day versions")
                .containsExactly(IDENTITY);
        // The count reads a handful of rows of one day. Both unique indexes that lead with (hearing_id,
        // hearing_day) serve it at the same cost, and the planner's tie-break between them moves with the
        // table's state, so either is accepted; neither is a scan of the table.
        assertThat(explain(JdbcShareQueries.SHARE_SQL).indexesOf("v")).as("share")
                .singleElement().isIn(IDENTITY, DAY_SHARE);
        assertThat(explain(JdbcShareQueries.pullSql(DayYouthFilter.ANY, true)).indexesOf("v")).as("pull")
                .singleElement().isIn(IDENTITY, DAY_SHARE);
        assertThat(explain(JdbcShareQueries.searchSql(DayYouthFilter.ANY, false, false)).indexesOf("v"))
                .as("search").singleElement().isIn(IDENTITY, DAY_SHARE);
    }

    @Test
    void one_share_should_use_hearing_share_pk() {
        assertThat(explain(JdbcShareQueries.SHARE_SQL).indexesOf("s")).containsExactly("hearing_share_pk");
    }

    @Test
    void no_pull_search_share_or_day_plan_should_touch_hearing_share_payload() {
        final List<String> constants = new ArrayList<>();
        for (final DayYouthFilter filter : DayYouthFilter.values()) {
            if (filter.allowedOnPull()) {
                constants.add(JdbcShareQueries.pullSql(filter, false));
                constants.add(JdbcShareQueries.pullSql(filter, true));
            }
            constants.add(JdbcShareQueries.searchSql(filter, true, true));
        }
        constants.add(JdbcShareQueries.SHARE_SQL);
        constants.add(JdbcShareQueries.DAY_VERSIONS_SQL);

        assertThat(constants).allSatisfy(sql -> assertThat(explain(sql).relations())
                .contains("hearing_share")
                .doesNotContain("hearing_share_payload"));
        assertThat(explain(JdbcShareQueries.PAYLOAD_SQL).relations()).contains("hearing_share_payload");
    }

    private QueryPlan explain(final String sql) {
        final String text = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            // At test volume a bitmap scan and a sort is cheaper than any ordered index scan; off, the plan shows
            // whether the index alone serves the query's order (no Sort node), as it must at production volume.
            jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
            return jdbc.sql("EXPLAIN (FORMAT JSON) " + sql).params(values()).query(String.class).single();
        });
        return new QueryPlan(JSON.readTree(text).get(0).get("Plan"));
    }

    /** A value for every parameter any read query names; each query takes the ones it uses. */
    private static Map<String, Object> values() {
        final Map<String, Object> values = new HashMap<>();
        values.put("storedAfterSeq", 1000L);
        values.put("lagSeconds", 90.0);
        values.put("rowLimit", 101);
        values.put("courtCentreId", COURT);
        values.put("sharedFrom", OffsetDateTime.parse("2026-09-10T23:00:00Z"));
        values.put("sharedTo", OffsetDateTime.parse("2026-09-11T23:00:00Z"));
        values.put("cursorAt", OffsetDateTime.parse("2026-09-11T09:00:00Z"));
        values.put("cursorId", UUID.fromString("00000000-0000-4000-8000-000000000001"));
        values.put("shareId", UUID.randomUUID());
        values.put("hearingId", UUID.randomUUID());
        values.put("hearingDay", LocalDate.parse("2026-09-11"));
        return values;
    }

    /** A plan's nodes, flattened. */
    private static final class QueryPlan {

        private final List<JsonNode> nodes = new ArrayList<>();

        QueryPlan(final JsonNode root) {
            collect(root);
        }

        private void collect(final JsonNode node) {
            nodes.add(node);
            final JsonNode children = node.get("Plans");
            if (children != null) {
                children.forEach(this::collect);
            }
        }

        List<String> nodeTypes() {
            return nodes.stream().map(node -> node.get("Node Type").asString()).toList();
        }

        List<String> relations() {
            return nodes.stream().filter(node -> node.has("Relation Name"))
                    .map(node -> node.get("Relation Name").asString()).toList();
        }

        /** The indexes the scans of the alias use: index scans directly, bitmap heap scans through their children. */
        List<String> indexesOf(final String alias) {
            final List<String> indexes = new ArrayList<>();
            for (final JsonNode node : nodes) {
                if (alias.equals(text(node, "Alias")) && node.has("Index Name")) {
                    indexes.add(node.get("Index Name").asString());
                }
                if (alias.equals(text(node, "Alias")) && "Bitmap Heap Scan".equals(text(node, "Node Type"))) {
                    node.get("Plans").forEach(child -> indexes.add(child.get("Index Name").asString()));
                }
                if (alias.equals(text(node, "Alias")) && "Seq Scan".equals(text(node, "Node Type"))) {
                    indexes.add("Seq Scan");
                }
            }
            return indexes;
        }

        List<String> scanDirectionsOf(final String alias) {
            return nodes.stream().filter(node -> alias.equals(text(node, "Alias")) && node.has("Scan Direction"))
                    .map(node -> node.get("Scan Direction").asString()).toList();
        }

        private static String text(final JsonNode node, final String field) {
            return node.has(field) ? node.get(field).asString() : null;
        }
    }
}
