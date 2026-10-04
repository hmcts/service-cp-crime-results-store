package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeOutcome;

/**
 * How a delivery ended, with the identifiers the listener puts in the logging context.
 *
 * @param outcome    the outcome
 * @param messageId  the receipt's key
 * @param shareId    the stored share (or the existing one for a duplicate), else {@code null}
 * @param hearingId  {@code hearing.id}, if it was read
 * @param hearingDay {@code hearingDay}, if it was read
 * @param sharedAt   {@code sharedTime}, if it was read
 */
public record IntakeResult(IntakeOutcome outcome, String messageId, UUID shareId, UUID hearingId,
        LocalDate hearingDay, Instant sharedAt) {
}
