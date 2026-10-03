package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;

/** The read meters (contracts/metrics.md). */
@DisplayName("the read metrics")
class MicrometerReadObserverTest {

    private static final String REQUESTS = "resultsstore.read.requests";

    private static final String DURATION = "resultsstore.read.duration";

    private static final String PAGE_ITEMS = "resultsstore.read.page.items";

    private static final String PAYLOAD_BYTES = "resultsstore.read.payload.bytes";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final MicrometerReadObserver observer = new MicrometerReadObserver(registry);

    @Test
    void every_meter_and_tag_combination_should_be_registered_at_start() {
        final List<String> expectedPairs = new ArrayList<>();
        for (final ReadEndpoint endpoint : ReadEndpoint.values()) {
            for (final ReadOutcome outcome : ReadOutcome.values()) {
                if (outcome != ReadOutcome.NOT_MODIFIED || endpoint == ReadEndpoint.PAYLOAD) {
                    expectedPairs.add(endpoint.tag() + "/" + outcome.tag());
                }
            }
        }

        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName()).containsOnly(REQUESTS,
                DURATION, PAGE_ITEMS, PAYLOAD_BYTES);
        assertThat(registry.find(REQUESTS).counters())
                .extracting(counter -> counter.getId().getTag("endpoint") + "/" + counter.getId().getTag("outcome"))
                .containsExactlyInAnyOrderElementsOf(expectedPairs);
        assertThat(registry.find(REQUESTS).counters()).allSatisfy(counter -> {
            assertThat(counter.count()).isZero();
            assertThat(counter.getId().getTags()).hasSize(2);
        });
        assertThat(registry.find(DURATION).timers())
                .extracting(timer -> timer.getId().getTag("endpoint"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ReadEndpoint.values()).map(ReadEndpoint::tag)
                        .toList());
        assertThat(registry.find(DURATION).timers()).allSatisfy(timer -> {
            assertThat(timer.count()).isZero();
            assertThat(timer.getId().getTags()).hasSize(1);
        });
        assertThat(registry.find(PAGE_ITEMS).summaries()).singleElement()
                .satisfies(summary -> assertThat(summary.getId().getTags()).isEmpty());
        assertThat(registry.find(PAYLOAD_BYTES).summaries()).singleElement()
                .satisfies(summary -> assertThat(summary.getId().getTags()).isEmpty());
    }

    @Test
    void each_callback_should_move_exactly_its_meter() {
        observer.request(ReadEndpoint.PAYLOAD, ReadOutcome.NOT_MODIFIED, Duration.ofMillis(40));

        assertThat(registry.find(REQUESTS).counters()).allSatisfy(counter -> assertThat(counter.count())
                .isEqualTo(isPayloadNotModified(counter) ? 1.0 : 0.0));
        assertThat(registry.find(DURATION).timers()).allSatisfy(timer -> {
            final boolean payload = "payload".equals(timer.getId().getTag("endpoint"));
            assertThat(timer.count()).isEqualTo(payload ? 1L : 0L);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(payload ? 40.0 : 0.0);
        });
        assertThat(registry.get(PAGE_ITEMS).summary().count()).isZero();
        assertThat(registry.get(PAYLOAD_BYTES).summary().count()).isZero();

        observer.pageItems(37);
        observer.payloadBytes(39_000L);

        assertThat(registry.get(PAGE_ITEMS).summary().count()).isOne();
        assertThat(registry.get(PAGE_ITEMS).summary().totalAmount()).isEqualTo(37.0);
        assertThat(registry.get(PAYLOAD_BYTES).summary().count()).isOne();
        assertThat(registry.get(PAYLOAD_BYTES).summary().totalAmount()).isEqualTo(39_000.0);
        assertThat(registry.find(REQUESTS).counters()).extracting(Counter::count).containsOnlyOnce(1.0);
    }

    @Test
    void a_pair_that_is_not_registered_should_be_refused_rather_than_create_a_series() {
        final int before = registry.getMeters().size();

        assertThatThrownBy(() -> observer.request(ReadEndpoint.SHARE, ReadOutcome.NOT_MODIFIED, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not_modified is not an outcome of share");
        assertThat(registry.getMeters()).hasSize(before);
    }

    @Test
    void no_tag_value_should_parse_as_a_uuid_or_date() {
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .isNotEmpty()
                .allSatisfy(tag -> {
                    assertThat(parsesAsUuid(tag.getValue())).isFalse();
                    assertThat(parsesAsDate(tag.getValue())).isFalse();
                });
    }

    private static boolean isPayloadNotModified(final Meter counter) {
        return "payload".equals(counter.getId().getTag("endpoint"))
                && "not_modified".equals(counter.getId().getTag("outcome"));
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
