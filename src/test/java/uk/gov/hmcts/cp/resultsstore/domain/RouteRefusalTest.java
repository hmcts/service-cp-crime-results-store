package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("route refusal tags")
class RouteRefusalTest {

    /** The {@code reason} values of contracts/metrics.md raised by this service's own filters. */
    @Test
    void every_tag_should_come_from_the_fixed_list() {
        assertThat(Arrays.stream(RouteRefusal.values()).map(RouteRefusal::tag))
                .containsExactly("route_not_found", "method_not_allowed", "unsupported_content_type");
    }
}
