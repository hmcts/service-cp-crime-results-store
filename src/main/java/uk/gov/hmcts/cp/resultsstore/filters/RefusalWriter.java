package uk.gov.hmcts.cp.resultsstore.filters;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;

/**
 * Writes a filter's refusal as the four-field problem body (contracts/read-api.md §6), without Spring MVC
 * and without {@code sendError}, so the refusal never reaches {@code /error}. Every value in the body comes
 * from {@link ProblemReason}: never a path or anything else the caller sent.
 */
public final class RefusalWriter {

    /** The problem body's media type. */
    public static final String PROBLEM_JSON = "application/problem+json";

    private static final String BODY = "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"reason\":\"%s\"}";

    private RefusalWriter() {
        // Static functions only.
    }

    /**
     * Sets the reason's status, writes its body and flushes it, so a client that has gone shows here as an
     * {@link IOException} rather than after the caller has counted the refusal.
     *
     * @param response the response, not yet committed
     * @param reason the bounded reason
     * @throws IOException when the body cannot be written
     */
    public static void write(final HttpServletResponse response, final ProblemReason reason) throws IOException {
        final byte[] body = String.format(Locale.ROOT, BODY, HttpStatus.valueOf(reason.status()).getReasonPhrase(),
                reason.status(), reason.code()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(reason.status());
        response.setContentType(PROBLEM_JSON);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
