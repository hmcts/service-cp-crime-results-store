package uk.gov.hmcts.cp.resultsstore.config;

import java.time.Duration;

/**
 * The pull's effective visibility lag (specs/003-read-api research R4, R6): the set
 * {@code resultsstore.read.pull.visibility-lag}, or transaction + 2 × statement + idle-in-transaction of
 * {@code resultsstore.intake.store.*} when it is unset. The read service holds pulls back by it, and intake
 * counts a store transaction that outlived it.
 *
 * @param value the lag
 */
public record VisibilityLag(Duration value) {
}
