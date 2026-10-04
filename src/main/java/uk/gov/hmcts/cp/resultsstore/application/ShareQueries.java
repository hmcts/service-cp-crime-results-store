package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.domain.StoredPayload;

/**
 * The read side of the store (data-model.md *Read queries*). Read-only; each call is one autocommit
 * statement. Only {@link #payload} and {@link #arrivedText} read the payload table (Principle III).
 */
public interface ShareQueries {

    /**
     * The shares after the cursor at or below the visibility bound, in {@code stored_seq} order: at most
     * {@code query.limit() + 1} rows, so the caller can tell whether there are more (FR-010 to FR-016).
     *
     * @param query the checked pull
     * @param lag   the visibility lag
     * @return the rows with the bound and {@code visibleUpTo} from the same statement
     */
    PullRows pull(PullQuery query, Duration lag);

    /**
     * The court's shares in the range, after the cursor, ordered by {@code shared_at} then {@code share_id}:
     * at most {@code query.limit() + 1} rows. Never a {@code FAILED} share (FR-026 to FR-028).
     *
     * @param query the checked search
     * @return the rows
     */
    List<ShareView> search(SearchQuery query);

    /**
     * One share (FR-031).
     *
     * @param shareId the share
     * @return the share, or empty when none has the id
     */
    Optional<ShareView> share(UUID shareId);

    /**
     * Every share of a hearing day in {@code shared_at} order (FR-032).
     *
     * @param hearingId  the hearing
     * @param hearingDay the day
     * @return the shares, empty when the day has none
     */
    List<ShareView> dayVersions(UUID hearingId, LocalDate hearingDay);

    /**
     * A share's payload: the working copy without {@code _metadata}, or the arrived text when the working
     * copy is empty (FR-033).
     *
     * @param shareId the share
     * @return the payload, or empty when no share has the id
     */
    Optional<StoredPayload> payload(UUID shareId);

    /**
     * A share's text as it arrived ({@code payload_text}, still with {@code _metadata}), whatever the working copy
     * holds (FR-038, FR-041; phase D). {@code payload_sha256} is not read: it is never served.
     *
     * @param shareId the share
     * @return the text in the {@link uk.gov.hmcts.cp.resultsstore.domain.PayloadForm#ARRIVED_TEXT} form, or empty
     *         when no share has the id
     */
    Optional<StoredPayload> arrivedText(UUID shareId);

    /**
     * What a pull read.
     *
     * @param visibleUpTo the database's {@code now()} minus the lag
     * @param boundSeq    the visibility bound: the highest {@code stored_seq} stored at or before
     *                    {@code visibleUpTo}, whatever the filters; {@code null} when no share is that old
     * @param rows        at most {@code limit + 1} matching rows
     */
    record PullRows(Instant visibleUpTo, Long boundSeq, List<ShareView> rows) {

        /** Keeps an unmodifiable copy of the rows. */
        public PullRows {
            rows = List.copyOf(rows);
        }
    }
}
