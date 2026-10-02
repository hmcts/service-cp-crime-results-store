package uk.gov.hmcts.cp.resultsstore.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The share id: a name-based UUID, version 5 (RFC 9562 section 5.5), over
 * {@code hearingId|hearingDay|sharedTime} with each part exactly as the message spells it
 * (research R4, FR-011, FR-012).
 */
public final class ShareId {

    /** Fixed for the life of the store; changing it would give every share a new id. */
    public static final UUID NAMESPACE = UUID.fromString("3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60");

    private static final String SEPARATOR = "|";

    private static final int UUID_BYTES = 16;

    private static final int VERSION_BYTE = 6;

    private static final int VARIANT_BYTE = 8;

    private ShareId() {
        // Static functions only.
    }

    /**
     * Computes the share id.
     *
     * @param hearingId  {@code hearing.id} as sent
     * @param hearingDay {@code hearingDay} as sent
     * @param sharedTime {@code sharedTime} as sent
     * @return the version 5 UUID
     */
    public static UUID from(final String hearingId, final String hearingDay, final String sharedTime) {
        final MessageDigest sha1 = sha1();
        sha1.update(ByteBuffer.allocate(UUID_BYTES)
                .putLong(NAMESPACE.getMostSignificantBits())
                .putLong(NAMESPACE.getLeastSignificantBits())
                .array());
        final String name = hearingId + SEPARATOR + hearingDay + SEPARATOR + sharedTime;
        final byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[VERSION_BYTE] = (byte) (hash[VERSION_BYTE] & 0x0f | 0x50);
        hash[VARIANT_BYTE] = (byte) (hash[VARIANT_BYTE] & 0x3f | 0x80);
        final ByteBuffer bytes = ByteBuffer.wrap(hash, 0, UUID_BYTES);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (final NoSuchAlgorithmException absent) {
            // Every Java platform must provide SHA-1; reaching here means a broken runtime.
            throw new IllegalStateException("the runtime has no SHA-1 digest", absent);
        }
    }
}
