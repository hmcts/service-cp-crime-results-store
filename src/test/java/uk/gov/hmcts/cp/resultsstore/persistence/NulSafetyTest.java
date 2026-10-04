package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Whether PostgreSQL {@code jsonb} can hold a payload's parsed copy (research R8): it refuses the
 * escape {@code \u0000} and an escaped UTF-16 surrogate that is not half of a pair.
 */
@DisplayName("parsed-copy pre-check")
class NulSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"a\":\"plain\"}",
        "{\"a\":\"\\u0041\\u00e9\\u20ac\"}",
        // A valid surrogate pair, in either case.
        "{\"a\":\"\\uD83D\\uDE00\"}",
        "{\"a\":\"\\ud83d\\ude00\"}",
        // An escaped backslash followed by u0000 is plain text, not an escape.
        "{\"a\":\"\\\\u0000\"}",
        "{\"a\":\"\\\\uD800\"}",
        // Other escapes are skipped whole.
        "{\"a\":\"\\\"\\n\\t\\/\"}",
        // u0001 is not NUL.
        "{\"a\":\"\\u0001\"}",
        // A trailing backslash or a short escape cannot be a NUL escape.
        "\\",
        "\\u00",
        // A unicode escape with a non-hex digit is malformed, so passed over.
        "{\"a\":\"\\uZZZZ\"}",
        "{\"a\":\"\\u00G0\"}",
    })
    void text_that_jsonb_can_hold_should_keep_its_parsed_copy(final String text) {
        assertThat(NulSafety.isJsonbSafe(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":\"\\u0000\"}",
        "{\"a\":\"x\\u0000y\"}",
        // A lone high surrogate, at the end, before a non-escape, or before another high one.
        "{\"a\":\"\\uD800\"}",
        "{\"a\":\"\\uDBFFx\"}",
        "{\"a\":\"\\uD800\\uD800\\uDC00\"}",
        "{\"a\":\"\\uD800\\n\"}",
        "{\"a\":\"\\uD800\\u0041\"}",
        "{\"a\":\"\\uD800abcdefgh\"}",
        // A lone low surrogate.
        "{\"a\":\"\\uDC00\"}",
        "{\"a\":\"\\udfff\"}",
        "{\"a\":\"\\\\\\u0000\"}",
    })
    void text_that_jsonb_refuses_should_skip_its_parsed_copy(final String text) {
        assertThat(NulSafety.isJsonbSafe(text)).isFalse();
    }
}
