package uk.gov.hmcts.cp.resultsstore.domain;

import java.util.List;

/** The outcome of reading a share's key details: extracted, or failed with a bounded reason. */
public sealed interface Projection permits Projection.Extracted, Projection.Failed {

    /** The {@code projection_status} this outcome is stored with. */
    ProjectionStatus status();

    /**
     * The key details were read.
     *
     * @param keyDetails        the key-detail values
     * @param defendants        the defendant index rows, merged per (case, defendant), in payload order
     * @param anySubjectIsYouth TRUE, FALSE, or {@code null} for unknown (FR-026)
     */
    record Extracted(KeyDetails keyDetails, List<DefendantRef> defendants, Boolean anySubjectIsYouth)
            implements Projection {

        public Extracted {
            defendants = List.copyOf(defendants);
        }

        @Override
        public ProjectionStatus status() {
            return ProjectionStatus.OK;
        }
    }

    /**
     * The key details could not be read; every key-detail column stays empty.
     *
     * @param reason {@code <KIND>:<path>} or {@code UNEXPECTED:<class>}; never payload text
     * @param kind   the kind of failure
     */
    record Failed(String reason, ExtractionFailureKind kind) implements Projection {

        @Override
        public ProjectionStatus status() {
            return ProjectionStatus.FAILED;
        }
    }
}
