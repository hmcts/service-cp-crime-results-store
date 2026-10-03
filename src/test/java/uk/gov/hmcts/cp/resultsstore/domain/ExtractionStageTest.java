package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("extraction stage tags")
class ExtractionStageTest {

    @ParameterizedTest
    @CsvSource({
        "INTAKE, intake",
        "SWEEP, sweep"
    })
    void stage_should_have_its_lower_case_tag(final ExtractionStage stage, final String tag) {
        assertThat(stage.tag()).isEqualTo(tag);
    }
}
