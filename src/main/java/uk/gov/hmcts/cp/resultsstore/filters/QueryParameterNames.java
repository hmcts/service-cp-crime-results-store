package uk.gov.hmcts.cp.resultsstore.filters;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Reads parameter names from a raw query string without asking the servlet container, so nothing about the
 * request's body or parameters is parsed before authorisation (research R2). Pairs are split on {@code &},
 * the name is everything before the first {@code =}, and names are decoded as the container decodes a query:
 * UTF-8 percent escapes, {@code +} as a space. A name with a malformed escape matches nothing.
 */
public final class QueryParameterNames {

    private static final char ESCAPE = '%';

    private static final char PLUS = '+';

    private static final int HEX_RADIX = 16;

    private static final int ESCAPE_LENGTH = 3;

    private QueryParameterNames() {
        // Static functions only.
    }

    /**
     * Whether the raw query string carries a parameter of this name.
     *
     * @param rawQuery the query string as sent, or null
     * @param name the decoded parameter name, matched case-sensitively
     * @return true when one {@code &}-separated pair's name decodes to {@code name}
     */
    public static boolean contains(final String rawQuery, final String name) {
        return rawQuery != null && Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2)[0])
                .map(QueryParameterNames::decode)
                .anyMatch(decoded -> decoded.filter(name::equals).isPresent());
    }

    /**
     * Decodes a raw query name or value as the container does.
     *
     * @param raw the text as sent
     * @return the decoded text, or empty when an escape is malformed
     */
    public static Optional<String> decode(final String raw) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        boolean wellFormed = true;
        int index = 0;
        while (wellFormed && index < raw.length()) {
            final char current = raw.charAt(index);
            if (current == ESCAPE) {
                final int value = escapedByte(raw, index);
                wellFormed = value >= 0;
                if (wellFormed) {
                    bytes.write(value);
                }
                index += ESCAPE_LENGTH;
            } else {
                final String plain = current == PLUS ? " " : String.valueOf(current);
                bytes.writeBytes(plain.getBytes(StandardCharsets.UTF_8));
                index++;
            }
        }
        return wellFormed ? Optional.of(bytes.toString(StandardCharsets.UTF_8)) : Optional.empty();
    }

    /** The byte a {@code %xx} escape at {@code index} stands for, or -1 when it is not two ASCII hex digits. */
    private static int escapedByte(final String raw, final int index) {
        int value = -1;
        if (index + ESCAPE_LENGTH <= raw.length()) {
            final char high = raw.charAt(index + 1);
            final char low = raw.charAt(index + 2);
            if (HexFormat.isHexDigit(high) && HexFormat.isHexDigit(low)) {
                value = HexFormat.fromHexDigit(high) * HEX_RADIX + HexFormat.fromHexDigit(low);
            }
        }
        return value;
    }
}
