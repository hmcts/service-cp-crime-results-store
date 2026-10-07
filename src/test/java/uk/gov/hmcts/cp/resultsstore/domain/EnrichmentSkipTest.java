package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("enrichment skip tags")
class EnrichmentSkipTest {

    @ParameterizedTest
    @CsvSource({
        "DISABLED, disabled",
        "ALREADY_STORED, already_stored"
    })
    void reason_should_have_its_lower_case_tag(final EnrichmentSkip reason, final String tag) {
        assertThat(reason.tag()).isEqualTo(tag);
    }

    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(EnrichmentSkip.values()).map(EnrichmentSkip::tag))
                .containsExactly("disabled", "already_stored");
    }
}
