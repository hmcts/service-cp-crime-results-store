package uk.gov.hmcts.cp.resultsstore.application;

import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;

/**
 * One share to store.
 *
 * @param messageId  the receipt's key, marked in the same transaction
 * @param identity   the share's identity
 * @param shareId    the share id computed from the identity as sent
 * @param sharedDays its London and UTC days
 * @param checksum   SHA-256 of the text
 * @param text       the message text exactly as received
 * @param projection the key details, read before the transaction
 */
public record StoreRequest(String messageId, ShareIdentity identity, UUID shareId, SharedDays sharedDays,
        String checksum, String text, Projection projection) {
}
