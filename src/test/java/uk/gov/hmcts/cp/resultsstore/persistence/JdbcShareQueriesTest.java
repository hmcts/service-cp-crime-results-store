package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;

/** The read SQL constants (Principle III; data-model.md invariant 4). */
@DisplayName("read query constants")
class JdbcShareQueriesTest {

    private static final String PAYLOAD_TABLE = "hearing_share_payload";

    @Test
    void no_pull_search_share_or_day_constant_should_name_hearing_share_payload() {
        assertThat(everyNonPayloadConstant()).isNotEmpty()
                .allSatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).doesNotContain(PAYLOAD_TABLE));
        assertThat(JdbcShareQueries.PAYLOAD_SQL).contains(PAYLOAD_TABLE).contains("payload_json - '_metadata'");
    }

    @Test
    void the_variant_table_should_cover_every_day_youth_filter_and_court_combination() {
        final List<String> pulls = new ArrayList<>();
        for (final DayYouthFilter filter : DayYouthFilter.values()) {
            if (filter.allowedOnPull()) {
                pulls.add(JdbcShareQueries.pullSql(filter, false));
                pulls.add(JdbcShareQueries.pullSql(filter, true));
            }
        }
        final List<String> searches = new ArrayList<>();
        for (final DayYouthFilter filter : DayYouthFilter.values()) {
            for (final boolean latestOnly : List.of(false, true)) {
                searches.add(JdbcShareQueries.searchSql(filter, latestOnly, false));
                searches.add(JdbcShareQueries.searchSql(filter, latestOnly, true));
            }
        }

        assertThat(pulls).hasSize(6).doesNotHaveDuplicates();
        assertThat(searches).hasSize(16).doesNotHaveDuplicates();
        assertThat(JdbcShareQueries.pullSql(DayYouthFilter.ANY, false)).doesNotContain("day_youth_seen IS")
                .doesNotContain("court_centre_id = :courtCentreId");
        assertThat(JdbcShareQueries.pullSql(DayYouthFilter.NOT_FALSE, false))
                .contains("AND s.day_youth_seen IS NOT FALSE\n").doesNotContain("AND s.day_youth_seen IS NOT FALSE AND");
        assertThat(JdbcShareQueries.pullSql(DayYouthFilter.TRUE, true))
                .contains("AND s.day_youth_seen IS NOT FALSE AND s.day_youth_seen\n")
                .contains("AND s.court_centre_id = :courtCentreId");
        assertThat(JdbcShareQueries.searchSql(DayYouthFilter.FALSE, true, true))
                .contains("AND s.day_youth_seen IS FALSE").contains("AND s.is_latest")
                .contains("AND (s.shared_at, s.share_id) > (:cursorAt, :cursorId)");
        assertThat(JdbcShareQueries.searchSql(DayYouthFilter.ANY, false, false)).doesNotContain("day_youth_seen IS")
                .doesNotContain("is_latest\n").doesNotContain(":cursorAt");
    }

    @ParameterizedTest
    @EnumSource(DayYouthFilter.class)
    void every_search_should_require_the_court_and_the_half_open_range(final DayYouthFilter filter) {
        assertThat(JdbcShareQueries.searchSql(filter, false, false))
                .contains("WHERE s.court_centre_id = :courtCentreId")
                .contains("AND s.shared_at >= :sharedFrom")
                .contains("AND s.shared_at < :sharedTo")
                .contains("ORDER BY s.shared_at, s.share_id");
    }

    @Test
    void false_should_have_no_pull_variant() {
        assertThatThrownBy(() -> JdbcShareQueries.pullSql(DayYouthFilter.FALSE, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dayYouthSeen=false is not a pull filter");
    }

    @Test
    void every_pull_should_be_bounded_by_the_visibility_bound_in_the_same_statement() {
        for (final DayYouthFilter filter : List.of(DayYouthFilter.ANY, DayYouthFilter.NOT_FALSE, DayYouthFilter.TRUE)) {
            assertThat(JdbcShareQueries.pullSql(filter, false))
                    .contains("WITH bound AS")
                    .contains("AND s.stored_seq <= bound.max_seq")
                    .contains("ORDER BY s.stored_seq")
                    .contains("LIMIT :rowLimit");
        }
    }

    private static List<String> everyNonPayloadConstant() {
        final List<String> constants = new ArrayList<>();
        for (final DayYouthFilter filter : DayYouthFilter.values()) {
            if (filter.allowedOnPull()) {
                constants.add(JdbcShareQueries.pullSql(filter, false));
                constants.add(JdbcShareQueries.pullSql(filter, true));
            }
            for (final boolean latestOnly : List.of(false, true)) {
                constants.add(JdbcShareQueries.searchSql(filter, latestOnly, false));
                constants.add(JdbcShareQueries.searchSql(filter, latestOnly, true));
            }
        }
        constants.add(JdbcShareQueries.SHARE_SQL);
        constants.add(JdbcShareQueries.DAY_VERSIONS_SQL);
        return constants;
    }
}
