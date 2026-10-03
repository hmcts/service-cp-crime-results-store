package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("the dayYouthSeen filter")
class DayYouthFilterTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"notFalse, NOT_FALSE", "true, TRUE", "false, FALSE"})
    void from_value_should_accept_not_false_true_and_false_case_sensitively(final String value,
            final DayYouthFilter expected) {
        assertThat(DayYouthFilter.fromValue(value)).contains(expected);
        assertThat(expected.wireValue()).isEqualTo(value);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullSource
    @ValueSource(strings = {"NOTFALSE", "notfalse", "True", "FALSE", "", " true", "any", "ANY", "NOT_FALSE"})
    void anything_else_should_be_refused(final String value) {
        assertThat(DayYouthFilter.fromValue(value)).isEmpty();
    }

    @Test
    void false_should_not_be_allowed_on_pull() {
        assertThat(DayYouthFilter.FALSE.allowedOnPull()).isFalse();
        assertThat(DayYouthFilter.ANY.allowedOnPull()).isTrue();
        assertThat(DayYouthFilter.NOT_FALSE.allowedOnPull()).isTrue();
        assertThat(DayYouthFilter.TRUE.allowedOnPull()).isTrue();
    }

    @Test
    void any_should_have_no_wire_value() {
        assertThat(DayYouthFilter.ANY.wireValue()).isNull();
    }
}
