package uk.gov.hmcts.cp.resultsstore.application;

import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;

/** A request parameter the read API refuses: answered {@code 400} with its reason only, never the value. */
public class BadParameterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The refusal's reason. */
    private final ProblemReason problem;

    /**
     * Creates the exception.
     *
     * @param reason the bounded reason; its code is the message
     */
    public BadParameterException(final ProblemReason reason) {
        super(reason.code());
        this.problem = reason;
    }

    /** The refusal's reason. */
    public ProblemReason reason() {
        return problem;
    }
}
