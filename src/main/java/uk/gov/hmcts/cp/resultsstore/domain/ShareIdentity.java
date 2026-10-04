package uk.gov.hmcts.cp.resultsstore.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What makes a share one share: its hearing, hearing day and shared time, parsed, with the three
 * strings exactly as the message sent them (the share id is computed from those, FR-012).
 *
 * @param hearingId     {@code hearing.id}
 * @param hearingDay    {@code hearingDay}
 * @param sharedAt      {@code sharedTime} as an instant
 * @param rawHearingId  {@code hearing.id} as sent
 * @param rawHearingDay {@code hearingDay} as sent
 * @param rawSharedTime {@code sharedTime} as sent
 */
public record ShareIdentity(UUID hearingId, LocalDate hearingDay, Instant sharedAt,
        String rawHearingId, String rawHearingDay, String rawSharedTime) {

    /** The share id computed from the three strings as sent. */
    public UUID shareId() {
        return ShareId.from(rawHearingId, rawHearingDay, rawSharedTime);
    }
}
