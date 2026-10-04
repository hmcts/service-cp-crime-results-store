package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("application lookup outcome tags")
class ApplicationLookupOutcomeTest {

    @ParameterizedTest
    @CsvSource({
        "ENRICHED, enriched",
        "NOT_FOUND, not_found",
        "NOT_FINALISED, not_finalised",
        "NO_RESULTS, no_results",
        "INVALID_ID, invalid_id"
    })
    void outcome_should_have_its_lower_case_tag(final ApplicationLookupOutcome outcome, final String tag) {
        assertThat(outcome.tag()).isEqualTo(tag);
    }

    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(ApplicationLookupOutcome.values()).map(ApplicationLookupOutcome::tag))
                .containsExactly("enriched", "not_found", "not_finalised", "no_results", "invalid_id");
    }
}
