package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.UUID;

/**
 * One row of the defendant index ({@code share_defendant}). Ids only.
 *
 * @param caseId            {@code hearing.prosecutionCases[].id}
 * @param defendantId       {@code .defendants[].id}
 * @param masterDefendantId {@code .defendants[].masterDefendantId}, or {@code null} when not stated
 */
public record DefendantRef(UUID caseId, UUID defendantId, UUID masterDefendantId) {
}
