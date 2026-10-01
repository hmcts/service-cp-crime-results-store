package uk.gov.hmcts.cp.resultsstore.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Derives the CPP-ACTION header that {@code cp-auth-rules-filter} authorises from the request path,
 * and never trusts a caller-supplied one for a mapped path.
 *
 * <p>No paths are mapped yet: the read API arrives with its feature spec, and each endpoint then
 * gets an entry here and an allow rule in {@code acl/results-store-rules.drl}. Unmapped paths pass
 * through unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActionHeaderFilter extends OncePerRequestFilter {

    /* default */ static final String ACTION_HEADER = "CPP-ACTION";

    private final Map<String, String> pathToAction;

    public ActionHeaderFilter() {
        this(Map.of());
    }

    /* default */ ActionHeaderFilter(final Map<String, String> pathToAction) {
        super();
        this.pathToAction = Map.copyOf(pathToAction);
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final String action = pathToAction.get(request.getRequestURI());
        if (action == null) {
            filterChain.doFilter(request, response);
        } else {
            filterChain.doFilter(new ActionHeaderRequestWrapper(request, action), response);
        }
    }

    private static final class ActionHeaderRequestWrapper extends HttpServletRequestWrapper {

        private final String action;

        /* default */ ActionHeaderRequestWrapper(final HttpServletRequest request, final String action) {
            super(request);
            this.action = action;
        }

        @Override
        public String getHeader(final String name) {
            return ACTION_HEADER.equalsIgnoreCase(name) ? action : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(final String name) {
            return ACTION_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(action))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            final List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(ACTION_HEADER::equalsIgnoreCase);
            names.add(ACTION_HEADER);
            return Collections.enumeration(names);
        }
    }
}
