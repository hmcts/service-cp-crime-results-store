package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        "OTHER, other"
    })
    void cause_should_have_its_lower_case_tag(final IntakeFailureCause cause, final String tag) {
        assertThat(cause.tag()).isEqualTo(tag);
    }

    @ParameterizedTest
    @CsvSource({"RECEIPT, receipt", "STORE, store"})
    void stage_should_have_its_lower_case_tag(final IntakeStage stage, final String tag) {
        assertThat(stage.tag()).isEqualTo(tag);
    }
}
