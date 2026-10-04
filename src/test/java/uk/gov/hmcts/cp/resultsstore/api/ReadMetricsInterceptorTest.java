package uk.gov.hmcts.cp.resultsstore.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.context.request.ServletWebRequest;
import uk.gov.hmcts.cp.resultsstore.application.ReadObserver;
import uk.gov.hmcts.cp.resultsstore.domain.ReadEndpoint;
import uk.gov.hmcts.cp.resultsstore.domain.ReadOutcome;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;

/** {@code resultsstore.read.requests} and {@code duration} recorded when a request completes (contracts/metrics.md). */
@DisplayName("read metrics interceptor")
class ReadMetricsInterceptorTest {

    private final ReadObserver observer = mock(ReadObserver.class);

    private final AtomicLong clock = new AtomicLong(1_000L);

    private final ReadMetricsInterceptor interceptor = new ReadMetricsInterceptor(observer, clock::get);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/results-store/v1/shares");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private void complete(final ApiRoute route, final int status) {
        if (route != null) {
            request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, route);
        }
        interceptor.preHandle(request, response, new Object());
        clock.addAndGet(2_500_000L);
        response.setStatus(status);
        interceptor.afterCompletion(request, response, new Object(), null);
    }

    @ParameterizedTest
    @CsvSource({
        "200, OK", "304, NOT_MODIFIED", "400, BAD_REQUEST", "405, BAD_REQUEST", "406, BAD_REQUEST",
        "415, BAD_REQUEST", "404, NOT_FOUND", "503, UNAVAILABLE", "500, FAILED"
    })
    void status_should_map_to_outcome(final int status, final ReadOutcome outcome) {
        complete(ApiRoute.GET_SHARE_PAYLOAD, status);

        verify(observer).request(ReadEndpoint.PAYLOAD, outcome, Duration.ofNanos(2_500_000L));
    }

    @Test
    void the_endpoint_should_come_from_the_route_attribute() {
        complete(ApiRoute.SEARCH_SHARES, 200);

        verify(observer).request(ReadEndpoint.SEARCH, ReadOutcome.OK, Duration.ofNanos(2_500_000L));
    }

    @Test
    void a_request_with_no_route_attribute_should_record_nothing() {
        complete(null, 404);

        verifyNoInteractions(observer);
    }

    @Test
    void a_request_whose_handler_never_started_should_record_nothing() {
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.GET_SHARE);
        response.setStatus(500);

        interceptor.afterCompletion(request, response, new Object(), null);

        verifyNoInteractions(observer);
    }

    @Test
    void a_parameter_refusal_should_be_counted_as_bad_request() throws Exception {
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.PULL_SHARES);
        request.setQueryString("storedAfterSeq=0&limit=0");
        interceptor.preHandle(request, response, new Object());
        new ShareParametersInterceptor().preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(observer).request(ReadEndpoint.PULL, ReadOutcome.BAD_REQUEST, Duration.ZERO);
    }

    @Test
    void a_request_the_advice_already_counted_should_not_be_counted_again() throws Exception {
        request.setAttribute(ApiRoute.REQUEST_ATTRIBUTE, ApiRoute.GET_SHARE);
        new ReadApiExceptionHandler(observer).handleException(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                new ServletWebRequest(request, response));
        response.setStatus(406);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        verify(observer).requestWithoutHandler(ReadEndpoint.SHARE, ReadOutcome.BAD_REQUEST);
        verify(observer, never()).request(any(), any(), any());
    }
}
