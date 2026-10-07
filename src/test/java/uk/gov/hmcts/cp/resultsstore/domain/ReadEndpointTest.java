package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("read endpoint tags")
class ReadEndpointTest {

    /** The {@code endpoint} values of contracts/metrics.md; {@code arrived_payload} withdrawn (spec 005). */
    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(ReadEndpoint.values()).map(ReadEndpoint::tag))
                .containsExactly("pull", "search", "share", "payload", "day_versions");
    }
}
