package uk.gov.hmcts.cp.resultsstore.persistence;

/**
 * The escapes PostgreSQL {@code jsonb} refuses, removed from the working copy (spec 005 FR-001).
 *
 * <p>{@code jsonb} refuses the escape {@code \u0000} and an escaped UTF-16 surrogate that is not half
 * of a pair (a high one followed at once by a low one). The text column takes both, so
 * {@code payload_text} keeps them; the working copy is written without them, so every share has one.
 * Removal is plain removal: no replacement character, and nothing else changes. The scan reads escapes
 * as JSON does: a backslash and the character after it are one escape, so an escaped backslash
 * followed by {@code u0000} is plain text. In a JSON text the parser has accepted, where every
 * unicode escape has its four hex digits, a removed escape starts and ends on an escape boundary, so
 * removing one never makes another, and stripping twice changes nothing more. Raw characters are not
 * checked: the parser has already refused a raw U+0000, and the text arrived as a Java string.
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
     * The text with the escapes {@code jsonb} refuses removed.
     *
     * @param text a JSON text
     * @return the text without them
     */
    public static String strip(final String text) {
        final StringBuilder kept = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == BACKSLASH) {
                final int unit = unicodeEscape(text, index);
                final boolean storable = isStorable(text, index, unit);
                final int length = stripLength(unit, storable);
                if (storable) {
                    kept.append(text, index, Math.min(index + length, text.length()));
                }
                index += length;
            } else {
                kept.append(text.charAt(index));
                index++;
            }
        }
        return kept.toString();
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

    /** A kept surrogate pair is two escapes; any other unit escape is one, kept or not. */
    private static int stripLength(final int unit, final boolean storable) {
        return storable && Character.isHighSurrogate((char) unit) ? 2 * ESCAPE_LENGTH : escapeLength(unit);
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
