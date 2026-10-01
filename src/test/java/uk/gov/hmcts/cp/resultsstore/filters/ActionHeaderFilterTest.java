package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActionHeaderFilterTest {

    private static final String PATH = "/results-store/v1/example";

    @Test
    void the_default_filter_should_pass_every_request_through_unchanged() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader("CPP-ACTION", "caller.supplied");

        final HttpServletRequest seen = filter(new ActionHeaderFilter(), request);

        assertThat(seen).isSameAs(request);
    }

    @Test
    void a_mapped_path_should_carry_the_derived_action_whatever_the_caller_sent()
            throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader("CPP-ACTION", "caller.supplied");
        request.addHeader("CJSCPPUID", "user-1");

        final HttpServletRequest seen = filter(new ActionHeaderFilter(Map.of(PATH, "results-store.example")), request);

        assertThat(seen.getHeader("cpp-action")).isEqualTo("results-store.example");
        assertThat(Collections.list(seen.getHeaders("CPP-ACTION"))).containsExactly("results-store.example");
        assertThat(seen.getHeader("CJSCPPUID")).isEqualTo("user-1");
        assertThat(Collections.list(seen.getHeaders("CJSCPPUID"))).containsExactly("user-1");
        assertThat(Collections.list(seen.getHeaderNames()))
                .containsOnlyOnce("CPP-ACTION")
                .contains("CJSCPPUID");
    }

    private static HttpServletRequest filter(final ActionHeaderFilter filter, final MockHttpServletRequest request)
            throws ServletException, IOException {
        final MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }
}
