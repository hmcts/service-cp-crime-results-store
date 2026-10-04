package uk.gov.hmcts.cp.resultsstore.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The share item: a read view of one {@code hearing_share} row, with no payload (FR-006; data-model.md
 * *The share item*).
 *
 * @param shareId            {@code share_id}
 * @param hearingId          {@code hearing_id}
 * @param hearingDay         {@code hearing_day}
 * @param sharedTime         {@code shared_at}
 * @param storedSeq          {@code stored_seq}, the pull cursor
 * @param storedAt           {@code stored_at}
 * @param sharedDayLondon    {@code shared_day_london}
 * @param sharedDayUtc       {@code shared_day_utc}
 * @param keyDetails         the eight key-detail columns; {@code null} exactly when the extraction is
 *                           {@link ProjectionStatus#FAILED}
 * @param anySubjectIsYouth  {@code any_subject_is_youth}, {@code null} when unknown
 * @param dayYouthSeen       {@code day_youth_seen}, {@code null} when unknown for the day
 * @param latest             {@code is_latest}
 * @param predecessorShareId {@code predecessor_share_id}, {@code null} for the day's first share
 * @param arrivedOutOfOrder  {@code arrived_out_of_order}
 * @param enrichmentApplied  {@code enrichment_applied}
 * @param projectionStatus   {@code projection_status}
 * @param projectionVersion  {@code projection_version}
 * @param projectedAt        {@code projected_at}
 * @param versionNumber      the share's place in its day by {@code shared_at}, from 1
 */
public record ShareView(UUID shareId, UUID hearingId, LocalDate hearingDay, Instant sharedTime, long storedSeq,
        Instant storedAt, LocalDate sharedDayLondon, LocalDate sharedDayUtc, KeyDetails keyDetails,
        Boolean anySubjectIsYouth, Boolean dayYouthSeen, boolean latest, UUID predecessorShareId,
        boolean arrivedOutOfOrder, boolean enrichmentApplied, ProjectionStatus projectionStatus,
        int projectionVersion, Instant projectedAt, long versionNumber) {

    /** Checks the identity and the rule that a {@code FAILED} share has no key details (V3). */
    public ShareView {
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(hearingId, "hearingId");
        Objects.requireNonNull(hearingDay, "hearingDay");
        Objects.requireNonNull(sharedTime, "sharedTime");
        Objects.requireNonNull(storedAt, "storedAt");
        Objects.requireNonNull(projectionStatus, "projectionStatus");
        if (keyDetails == null != (projectionStatus == ProjectionStatus.FAILED)) {
            throw new IllegalArgumentException("keyDetails must be null exactly when projectionStatus is FAILED");
        }
    }
}
