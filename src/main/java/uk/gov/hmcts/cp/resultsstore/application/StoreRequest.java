package uk.gov.hmcts.cp.resultsstore.application;

import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;

/**
 * One share to store.
 *
 * @param messageId         the receipt's key, marked in the same transaction
 * @param identity          the share's identity
 * @param shareId           the share id computed from the identity as sent
 * @param sharedDays        its London and UTC days
 * @param checksum          SHA-256 of the text
 * @param text              the message text exactly as it arrived
 * @param parsedCopy        the working copy for {@code payload_json}: the enriched copy, or the arrived
 *                          text when nothing was added (specs/002-enrichment FR-017); the store removes
 *                          the escapes {@code jsonb} refuses before writing it
 * @param enrichmentApplied whether at least one application received results, so the parsed copy is
 *                          the enriched one (FR-018)
 * @param projection        the key details, read before the transaction
 */
public record StoreRequest(String messageId, ShareIdentity identity, UUID shareId, SharedDays sharedDays,
        String checksum, String text, String parsedCopy, boolean enrichmentApplied, Projection projection) {

    /**
     * A share stored as it arrived: the parsed copy is the arrived text and nothing was added.
     *
     * @param messageId  the receipt's key
     * @param identity   the share's identity
     * @param shareId    the share id
     * @param sharedDays its London and UTC days
     * @param checksum   SHA-256 of the text
     * @param text       the message text exactly as it arrived
     * @param projection the key details
     */
    public StoreRequest(final String messageId, final ShareIdentity identity, final UUID shareId,
            final SharedDays sharedDays, final String checksum, final String text, final Projection projection) {
        this(messageId, identity, shareId, sharedDays, checksum, text, text, false, projection);
    }
}
