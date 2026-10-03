package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("the action request wrapper")
class ActionRequestWrapperTest {

    private static final String VENDOR = "application/vnd.results-store.get-share+json";

    private static MockHttpServletRequest request() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/results-store/v1/shares");
        request.addHeader("cpp-action", "caller.supplied");
        request.addHeader("CJSCPPUID", "user-1");
        return request;
    }

    @Test
    void get_header_get_headers_and_get_header_names_should_agree_on_the_action() {
        final HttpServletRequest routed = ActionRequestWrapper.forRoute(request(), ApiRoute.SEARCH_SHARES);
        final HttpServletRequest bare = ActionRequestWrapper.withoutAction(request());

        assertThat(routed.getHeader("CPP-ACTION")).isEqualTo("results-store.search-shares");
        assertThat(Collections.list(routed.getHeaders("CPP-ACTION"))).containsExactly("results-store.search-shares");
        assertThat(Collections.list(routed.getHeaderNames()).stream()
                .filter("CPP-ACTION"::equalsIgnoreCase)).containsExactly("CPP-ACTION");
        assertThat(routed.getHeader("CJSCPPUID")).isEqualTo("user-1");
        assertThat(Collections.list(routed.getHeaders("CJSCPPUID"))).containsExactly("user-1");
        assertThat(Collections.list(routed.getHeaderNames())).contains("CJSCPPUID");

        assertThat(bare.getHeader("CPP-ACTION")).isNull();
        assertThat(Collections.list(bare.getHeaders("CPP-ACTION"))).isEmpty();
        assertThat(Collections.list(bare.getHeaderNames())).noneMatch("CPP-ACTION"::equalsIgnoreCase)
                .contains("CJSCPPUID");
    }

    @Test
    void header_names_should_be_case_insensitive() {
        final MockHttpServletRequest request = request();
        request.addHeader("Accept", VENDOR);
        request.addHeader("Accept", "text/plain");
        request.setContentType(VENDOR + "; charset=UTF-8");

        final HttpServletRequest routed = ActionRequestWrapper.forRoute(request, ApiRoute.GET_SHARE);

        assertThat(routed.getHeader("cpp-action")).isEqualTo("results-store.get-share");
        assertThat(routed.getHeader("Cpp-Action")).isEqualTo("results-store.get-share");
        assertThat(Collections.list(routed.getHeaders("cPP-aCTION"))).containsExactly("results-store.get-share");
        assertThat(routed.getHeader("accept")).isEqualTo("application/json");
        assertThat(Collections.list(routed.getHeaders("ACCEPT"))).containsExactly("application/json", "text/plain");
        assertThat(routed.getHeader("content-type")).isEqualTo("application/json");
        assertThat(Collections.list(routed.getHeaders("Content-Type"))).containsExactly("application/json");
        assertThat(routed.getContentType()).isEqualTo("application/json");
    }

    @Test
    void a_non_vendor_accept_should_be_left_as_sent() {
        final MockHttpServletRequest request = request();
        request.addHeader("Accept", "text/html, application/json;q=0.9");
        request.setContentType("application/json;charset=UTF-8");

        final HttpServletRequest routed = ActionRequestWrapper.forRoute(request, ApiRoute.GET_SHARE);

        assertThat(routed.getHeader("Accept")).isEqualTo("text/html, application/json;q=0.9");
        assertThat(Collections.list(routed.getHeaders("Accept"))).containsExactly("text/html, application/json;q=0.9");
        assertThat(routed.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(routed.getHeader("Content-Type")).isEqualTo("application/json;charset=UTF-8");
        assertThat(ActionRequestWrapper.forRoute(request(), ApiRoute.GET_SHARE).getHeader("Accept")).isNull();
        assertThat(ActionRequestWrapper.forRoute(request(), ApiRoute.GET_SHARE).getContentType()).isNull();
    }
}
