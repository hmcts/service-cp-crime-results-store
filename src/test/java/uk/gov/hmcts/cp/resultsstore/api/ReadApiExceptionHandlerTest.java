package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.gov.hmcts.cp.resultsstore.application.BadParameterException;
import uk.gov.hmcts.cp.resultsstore.application.NotFoundException;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.domain.EnvelopeMetadata;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.openapi.api.SharesApi;
import uk.gov.hmcts.cp.resultsstore.openapi.model.ProblemDetail;
import uk.gov.hmcts.cp.resultsstore.support.CapturedLog;

/** Every error as the four fields and nothing else (FR-042 to FR-044; research R13). */
@DisplayName("read API exception handler")
class ReadApiExceptionHandlerTest {

    private static final String SECRET = "zz-secret-message-zz";

    private final ReadObserver observer = mock(ReadObserver.class);

    private final ReadApiExceptionHandler handler = new ReadApiExceptionHandler(observer);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/results-store/v1/shares");

    private static void assertProblem(final ResponseEntity<Object> response, final int status, final String reason) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isInstanceOfSatisfying(ProblemDetail.class, body -> {
            assertThat(body.getType()).isEqualTo(ProblemDetail.TypeEnum.ABOUT_BLANK);
            assertThat(body.getStatus()).isEqualTo(status);
            assertThat(body.getReason().getValue()).isEqualTo(reason);
            assertThat(body.toString()).doesNotContain(SECRET);
        });
    }

    static Stream<Arguments> springExceptions() {
        return Stream.of(
                Arguments.of(new HttpRequestMethodNotSupportedException("POST", Set.of("GET")), 405,
                        "method_not_allowed"),
                Arguments.of(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), 406,
                        "not_acceptable"),
                Arguments.of(new NoResourceFoundException(HttpMethod.GET, "/results-store/v1/" + SECRET, SECRET),
                        404, "route_not_found"),
                Arguments.of(new MissingServletRequestParameterException(SECRET, "String"), 400, "bad_request"),
                Arguments.of(new HttpMessageNotReadableException(SECRET, new MockHttpInputMessage(new byte[0])), 400,
                        "bad_request"));
    }

    @ParameterizedTest
    @MethodSource("springExceptions")
    void each_spring_mvc_exception_type_should_render_the_four_fields_only(final Exception exception,
            final int status, final String reason) throws Exception {
        final ResponseEntity<Object> response = handler.handleException(exception, new ServletWebRequest(request));

        assertProblem(response, status, reason);
    }

    @Test
    void a_405_should_keep_the_allow_header() throws Exception {
        final ResponseEntity<Object> response = handler.handleException(
                new HttpRequestMethodNotSupportedException("POST", Set.of("GET")), new ServletWebRequest(request));

        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET");
    }

    static Stream<Arguments> mismatches() {
        return Stream.of(Arguments.of("shareId", "invalid_share_id"), Arguments.of("hearingId", "invalid_hearing_id"),
                Arguments.of("hearingDay", "invalid_hearing_day"),
                Arguments.of("storedAfterSeq", "invalid_stored_after_seq"), Arguments.of("limit", "limit_out_of_range"),
                Arguments.of("courtCentreId", "invalid_court_centre_id"),
                Arguments.of("sharedDayFrom", "invalid_shared_day"), Arguments.of("sharedDayTo", "invalid_shared_day"),
                Arguments.of("sharedFrom", "invalid_shared_from"), Arguments.of("sharedTo", "invalid_shared_to"),
                Arguments.of("latestOnly", "invalid_latest_only"), Arguments.of("other", "bad_request"));
    }

    @ParameterizedTest
    @MethodSource("mismatches")
    void a_type_mismatch_should_give_the_parameters_own_reason(final String name, final String reason)
            throws Exception {
        final MethodParameter parameter = new MethodParameter(SharesApi.class.getMethod("getShare", UUID.class), 0);
        final MethodArgumentTypeMismatchException mismatch = new MethodArgumentTypeMismatchException(SECRET,
                UUID.class, name, parameter, new IllegalArgumentException(SECRET));

        assertProblem(handler.handleException(mismatch, new ServletWebRequest(request)), 400, reason);
    }

    @Test
    void our_own_refusals_should_keep_their_reason() {
        assertProblem(handler.badParameter(new BadParameterException(ProblemReason.INVALID_CURSOR)), 400,
                "invalid_cursor");
        assertProblem(handler.notFound(new NotFoundException(ProblemReason.SHARE_NOT_FOUND)), 404, "share_not_found");
    }

    @Test
    void a_connection_failure_or_query_timeout_should_be_503_store_unavailable_with_retry_after_5() {
        for (final RuntimeException failure : List.of(new CannotGetJdbcConnectionException(SECRET),
                new QueryTimeoutException(SECRET))) {
            final ResponseEntity<Object> response = handler.storeUnavailable(failure);
            assertProblem(response, 503, "store_unavailable");
            assertThat(response.getHeaders().get(HttpHeaders.RETRY_AFTER)).containsExactly("5");
        }
    }

    @Test
    void any_other_exception_should_be_500_internal_error_logged_by_class_without_its_message() {
        final String shareId = "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b";
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", shareId));
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            assertProblem(handler.internalError(new IllegalStateException(SECRET), request), 500, "internal_error");
            assertProblem(handler.internalError(new EnvelopeMetadata.UnreadablePayloadException(SECRET), request), 500,
                    "internal_error");

            assertThat(log.events()).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap()).containsEntry("shareId", shareId);
            });
            assertThat(log.events()).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                    "Read request failed: java.lang.IllegalStateException",
                    "Read request failed: " + EnvelopeMetadata.UnreadablePayloadException.class.getName());
        }
    }

    static Stream<Exception> springServerErrors() throws NoSuchMethodException {
        final MethodParameter parameter = new MethodParameter(SharesApi.class.getMethod("getShare", UUID.class), 0);
        return Stream.of(new HttpMessageNotWritableException(SECRET),
                new MissingPathVariableException(SECRET, parameter));
    }

    @ParameterizedTest
    @MethodSource("springServerErrors")
    void a_spring_mvc_5xx_should_be_logged_by_class_with_the_share_id_and_without_its_message(
            final Exception exception) throws Exception {
        final String shareId = "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b";
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", shareId));
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            assertProblem(handler.handleException(exception, new ServletWebRequest(request)), 500, "internal_error");

            assertThat(log.events()).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Read request failed: " + exception.getClass().getName());
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap()).containsEntry("shareId", shareId);
            });
        }
    }

    @Test
    void a_spring_mvc_4xx_should_not_be_logged() throws Exception {
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            handler.handleException(new MissingServletRequestParameterException(SECRET, "String"),
                    new ServletWebRequest(request));

            assertThat(log.events()).isEmpty();
        }
    }

    @Test
    void a_500_on_a_route_naming_no_share_should_be_logged_without_a_share_id() {
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            assertProblem(handler.internalError(new IllegalStateException(SECRET), request), 500, "internal_error");

            assertThat(log.events()).singleElement()
                    .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("shareId"));
        }
    }

    @Test
    void a_share_id_that_is_not_a_string_should_not_reach_the_logging_context() {
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", 42));
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            handler.internalError(new IllegalStateException(SECRET), request);

            assertThat(log.events()).singleElement()
                    .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("shareId"));
        }
    }

    @Test
    void a_share_id_that_is_not_canonical_should_not_reach_the_logging_context() {
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", SECRET));
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            handler.internalError(new IllegalStateException(SECRET), request);

            assertThat(log.events()).singleElement()
                    .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("shareId"));
        }
    }

    @ParameterizedTest
    @MethodSource("springExceptions")
    void a_committed_response_should_be_left_as_it_is_and_the_failure_logged_by_class(final Exception exception)
            throws Exception {
        final String shareId = "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b";
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("shareId", shareId));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setCommitted(true);
        try (CapturedLog log = CapturedLog.forClass(ReadApiExceptionHandler.class)) {
            final ResponseEntity<Object> answer = handler.handleException(exception,
                    new ServletWebRequest(request, response));

            assertThat(answer).isNull();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(response.getContentType()).isNull();
            assertThat(log.events()).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "Read request failed after the response was committed: " + exception.getClass().getName());
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap()).containsEntry("shareId", shareId);
            });
        }
    }

    private static HttpMediaTypeNotAcceptableException notAcceptable() {
        return new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
    }

    @Test
    void a_406_on_a_matched_route_before_any_handler_should_be_counted_once_as_bad_request() throws Exception {
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.GET_SHARE);

        handler.handleException(notAcceptable(), new ServletWebRequest(request));
        handler.handleException(notAcceptable(), new ServletWebRequest(request));

        verify(observer).requestWithoutHandler(ReadEndpoint.SHARE, ReadOutcome.BAD_REQUEST);
        verifyNoMoreInteractions(observer);
    }

    @Test
    void a_failure_on_a_matched_route_before_any_handler_should_be_counted_once_as_failed() {
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.SEARCH_SHARES);

        handler.internalError(new IllegalStateException(SECRET), request);

        verify(observer).requestWithoutHandler(ReadEndpoint.SEARCH, ReadOutcome.FAILED);
        verifyNoMoreInteractions(observer);
    }

    @Test
    void an_error_after_the_handler_started_should_be_left_to_the_interceptor() throws Exception {
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.GET_SHARE);
        new ReadMetricsInterceptor(observer, () -> 0L).preHandle(request, new MockHttpServletResponse(),
                new Object());

        handler.handleException(notAcceptable(), new ServletWebRequest(request));
        handler.internalError(new IllegalStateException(SECRET), request);

        verifyNoInteractions(observer);
    }

    @Test
    void an_error_on_no_matched_route_should_not_be_counted_here() throws Exception {
        handler.handleException(notAcceptable(), new ServletWebRequest(request));
        handler.internalError(new IllegalStateException(SECRET), request);

        verifyNoInteractions(observer);
    }

    @ParameterizedTest
    @EnumSource(ProblemReason.class)
    void every_reason_should_be_a_reason_of_the_generated_model(final ProblemReason reason) {
        assertProblem(ReadApiExceptionHandler.problem(reason, new HttpHeaders()), reason.status(), reason.code());
    }
}
