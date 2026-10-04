package uk.gov.hmcts.cp.resultsstore.application;

import tools.jackson.databind.JsonNode;

/**
 * What enrichment made of one share's body (specs/002-enrichment research R5, R6).
 *
 * @param tree       the working copy as a tree: the enriched deep copy, or the arrived body itself when
 *                   nothing was added; the key details are read from it
 * @param parsedCopy the text sent to {@code payload_json}: the enriched tree written compact with
 *                   non-ASCII escaped, or the arrived text when nothing was added
 * @param applied    whether at least one application received results
 */
public record Enrichment(JsonNode tree, String parsedCopy, boolean applied) {
}
