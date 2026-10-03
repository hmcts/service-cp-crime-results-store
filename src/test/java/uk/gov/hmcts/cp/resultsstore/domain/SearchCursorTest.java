package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("the search cursor")
class SearchCursorTest {

    private static final UUID SHARE_ID = UUID.fromString("3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11");

    private static final Instant SHARED_AT = Instant.parse("2026-10-03T09:15:00.123456Z");

    @Test
    void encode_then_decode_should_round_trip() {
        final SearchCursor cursor = SearchCursor.after(SHARED_AT, SHARE_ID);

        assertThat(cursor.sharedAt()).isEqualTo(SHARED_AT);
        assertThat(cursor.sharedAtMicros()).isEqualTo(1_791_018_900_123_456L);
        assertThat(SearchCursor.decode(cursor.encode())).contains(cursor);
        assertThat(cursor.encode()).isEqualTo(base64("v1|1791018900123456|" + SHARE_ID));
    }

    @Test
    void the_encoded_cursor_should_never_exceed_128_characters() {
        final SearchCursor longest = new SearchCursor(Long.MAX_VALUE, SHARE_ID);

        assertThat(longest.encode()).hasSizeLessThanOrEqualTo(SearchCursor.MAX_LENGTH);
        assertThat(SearchCursor.decode(longest.encode())).contains(longest);
    }

    @Test
    void the_cursor_should_be_base64url_without_padding() {
        assertThat(SearchCursor.after(SHARED_AT, SHARE_ID).encode()).matches("^[A-Za-z0-9_-]+$");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedTexts")
    void a_tampered_truncated_padded_or_non_base64url_cursor_should_be_invalid(final String name,
            final String text) {
        assertThat(SearchCursor.decode(text)).isEmpty();
    }

    static Stream<Arguments> malformedTexts() {
        final String valid = SearchCursor.after(SHARED_AT, SHARE_ID).encode();
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        final int lastIndex = alphabet.indexOf(valid.charAt(valid.length() - 1));
        return Stream.of(
                // The last character carries unused low bits; setting one decodes to the same bytes.
                Arguments.of("tampered: unused trailing bits set",
                        valid.substring(0, valid.length() - 1) + alphabet.charAt(lastIndex | 1)),
                Arguments.of("tampered: the version prefix changed",
                        (valid.charAt(1) == 'x' ? "dy" : "dx") + valid.substring(2)),
                Arguments.of("truncated", valid.substring(0, valid.length() - 7)),
                Arguments.of("padded", valid + "="),
                Arguments.of("padded twice", valid + "=="),
                Arguments.of("standard base64 alphabet", valid.substring(0, 4) + "+/" + valid.substring(6)),
                Arguments.of("white space", valid.substring(0, 4) + " " + valid.substring(4)),
                Arguments.of("not a cursor", "not a cursor"),
                Arguments.of("empty", ""));
    }

    @Test
    void an_over_long_cursor_should_be_invalid_even_when_it_decodes() {
        final String overLong = base64("v1|" + "0".repeat(60) + "1|" + SHARE_ID);

        assertThat(overLong).hasSizeGreaterThan(SearchCursor.MAX_LENGTH);
        assertThat(SearchCursor.decode(overLong)).isEmpty();
    }

    @Test
    void a_null_cursor_should_be_invalid() {
        assertThat(SearchCursor.decode(null)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "v2|1759482900123456|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "V1|1759482900123456|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "v1|1759482900123456|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11|x",
        "v1|1759482900123456",
        "v1|1759482900123456|1-1-1-1-1",
        "v1|1759482900123456|3F0C6A6E-5B1D-4C39-9D43-0E5F2C4B7A11",
        "v1|1759482900123456|not-a-uuid",
        "v1|-1|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "v1|+1759482900123456|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "v1|01759482900123456|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "v1|99999999999999999999|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "v1||3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11",
        "v1|17594829001234.56|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11"
    })
    void a_cursor_with_another_version_prefix_extra_part_bad_uuid_or_negative_microseconds_should_be_invalid(
            final String plain) {
        assertThat(SearchCursor.decode(base64(plain))).isEmpty();
    }

    @Test
    void a_well_formed_plain_cursor_should_decode() {
        assertThat(SearchCursor.decode(base64("v1|1759482900123456|3f0c6a6e-5b1d-4c39-9d43-0e5f2c4b7a11")))
                .contains(new SearchCursor(1_759_482_900_123_456L, SHARE_ID));
    }

    @Test
    void a_cursor_that_is_not_utf_8_should_be_invalid() {
        final String text = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xC3, 0x28});

        assertThat(SearchCursor.decode(text)).isEmpty();
    }

    @Test
    void zero_microseconds_should_round_trip() {
        final SearchCursor epoch = SearchCursor.after(Instant.EPOCH, SHARE_ID);

        assertThat(SearchCursor.decode(epoch.encode())).contains(epoch);
    }

    private static String base64(final String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }
}
