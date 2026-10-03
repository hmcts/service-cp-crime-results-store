package uk.gov.hmcts.cp.resultsstore.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;

/**
 * The read meters on Micrometer (contracts/metrics.md): {@code resultsstore.read.requests{endpoint,outcome}},
 * {@code resultsstore.read.duration{endpoint}}, {@code resultsstore.read.page.items} and
 * {@code resultsstore.read.payload.bytes}. Every meter and tag combination is registered when the observer is
 * built, so a dashboard shows a zero rather than no series; a pair that is not registered is refused rather
 * than creating one.
 */
public class MicrometerReadObserver implements ReadObserver {

    private static final String REQUESTS = "resultsstore.read.requests";

    private static final String DURATION = "resultsstore.read.duration";

    private static final String ENDPOINT = "endpoint";

    private static final String OUTCOME = "outcome";

    private final Map<ReadEndpoint, Map<ReadOutcome, Counter>> requests = new EnumMap<>(ReadEndpoint.class);

    private final Map<ReadEndpoint, Timer> durations = new EnumMap<>(ReadEndpoint.class);

    private final DistributionSummary pageItemsSummary;

    private final DistributionSummary payloadBytesSummary;

    /**
     * Creates the observer and registers every meter and tag combination.
     *
     * @param registry the application's registry
     */
    public MicrometerReadObserver(final MeterRegistry registry) {
        for (final ReadEndpoint endpoint : ReadEndpoint.values()) {
            requests.put(endpoint, counters(registry, endpoint));
            durations.put(endpoint, registry.timer(DURATION, ENDPOINT, endpoint.tag()));
        }
        pageItemsSummary = registry.summary("resultsstore.read.page.items");
        payloadBytesSummary = registry.summary("resultsstore.read.payload.bytes");
    }

    /** The endpoint's request counters, one per outcome that applies to it. */
    private static Map<ReadOutcome, Counter> counters(final MeterRegistry registry, final ReadEndpoint endpoint) {
        final Map<ReadOutcome, Counter> byOutcome = new EnumMap<>(ReadOutcome.class);
        for (final ReadOutcome outcome : ReadOutcome.values()) {
            if (outcome.appliesTo(endpoint)) {
                byOutcome.put(outcome, registry.counter(REQUESTS, ENDPOINT, endpoint.tag(), OUTCOME, outcome.tag()));
            }
        }
        return byOutcome;
    }

    @Override
    public void request(final ReadEndpoint endpoint, final ReadOutcome outcome, final Duration duration) {
        final Counter counter = requests.get(endpoint).get(outcome);
        if (counter == null) {
            throw new IllegalArgumentException(outcome.tag() + " is not an outcome of " + endpoint.tag());
        }
        counter.increment();
        durations.get(endpoint).record(duration);
    }

    @Override
    public void pageItems(final int items) {
        pageItemsSummary.record(items);
    }

    @Override
    public void payloadBytes(final long bytes) {
        payloadBytesSummary.record(bytes);
    }
}
