package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.util.List;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;

/**
 * A pull's answer (FR-013 to FR-015).
 *
 * @param items              at most {@code limit} shares, ascending {@code storedSeq}
 * @param nextStoredAfterSeq the next cursor: the last item's {@code storedSeq} when there is more, else the
 *                           greater of the request's cursor and the visibility bound
 * @param hasMore            whether more shares follow at or below the bound
 * @param visibleUpTo        the database's {@code now()} minus the lag, from the same statement
 */
public record PullPage(List<ShareView> items, long nextStoredAfterSeq,
        boolean hasMore, Instant visibleUpTo) {

    /** Keeps an unmodifiable copy of the items. */
    public PullPage {
        items = List.copyOf(items);
    }
}
