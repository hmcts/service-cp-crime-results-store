package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionFailureKind;
import uk.gov.hmcts.cp.resultsstore.domain.ExtractionStage;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.NonShareReason;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

/**
 * The stand-in observer until the Micrometer one lands (T013): it says once, at start, that intake
 * metrics are not recorded, and then records and logs nothing.
 */
@DisplayName("placeholder intake observer")
class PlaceholderIntakeObserverTest {

    @Test
    void observer_should_warn_once_at_start_and_then_stay_silent() {
        try (CapturedLog log = CapturedLog.forClass(PlaceholderIntakeObserver.class)) {
            final PlaceholderIntakeObserver observer = new PlaceholderIntakeObserver();

            assertThat(log.events()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("Intake metrics are not recorded")
                        .contains("Micrometer observer");
            });

            observer.received();
            observer.messageIdMissing();
            observer.notShare(NonShareReason.NOT_JSON);
            observer.alreadySettled();
            observer.stored(true, Duration.ofSeconds(1));
            observer.duplicate();
            observer.parsedCopySkipped();
            observer.extractionFailed(ExtractionStage.SWEEP, ExtractionFailureKind.WRONG_TYPE);
            observer.sweepRow(SweepRowOutcome.ERROR);
            observer.intakeFailed(IntakeStage.STORE, IntakeFailureCause.LOCK_TIMEOUT);

            assertThat(log.events()).hasSize(1).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN);
        }
    }
}
