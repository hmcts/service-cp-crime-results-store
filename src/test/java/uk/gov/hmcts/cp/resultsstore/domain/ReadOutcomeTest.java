package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("read outcome tags")
class ReadOutcomeTest {

    /** The {@code outcome} values of contracts/metrics.md. */
    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(ReadOutcome.values()).map(ReadOutcome::tag))
                .containsExactly("ok", "not_modified", "bad_request", "not_found", "unavailable", "failed");
    }

    @ParameterizedTest
    @EnumSource(ReadEndpoint.class)
    void not_modified_should_apply_to_the_payload_endpoint_only(final ReadEndpoint endpoint) {
        assertThat(ReadOutcome.NOT_MODIFIED.appliesTo(endpoint)).isEqualTo(endpoint == ReadEndpoint.PAYLOAD);
    }

    @ParameterizedTest
    @EnumSource(value = ReadOutcome.class, names = "NOT_MODIFIED", mode = EnumSource.Mode.EXCLUDE)
    void every_other_outcome_should_apply_to_every_endpoint(final ReadOutcome outcome) {
        assertThat(ReadEndpoint.values()).allSatisfy(endpoint -> assertThat(outcome.appliesTo(endpoint)).isTrue());
    }

    /** contracts/metrics.md, *Outcome from status*. */
    @ParameterizedTest
    @CsvSource({
        "200, OK", "304, NOT_MODIFIED", "400, BAD_REQUEST", "405, BAD_REQUEST", "406, BAD_REQUEST",
        "415, BAD_REQUEST", "404, NOT_FOUND", "503, UNAVAILABLE", "500, FAILED", "502, FAILED"
    })
    void status_should_map_to_outcome(final int status, final ReadOutcome outcome) {
        assertThat(ReadOutcome.forStatus(status)).isEqualTo(outcome);
    }
}
