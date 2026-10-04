package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("intake failure causes")
class IntakeFailureCauseTest {

    @ParameterizedTest
    @CsvSource({
        "55P03, LOCK_TIMEOUT",
        "57014, STATEMENT_TIMEOUT",
        "23505, DATABASE",
        "08006, DATABASE",
        "40P01, DATABASE"
    })
    void from_sql_state_should_name_the_cause(final String sqlState, final IntakeFailureCause cause) {
        assertThat(IntakeFailureCause.fromSqlState(sqlState)).isEqualTo(cause);
    }

    @Test
    void from_sql_state_with_none_should_be_other() {
        assertThat(IntakeFailureCause.fromSqlState(null)).isEqualTo(IntakeFailureCause.OTHER);
    }

    @ParameterizedTest
    @CsvSource({
        "LOCK_TIMEOUT, lock_timeout",
        "STATEMENT_TIMEOUT, statement_timeout",
        "DATABASE, database",
        "OTHER, other",
        "PROGRESSION_REJECTED, progression_rejected",
        "PROGRESSION_REFUSED, progression_refused",
        "PROGRESSION_UNAVAILABLE, progression_unavailable",
        "PROGRESSION_UNREACHABLE, progression_unreachable",
        "PROGRESSION_TIMEOUT, progression_timeout",
        "PROGRESSION_MALFORMED, progression_malformed"
    })
    void cause_should_have_its_lower_case_tag(final IntakeFailureCause cause, final String tag) {
        assertThat(cause.tag()).isEqualTo(tag);
    }

    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(IntakeFailureCause.values()).map(IntakeFailureCause::tag))
                .containsExactly("lock_timeout", "statement_timeout", "database", "other",
                        "progression_rejected", "progression_refused", "progression_unavailable",
                        "progression_unreachable", "progression_timeout", "progression_malformed");
    }

    /** Every SQLSTATE row above, plus a failure that carried none. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"55P03", "57014", "23505", "08006", "40P01"})
    void from_sql_state_should_never_name_a_progression_cause(final String sqlState) {
        final Set<IntakeFailureCause> progression = EnumSet.of(IntakeFailureCause.PROGRESSION_REJECTED,
                IntakeFailureCause.PROGRESSION_REFUSED, IntakeFailureCause.PROGRESSION_UNAVAILABLE,
                IntakeFailureCause.PROGRESSION_UNREACHABLE, IntakeFailureCause.PROGRESSION_TIMEOUT,
                IntakeFailureCause.PROGRESSION_MALFORMED);
        assertThat(IntakeFailureCause.fromSqlState(sqlState)).isNotIn(progression);
    }

    /** contracts/metrics.md: the progression causes only with {@code enrich}; the database ones never with it. */
    @ParameterizedTest
    @CsvSource({
        "LOCK_TIMEOUT, true, true, false",
        "STATEMENT_TIMEOUT, true, true, false",
        "DATABASE, true, true, false",
        "OTHER, true, true, true",
        "PROGRESSION_REJECTED, false, false, true",
        "PROGRESSION_REFUSED, false, false, true",
        "PROGRESSION_UNAVAILABLE, false, false, true",
        "PROGRESSION_UNREACHABLE, false, false, true",
        "PROGRESSION_TIMEOUT, false, false, true",
        "PROGRESSION_MALFORMED, false, false, true"
    })
    void cause_should_belong_to_the_stages_the_contract_pairs_it_with(final IntakeFailureCause cause,
            final boolean receipt, final boolean store, final boolean enrich) {
        assertThat(cause.belongsTo(IntakeStage.RECEIPT)).as("receipt").isEqualTo(receipt);
        assertThat(cause.belongsTo(IntakeStage.STORE)).as("store").isEqualTo(store);
        assertThat(cause.belongsTo(IntakeStage.ENRICH)).as("enrich").isEqualTo(enrich);
    }
}
