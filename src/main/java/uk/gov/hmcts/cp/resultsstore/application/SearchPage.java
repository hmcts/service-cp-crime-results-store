package uk.gov.hmcts.cp.resultsstore.application;

import java.util.List;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;

/**
 * A search's answer (FR-028).
 *
 * @param items      at most {@code limit} shares, by {@code sharedTime} then {@code shareId}
 * @param nextCursor the cursor of the next page; {@code null} on the last page
 */
public record SearchPage(List<ShareView> items, String nextCursor) {

    /** Keeps an unmodifiable copy of the items. */
    public SearchPage {
        items = List.copyOf(items);
    }
}
