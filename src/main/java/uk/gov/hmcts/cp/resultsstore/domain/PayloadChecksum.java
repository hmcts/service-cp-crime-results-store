package uk.gov.hmcts.cp.resultsstore.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the UTF-8 bytes of the stored text, as 64 lower-case hex characters (R5, FR-016). */
public final class PayloadChecksum {

    private PayloadChecksum() {
        // Static functions only.
    }

    /**
     * Computes the checksum.
     *
     * @param text the text exactly as stored
     * @return 64 lower-case hex characters
     */
    public static String sha256Hex(final String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the checksum of bytes, such as a served body (FR-034).
     *
     * @param bytes the bytes exactly as served
     * @return 64 lower-case hex characters
     */
    public static String sha256Hex(final byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException absent) {
            // Every Java platform must provide SHA-256; reaching here means a broken runtime.
            throw new IllegalStateException("the runtime has no SHA-256 digest", absent);
        }
    }
}
