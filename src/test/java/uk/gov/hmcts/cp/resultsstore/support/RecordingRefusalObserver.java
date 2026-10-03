package uk.gov.hmcts.cp.resultsstore.support;

import java.util.ArrayList;
import java.util.List;
import uk.gov.hmcts.cp.resultsstore.application.RefusalObserver;
import uk.gov.hmcts.cp.resultsstore.domain.RouteRefusal;

/** Records every refusal it is told about, in order. */
public final class RecordingRefusalObserver implements RefusalObserver {

    private final List<RouteRefusal> recorded = new ArrayList<>();

    @Override
    public void refused(final RouteRefusal reason) {
        recorded.add(reason);
    }

    /** The refusals recorded so far. */
    public List<RouteRefusal> refusals() {
        return List.copyOf(recorded);
    }
}
