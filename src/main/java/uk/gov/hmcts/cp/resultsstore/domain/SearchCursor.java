package uk.gov.hmcts.cp.resultsstore.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The keyset position of a search page (FR-028, FR-029; research R9): the last item's {@code shared_at}
 * in epoch microseconds and its {@code shareId}. On the wire it is base64url without padding of
 * {@code v1|<microseconds>|<shareId>}, at most {@value #MAX_LENGTH} characters, and is decoded strictly:
 * a text is a cursor only if it is exactly what {@link #encode()} writes for the position it decodes to.
 *
 * @param sharedAtMicros the last item's {@code shared_at}, epoch microseconds, negative before 1970 (intake
 *                       accepts four-digit years from 0000)
 * @param shareId        the last item's share id
 */
public record SearchCursor(long sharedAtMicros, UUID shareId) {

    /** The longest cursor text accepted. */
    public static final int MAX_LENGTH = 128;

    /**
     * Version 1: the prefix, the microseconds as {@link Long#toString(long)} writes them (a minus sign before
     * 1970, no plus sign, no leading zero, no {@code -0}), a canonical lower-case UUID.
     */
    private static final Pattern PLAIN = Pattern.compile(
            "^v1\\|(0|-?[1-9][0-9]{0,18})\\|([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");

    /** Checks that the position is complete. */
    public SearchCursor {
        Objects.requireNonNull(shareId, "shareId");
    }

    /**
     * The position after an item.
     *
     * @param sharedAt the item's {@code shared_at}, at most six fraction digits
     * @param shareId  the item's share id
     * @return its cursor
     */
    public static SearchCursor after(final Instant sharedAt, final UUID shareId) {
        return new SearchCursor(ChronoUnit.MICROS.between(Instant.EPOCH, sharedAt), shareId);
    }

    /**
     * Reads a cursor text strictly.
     *
     * @param text the {@code cursor} parameter, possibly {@code null}
     * @return the position, or empty when the text is not exactly a cursor this class encodes
     */
    public static Optional<SearchCursor> decode(final String text) {
        return Optional.ofNullable(text)
                .filter(candidate -> candidate.length() <= MAX_LENGTH && BASE64URL.matcher(candidate).matches())
                .flatMap(SearchCursor::plainText)
                .map(PLAIN::matcher)
                .filter(Matcher::matches)
                .flatMap(SearchCursor::position)
                .filter(cursor -> cursor.encode().equals(text));
    }

    /** The cursor text. */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1|" + sharedAtMicros + "|" + shareId).getBytes(StandardCharsets.UTF_8));
    }

    /** The last item's {@code shared_at}. */
    public Instant sharedAt() {
        return Instant.EPOCH.plus(sharedAtMicros, ChronoUnit.MICROS);
    }

    /** The decoded text, or empty when the bytes are not base64url or not UTF-8. */
    private static Optional<String> plainText(final String text) {
        Optional<String> plain;
        try {
            final byte[] bytes = Base64.getUrlDecoder().decode(text);
            plain = Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString());
        } catch (final IllegalArgumentException | CharacterCodingException notACursor) {
            // A recorded outcome: the text is not a cursor, which the caller answers 400 invalid_cursor.
            plain = Optional.empty();
        }
        return plain;
    }

    /** The position the matched parts name, or empty when the microseconds overflow. */
    private static Optional<SearchCursor> position(final Matcher parts) {
        Optional<SearchCursor> position;
        try {
            position = Optional.of(new SearchCursor(Long.parseLong(parts.group(1)), UUID.fromString(parts.group(2))));
        } catch (final NumberFormatException overflow) {
            // A recorded outcome: nineteen digits beyond the long range are not a position.
            position = Optional.empty();
        }
        return position;
    }
}
