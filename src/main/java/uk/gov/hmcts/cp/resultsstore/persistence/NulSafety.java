package uk.gov.hmcts.cp.resultsstore.persistence;

/**
 * Whether PostgreSQL {@code jsonb} can hold a payload's parsed copy (research R8, FR-015).
 *
 * <p>{@code jsonb} refuses the escape {@code \u0000} and an escaped UTF-16 surrogate that is not half
 * of a pair (a high one followed at once by a low one). The text column takes both, so such a payload
 * is stored as text with no parsed copy. The scan reads escapes as JSON does: a backslash and the
 * character after it are one escape, so an escaped backslash followed by {@code u0000} is plain text.
 * Raw characters are not checked: the parser has already refused a raw U+0000, and the text arrived
 * as a Java string.
 */
public final class NulSafety {

    private static final char BACKSLASH = '\\';

    private static final char UNICODE = 'u';

    /** {@code \}, {@code u} and four hex digits. */
    private static final int ESCAPE_LENGTH = 6;

    private static final int RADIX = 16;

    private NulSafety() {
        // Static functions only.
    }

    /**
     * Whether {@code jsonb} can hold the text's parsed copy.
     *
     * @param text a JSON text
     * @return {@code false} when it holds {@code \u0000} or an unpaired surrogate escape
     */
    public static boolean isJsonbSafe(final String text) {
        boolean safe = true;
        int index = 0;
        while (safe && index < text.length()) {
            if (text.charAt(index) == BACKSLASH) {
                final int unit = unicodeEscape(text, index);
                safe = isStorable(text, index, unit);
                // A surrogate pair is checked from its high half; the low half is passed over with it.
                index += Character.isHighSurrogate((char) unit) ? 2 * ESCAPE_LENGTH : escapeLength(unit);
            } else {
                index++;
            }
        }
        return safe;
    }

    private static boolean isStorable(final String text, final int index, final int unit) {
        final boolean storable;
        if (unit == 0 || Character.isLowSurrogate((char) unit)) {
            storable = false;
        } else if (Character.isHighSurrogate((char) unit)) {
            storable = Character.isLowSurrogate((char) unicodeEscape(text, index + ESCAPE_LENGTH));
        } else {
            storable = true;
        }
        return storable;
    }

    /** The other escapes are two characters long; a short or missing one is passed over. */
    private static int escapeLength(final int unit) {
        return unit < 0 ? 2 : ESCAPE_LENGTH;
    }

    /** The UTF-16 unit of the {@code \\uXXXX} escape at {@code index}, or -1 when there is none. */
    private static int unicodeEscape(final String text, final int index) {
        int unit = -1;
        if (index + ESCAPE_LENGTH <= text.length() && text.charAt(index) == BACKSLASH
                && text.charAt(index + 1) == UNICODE) {
            unit = hex(text.substring(index + 2, index + ESCAPE_LENGTH));
        }
        return unit;
    }

    private static int hex(final String digits) {
        int value = 0;
        for (int position = 0; value >= 0 && position < digits.length(); position++) {
            final int digit = Character.digit(digits.charAt(position), RADIX);
            value = digit < 0 ? -1 : value * RADIX + digit;
        }
        return value;
    }
}
