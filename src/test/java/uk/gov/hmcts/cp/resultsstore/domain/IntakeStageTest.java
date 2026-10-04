package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("intake stage tags")
class IntakeStageTest {

    @ParameterizedTest
    @CsvSource({"RECEIPT, receipt", "STORE, store", "ENRICH, enrich"})
    void stage_should_have_its_lower_case_tag(final IntakeStage stage, final String tag) {
        assertThat(stage.tag()).isEqualTo(tag);
    }

    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(IntakeStage.values()).map(IntakeStage::tag))
                .containsExactly("receipt", "store", "enrich");
    }
}
