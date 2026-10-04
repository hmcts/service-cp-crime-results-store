package uk.gov.hmcts.cp.resultsstore.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/**
 * {@code resultsstore.read.refused{reason}} on Micrometer (contracts/metrics.md): the only record of a
 * request refused before the audit filter. Every reason is registered at start, so a dashboard shows a zero
 * rather than no series; the tag values are {@link RouteRefusal#tag()}s.
 */
public class MicrometerRefusalObserver implements RefusalObserver {

    private static final String REFUSED_METER = "resultsstore.read.refused";

    private static final String REASON = "reason";

    private final MeterRegistry registry;

    /**
     * Creates the observer and registers the counter for every reason.
     *
     * @param registry the application's registry
     */
    public MicrometerRefusalObserver(final MeterRegistry registry) {
        this.registry = registry;
        Arrays.stream(RouteRefusal.values()).forEach(this::counter);
    }

    @Override
    public void refused(final RouteRefusal reason) {
        counter(reason).increment();
    }

    private Counter counter(final RouteRefusal reason) {
        return registry.counter(REFUSED_METER, REASON, reason.tag());
    }
}
