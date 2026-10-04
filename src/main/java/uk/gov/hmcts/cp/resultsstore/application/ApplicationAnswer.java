package uk.gov.hmcts.cp.resultsstore.application;

import tools.jackson.databind.JsonNode;

/** Progression's answer for one application id, once the adapter has checked its shape. */
public sealed interface ApplicationAnswer {

    /**
     * Progression has the application. Whether it is finalised and has results is the enricher's
     * reading, not the adapter's.
     *
     * @param courtApplication the {@code courtApplication} object; its {@code judicialResults}, when
     *                         present and not {@code null}, is an array
     */
    record Found(JsonNode courtApplication) implements ApplicationAnswer {
    }

    /** Progression answered {@code 200 {}}, or with {@code courtApplication} missing or {@code null}. */
    record NotFound() implements ApplicationAnswer {
    }
}
