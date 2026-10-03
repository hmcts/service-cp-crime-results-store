package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;

@DisplayName("retryable failure classification")
class RetryableFailuresTest {

    @ParameterizedTest
    @CsvSource({
        "55P03, LOCK_TIMEOUT",
        "57014, STATEMENT_TIMEOUT",
        "08006, DATABASE"
    })
    void failure_carrying_a_sql_state_should_be_classified_by_it(final String sqlState,
            final IntakeFailureCause cause) {
        final UncategorizedSQLException failure =
                new UncategorizedSQLException("insert", "INSERT", new SQLException("refused", sqlState));

        final RetryableIntakeException classified = RetryableFailures.classify(IntakeStage.STORE, failure);

        assertThat(classified.getStage()).isEqualTo(IntakeStage.STORE);
        assertThat(classified.getFailureCause()).isEqualTo(cause);
        assertThat(classified.getCause()).isSameAs(failure);
    }

    @Test
    void sql_state_deeper_in_the_cause_chain_should_be_found() {
        final QueryTimeoutException failure = new QueryTimeoutException("timed out",
                new IllegalStateException("wrapped", new SQLException("cancelled", "57014")));

        assertThat(RetryableFailures.classify(IntakeStage.RECEIPT, failure).getFailureCause())
                .isEqualTo(IntakeFailureCause.STATEMENT_TIMEOUT);
    }

    @Test
    void database_failure_with_no_sql_state_should_be_database() {
        final DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("down", new SQLException("down"));

        assertThat(RetryableFailures.classify(IntakeStage.RECEIPT, failure).getFailureCause())
                .isEqualTo(IntakeFailureCause.DATABASE);
    }

    @Test
    void transaction_failure_with_no_sql_exception_should_be_database() {
        final CannotCreateTransactionException failure = new CannotCreateTransactionException("no connection");

        assertThat(RetryableFailures.classify(IntakeStage.RECEIPT, failure).getFailureCause())
                .isEqualTo(IntakeFailureCause.DATABASE);
    }

    @Test
    void sql_state_past_the_search_depth_should_not_be_found() {
        RuntimeException failure = new QueryTimeoutException("timed out", new SQLException("lock", "55P03"));
        for (int link = 0; link < 40; link++) {
            failure = new IllegalStateException("wrapped", failure);
        }

        assertThat(RetryableFailures.classify(IntakeStage.STORE, failure).getFailureCause())
                .as("the search stops before a chain this long reaches its SQLSTATE")
                .isEqualTo(IntakeFailureCause.DATABASE);
    }

    @Test
    void cause_that_points_at_itself_should_end_the_search_as_database() {
        final IllegalStateException loop = new IllegalStateException("loop") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(RetryableFailures.classify(IntakeStage.RECEIPT, loop).getFailureCause())
                .isEqualTo(IntakeFailureCause.DATABASE);
    }

    @Test
    void message_should_hold_the_stage_and_cause_only() {
        final UncategorizedSQLException failure = new UncategorizedSQLException("insert", "INSERT payload text",
                new SQLException("payload text", "55P03"));

        assertThat(RetryableFailures.classify(IntakeStage.STORE, failure).getMessage())
                .isEqualTo("intake failed at STORE: LOCK_TIMEOUT");
    }
}
