package uk.gov.hmcts.cp.resultsstore.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A share's payload as the payload query read it, before the service serves it.
 *
 * @param shareId           the share
 * @param hearingId         its hearing
 * @param hearingDay        its hearing day
 * @param sharedTime        its stored {@code shared_at}
 * @param enrichmentApplied whether intake added application results to the working copy
 * @param body              the working copy as read, already without {@code _metadata}
 */
public record StoredPayload(UUID shareId, UUID hearingId, LocalDate hearingDay, Instant sharedTime,
        boolean enrichmentApplied, String body) {

    /** Checks that every value is present. */
    public StoredPayload {
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(hearingId, "hearingId");
        Objects.requireNonNull(hearingDay, "hearingDay");
        Objects.requireNonNull(sharedTime, "sharedTime");
        Objects.requireNonNull(body, "body");
    }
}
