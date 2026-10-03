package uk.gov.hmcts.cp.resultsstore.application;

import java.time.Instant;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.domain.SearchCursor;

/**
 * A search, its parameters checked (FR-026 to FR-029). Both range forms arrive here as the half-open
 * {@code shared_at} range: the day form as London midnight to London midnight.
 *
 * @param courtCentreId the court, exact match
 * @param sharedFrom    the first {@code shared_at} included
 * @param sharedTo      the first {@code shared_at} excluded
 * @param dayYouthSeen  the day filter
 * @param latestOnly    whether only each day's latest share is wanted
 * @param after         the position after the previous page's last item; {@code null} for the first page
 * @param limit         the most items to return, 1 to 500
 */
public record SearchQuery(UUID courtCentreId, Instant sharedFrom, Instant sharedTo, DayYouthFilter dayYouthSeen,
        boolean latestOnly, SearchCursor after, int limit) {
}
