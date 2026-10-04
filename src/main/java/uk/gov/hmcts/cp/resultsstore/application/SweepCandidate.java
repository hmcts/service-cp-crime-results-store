package uk.gov.hmcts.cp.resultsstore.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A {@code FAILED} share the sweep selected, with the attempts it had when selected (research R13):
 * the row is worked on only if, under the locks, it is still {@code FAILED} with this many attempts.
 *
 * @param shareId    the share
 * @param hearingId  its hearing, for the day lock
 * @param hearingDay its hearing day, for the day lock
 * @param attempts   {@code projection_attempts} when selected
 */
public record SweepCandidate(UUID shareId, UUID hearingId, LocalDate hearingDay, int attempts) {
}
