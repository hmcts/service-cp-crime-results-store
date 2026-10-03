package uk.gov.hmcts.cp.resultsstore.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;

@DisplayName("retryable intake exception")
class RetryableIntakeExceptionTest {

    @Test
    void with_a_failed_class_name_should_chain_no_cause_and_name_the_class() {
        final RetryableIntakeException failure = new RetryableIntakeException(IntakeStage.ENRICH,
                IntakeFailureCause.PROGRESSION_MALFORMED, "UnexpectedEndOfInputException");

        assertThat(failure.getStage()).isEqualTo(IntakeStage.ENRICH);
        assertThat(failure.getFailureCause()).isEqualTo(IntakeFailureCause.PROGRESSION_MALFORMED);
        assertThat(failure.getFailedClassName()).contains("UnexpectedEndOfInputException");
        assertThat(failure.getCause()).isNull();
        assertThat(failure).hasMessage(
                "intake failed at ENRICH: PROGRESSION_MALFORMED (UnexpectedEndOfInputException)");
    }

    @Test
    void with_a_cause_should_chain_it_and_name_no_class() {
        final SQLException cause = new SQLException("refused");
        final RetryableIntakeException failure =
                new RetryableIntakeException(IntakeStage.STORE, IntakeFailureCause.DATABASE, cause);

        assertThat(failure.getCause()).isSameAs(cause);
        assertThat(failure.getFailedClassName()).isEmpty();
        assertThat(failure).hasMessage("intake failed at STORE: DATABASE");
    }
}
