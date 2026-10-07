package uk.gov.hmcts.cp.resultsstore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The escapes PostgreSQL {@code jsonb} refuses: {@code \u0000} and an escaped UTF-16 surrogate that
 * is not half of a pair. The working copy is written without them (spec 005 FR-001).
 */
@DisplayName("working-copy escape removal")
class NulSafetyTest {

    private static final String[] JSONB_CAN_HOLD = {
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
        "\\u",
        "\\u00",
        // A unicode escape with a non-hex digit is malformed, so passed over.
        "{\"a\":\"\\uZZZZ\"}",
        "{\"a\":\"\\u00G0\"}",
    };

    static Stream<Arguments> refused() {
        return Stream.of(
            Arguments.of("\\u0000", ""),
            Arguments.of("x\\u0000y", "xy"),
            Arguments.of("{\"a\\u0000\":1}", "{\"a\":1}"),
            Arguments.of("\\uDC00x", "x"),
            Arguments.of("\\udfffx", "x"),
            Arguments.of("\\uDBFFx", "x"),
            Arguments.of("\"\\uD800\"", "\"\""),
            Arguments.of("\\uD800", ""),
            Arguments.of("\\uD800\\uD800\\uDC00", "\\uD800\\uDC00"),
            Arguments.of("\\uD800A", "A"),
            Arguments.of("\\uD800\\u0041", "\\u0041"),
            Arguments.of("\\uD800\\n", "\\n"),
            Arguments.of("\\uD800\\u0000\\uDC00", ""),
            // An unpaired high escape before a short escape, a raw pair, or at the end of the text; a low one alone.
            Arguments.of("\\uD800\\u00", "\\u00"),
            Arguments.of("\\uD800\uD800\uDC00", "\uD800\uDC00"),
            Arguments.of("\\uDC00", ""),
            Arguments.of("{\"a\":\"\\\\\\u0000\"}", "{\"a\":\"\\\\\"}"));
    }

    static Stream<String> everyText() {
        return Stream.concat(Stream.of(JSONB_CAN_HOLD), refused().map(arguments -> (String) arguments.get()[0]));
    }

    static Stream<String> jsonbCanHold() {
        return Stream.of(JSONB_CAN_HOLD);
    }

    @ParameterizedTest
    @MethodSource("refused")
    void strip_should_remove_the_nul_escape_and_each_unpaired_surrogate_escape(final String text,
                                                                               final String expected) {
        assertThat(NulSafety.strip(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("jsonbCanHold")
    void strip_should_keep_text_jsonb_can_hold_unchanged(final String text) {
        assertThat(NulSafety.strip(text)).isEqualTo(text);
    }

    @ParameterizedTest
    @MethodSource("everyText")
    void strip_should_be_idempotent(final String text) {
        final String once = NulSafety.strip(text);
        assertThat(NulSafety.strip(once)).isEqualTo(once);
    }
}
