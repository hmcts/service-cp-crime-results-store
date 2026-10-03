package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import uk.gov.hmcts.cp.resultsstore.application.ApplicationAnswer;
import uk.gov.hmcts.cp.resultsstore.application.ProgressionApplications;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;

/**
 * Progression's application-only query over a {@link RestClient} (research R11 to R15;
 * specs/002-enrichment/contracts/progression-lookup.md).
 *
 * <p>The status and the body are classified in one place, inside {@code exchange}, so Spring's
 * default status handlers (whose messages carry the body) never run. Every failure throws a
 * {@link RetryableIntakeException} at {@link IntakeStage#ENRICH} with no chained cause: a Jackson
 * message quotes the body. One request per call; nothing is retried here.
 *
 * <p>Logs hold the application id, the cause and what failed (an exception's class name, a status, or
 * the JSON node type); never the body, any part of it, or the system user id.
 */
public class ProgressionApplicationClient implements ProgressionApplications {

    private static final Logger LOG = LoggerFactory.getLogger(ProgressionApplicationClient.class);

    /** Progression's contract, not an environment's choice. */
    private static final String PATH =
            "/progression-query-api/query/api/rest/progression/applications/{applicationId}";

    /** Selects the action {@code progression.query.application-only}. */
    private static final String MEDIA_TYPE = "application/vnd.progression.query.application-only+json";

    /** The estate's identity header. */
    private static final String USER_HEADER = "CJSCPPUID";

    private static final String COURT_APPLICATION = "courtApplication";

    private static final String JUDICIAL_RESULTS = "judicialResults";

    private final RestClient restClient;

    private final String systemUserId;

    private final Duration responseDeadline;

    private final ObjectReader reader;

    /**
     * Creates the client.
     *
     * @param restClient       a client with progression's base URL, over {@link NoRedirectRequestFactory}
     * @param systemUserId     the store's own system user, sent as {@code CJSCPPUID}
     * @param responseDeadline the longest a whole response may take, from sending the request
     * @param mapper           the application's mapper; the reader is derived from it, never changing it
     */
    public ProgressionApplicationClient(final RestClient restClient, final String systemUserId,
            final Duration responseDeadline, final ObjectMapper mapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.responseDeadline = responseDeadline;
        this.reader = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    @Override
    public ApplicationAnswer find(final UUID applicationId) {
        final ApplicationAnswer answer;
        try {
            answer = exchange(applicationId);
        } catch (RetryableIntakeException e) {
            // Logged once the exchange has settled: the response is closed and its connection gone.
            LOG.warn("Progression lookup for application {} failed: {} ({})", applicationId,
                    e.getFailureCause().tag(), e.getFailedClassName().orElse(""));
            throw e;
        }
        LOG.debug("Progression lookup for application {} answered {}", applicationId,
                answer.getClass().getSimpleName());
        return answer;
    }

    private ApplicationAnswer exchange(final UUID applicationId) {
        final long deadline = System.nanoTime() + responseDeadline.toNanos();
        final ApplicationAnswer answer;
        try {
            answer = restClient.get()
                    .uri(PATH, applicationId)
                    .header(HttpHeaders.ACCEPT, MEDIA_TYPE)
                    .header(USER_HEADER, systemUserId)
                    .exchangeForRequiredValue((request, response) ->
                            classify(ProgressionExchange.exchangeOf(request), response, deadline));
        } catch (ResourceAccessException e) {
            // An I/O failure before the status line and headers were read: Spring wraps it, with the URL
            // in the message. Past the deadline it is the request factory's cancellation, or a timeout.
            final Throwable io = Objects.requireNonNullElse(e.getCause(), e);
            final IntakeFailureCause cause = timedOut(io, deadline)
                    ? IntakeFailureCause.PROGRESSION_TIMEOUT : IntakeFailureCause.PROGRESSION_UNREACHABLE;
            throw failure(cause, io.getClass().getSimpleName());
        }
        return answer;
    }

    private ApplicationAnswer classify(final ProgressionExchange exchange,
            final ClientHttpResponse response, final long deadline) throws IOException {
        final HttpStatusCode status = response.getStatusCode();
        if (!status.isSameCodeAs(HttpStatus.OK)) {
            // The status decides it: the body is never read, and aborting stops closing the response
            // from draining it, however slowly it comes.
            exchange.abort();
            throw failure(causeOf(status), "status " + status.value());
        }
        return shapeOf(parse(body(response, deadline)));
    }

    private static IntakeFailureCause causeOf(final HttpStatusCode status) {
        final IntakeFailureCause cause;
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED) || status.isSameCodeAs(HttpStatus.FORBIDDEN)) {
            cause = IntakeFailureCause.PROGRESSION_REFUSED;
        } else if (status.is5xxServerError() || status.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            cause = IntakeFailureCause.PROGRESSION_UNAVAILABLE;
        } else {
            cause = IntakeFailureCause.PROGRESSION_REJECTED;
        }
        return cause;
    }

    private static byte[] body(final ClientHttpResponse response, final long deadline) {
        final byte[] body;
        try (InputStream stream = new DeadlineInputStream(response.getBody(), deadline, System::nanoTime)) {
            body = stream.readAllBytes();
        } catch (IOException e) {
            // The status line was read, so short of a timeout or the deadline the body was cut short.
            final IntakeFailureCause cause = timedOut(e, deadline)
                    ? IntakeFailureCause.PROGRESSION_TIMEOUT : IntakeFailureCause.PROGRESSION_MALFORMED;
            throw failure(cause, e.getClass().getSimpleName());
        }
        return body;
    }

    /** A socket timeout, or any I/O failure once the deadline has passed (the factory cancelled it). */
    private static boolean timedOut(final Throwable io, final long deadline) {
        return io instanceof SocketTimeoutException || System.nanoTime() - deadline >= 0;
    }

    private JsonNode parse(final byte[] body) {
        final JsonNode root;
        try {
            root = reader.readTree(body);
        } catch (JacksonException e) {
            throw failure(IntakeFailureCause.PROGRESSION_MALFORMED, e.getClass().getSimpleName());
        }
        return root;
    }

    private static ApplicationAnswer shapeOf(final JsonNode root) {
        // An empty body reads as a MissingNode, never null.
        if (!root.isObject()) {
            throw malformed(root);
        }
        final JsonNode application = root.get(COURT_APPLICATION);
        final ApplicationAnswer answer;
        if (application == null || application.isNull()) {
            answer = new ApplicationAnswer.NotFound();
        } else if (application.isObject()) {
            final JsonNode results = application.get(JUDICIAL_RESULTS);
            if (results != null && !results.isNull() && !results.isArray()) {
                throw malformed(results);
            }
            answer = new ApplicationAnswer.Found(application);
        } else {
            throw malformed(application);
        }
        return answer;
    }

    private static RetryableIntakeException malformed(final JsonNode node) {
        return failure(IntakeFailureCause.PROGRESSION_MALFORMED, node.getClass().getSimpleName());
    }

    private static RetryableIntakeException failure(final IntakeFailureCause cause, final String failed) {
        return new RetryableIntakeException(IntakeStage.ENRICH, cause, failed);
    }
}
