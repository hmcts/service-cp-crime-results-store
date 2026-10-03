package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/** {@code resultsstore.read.refused} (contracts/metrics.md). */
@DisplayName("the refusal metrics")
class MicrometerRefusalObserverTest {

    private static final String METER = "resultsstore.read.refused";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final MicrometerRefusalObserver observer = new MicrometerRefusalObserver(registry);

    @Test
    void every_reason_should_be_registered_at_start_with_zero() {
        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName()).containsOnly(METER);
        assertThat(registry.find(METER).counters())
                .extracting(counter -> counter.getId().getTag("reason"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(RouteRefusal.values()).map(RouteRefusal::tag)
                        .toList());
        assertThat(registry.find(METER).counters()).allSatisfy(counter -> {
            assertThat(counter.count()).isZero();
            assertThat(counter.getId().getTags()).hasSize(1);
        });
    }

    @ParameterizedTest
    @EnumSource(RouteRefusal.class)
    void a_refusal_should_move_its_own_counter_once(final RouteRefusal reason) {
        observer.refused(reason);

        assertThat(registry.find(METER).counters()).allSatisfy(counter -> assertThat(counter.count())
                .isEqualTo(reason.tag().equals(counter.getId().getTag("reason")) ? 1.0 : 0.0));
    }

    @Test
    void no_tag_value_should_parse_as_a_uuid_or_date() {
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .allSatisfy(tag -> {
                    assertThat(parsesAsUuid(tag.getValue())).isFalse();
                    assertThat(parsesAsDate(tag.getValue())).isFalse();
                });
    }

    private static boolean parsesAsUuid(final String value) {
        boolean parses;
        try {
            UUID.fromString(value);
            parses = true;
        } catch (IllegalArgumentException e) {
            parses = false;
        }
        return parses;
    }

    private static boolean parsesAsDate(final String value) {
        boolean parses;
        try {
            LocalDate.parse(value);
            parses = true;
        } catch (DateTimeParseException e) {
            parses = false;
        }
        return parses;
    }
}
