package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("intake outcome tags")
class IntakeOutcomeTest {

    @ParameterizedTest
    @CsvSource({
        "STORED, stored",
        "DUPLICATE, duplicate",
        "NOT_A_SHARE, not_a_share",
        "ALREADY_SETTLED, already_settled"
    })
    void outcome_should_have_its_lower_case_tag(final IntakeOutcome outcome, final String tag) {
        assertThat(outcome.tag()).isEqualTo(tag);
    }
}
