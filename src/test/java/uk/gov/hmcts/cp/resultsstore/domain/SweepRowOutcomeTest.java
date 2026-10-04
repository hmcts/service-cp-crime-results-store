package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("sweep row outcome tags")
class SweepRowOutcomeTest {

    @ParameterizedTest
    @CsvSource({
        "FIXED, fixed",
        "FAILED_AGAIN, failed_again",
        "SKIPPED, skipped",
        "ERROR, error",
        "CANCELLED, cancelled"
    })
    void outcome_should_have_its_lower_case_tag(final SweepRowOutcome outcome, final String tag) {
        assertThat(outcome.tag()).isEqualTo(tag);
    }
}
