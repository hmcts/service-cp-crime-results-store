package uk.gov.hmcts.cp.resultsstore.application;

import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;

/** Nothing stored answers the request: {@code 404} with its reason only, never the id asked for. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The refusal's reason. */
    private final ProblemReason problem;

    /**
     * Creates the exception.
     *
     * @param reason the bounded reason; its code is the message
     */
    public NotFoundException(final ProblemReason reason) {
        super(reason.code());
        this.problem = reason;
    }

    /** The refusal's reason. */
    public ProblemReason reason() {
        return problem;
    }
}
