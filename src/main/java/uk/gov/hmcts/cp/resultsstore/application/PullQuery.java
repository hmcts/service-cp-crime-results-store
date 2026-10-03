package uk.gov.hmcts.cp.resultsstore.application;

import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;

/**
 * A pull, its parameters checked (FR-009 to FR-012).
 *
 * @param storedAfterSeq the cursor, not negative
 * @param limit          the most items to return, 1 to 500
 * @param dayYouthSeen   the day filter; never {@link DayYouthFilter#FALSE}
 * @param courtCentreId  the court, exact match; {@code null} for every court
 */
public record PullQuery(long storedAfterSeq, int limit, DayYouthFilter dayYouthSeen, UUID courtCentreId) {
}
