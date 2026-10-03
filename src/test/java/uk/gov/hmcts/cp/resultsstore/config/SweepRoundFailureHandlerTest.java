package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.util.ErrorHandler;
import uk.gov.hmcts.cp.resultsstore.application.IntakeObserver;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

/**
 * The sweep schedule's handler for a round that throws (FR-037): a runtime failure is counted on a
 * bounded meter and logged by class, and the next round runs; an {@link Error} is counted, logged
 * and thrown on.
 */
@DisplayName("sweep round failure handler")
class SweepRoundFailureHandlerTest {

    private static final String MARKER = "ROUND-MARKER-41c2";

    private final IntakeObserver observer = mock(IntakeObserver.class);

    private final ErrorHandler handler = SweepSchedulingConfig.roundFailureHandler(observer);

    @Test
    void runtime_failure_should_be_counted_and_logged_by_class_alone() {
        try (CapturedLog log = CapturedLog.forClass(SweepSchedulingConfig.class)) {
            handler.handleError(new QueryTimeoutException("statement quoted " + MARKER));

            verify(observer).sweepRoundFailed();
            assertThat(log.messages()).singleElement().asString()
                    .contains("Extraction sweep round failed")
                    .contains(QueryTimeoutException.class.getName())
                    .doesNotContain(MARKER);
        }
    }

    @Test
    void error_should_be_counted_logged_and_thrown_on() {
        final OutOfMemoryError fatal = new OutOfMemoryError("heap " + MARKER);

        try (CapturedLog log = CapturedLog.forClass(SweepSchedulingConfig.class)) {
            assertThatThrownBy(() -> handler.handleError(fatal)).isSameAs(fatal);

            verify(observer).sweepRoundFailed();
            assertThat(log.messages()).singleElement().asString()
                    .contains(OutOfMemoryError.class.getName())
                    .doesNotContain(MARKER);
        }
    }
}
