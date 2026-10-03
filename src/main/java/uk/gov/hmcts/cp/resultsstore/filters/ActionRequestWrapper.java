package uk.gov.hmcts.cp.resultsstore.filters;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A request whose {@code CPP-ACTION} and media types the service, not the caller, decides (research R2).
 *
 * <p>On a mapped route ({@link #forRoute}) {@code getHeader}, {@code getHeaders} and
 * {@code getHeaderNames} always answer the route's action, and {@code getContentType}, {@code Content-Type}
 * and {@code Accept} answer {@code application/json} for any value naming a vendor media type (the whole
 * value is replaced). {@code cp-auth-rules-filter} 1.0.7 resolves the action from a vendor token in
 * {@code Content-Type}, then in {@code Accept}, then {@code CPP-ACTION}, so overwriting the header alone
 * would let a caller pick an action. On {@code /actuator} and {@code /error} ({@link #withoutAction}) the
 * header is removed and the media types are left as sent.
 */
public final class ActionRequestWrapper extends HttpServletRequestWrapper {

    /** The header the authorisation library reads the action from. */
    public static final String ACTION_HEADER = "CPP-ACTION";

    /** The library's own vendor token pattern ({@code RequestActionResolver}). */
    private static final Pattern VENDOR =
            Pattern.compile("(?i)\\bapplication/vnd\\.([a-z0-9][a-z0-9._-]*)(?:\\+[^\\s;,]+)?\\b");

    private static final String JSON = "application/json";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String ACCEPT = "Accept";

    /** The derived action, or null when the header is removed. */
    private final String action;

    private final boolean neutraliseMediaTypes;

    private ActionRequestWrapper(final HttpServletRequest request, final String action,
                                 final boolean neutraliseMediaTypes) {
        super(request);
        this.action = action;
        this.neutraliseMediaTypes = neutraliseMediaTypes;
    }

    /**
     * Wraps a request on a mapped route.
     *
     * @param request the request
     * @param route the route it matched
     * @return the request carrying the route's action, with vendor media types answered as JSON
     */
    public static ActionRequestWrapper forRoute(final HttpServletRequest request, final ApiRoute route) {
        return new ActionRequestWrapper(request, route.action(), true);
    }

    /**
     * Wraps a request that passes through unmapped ({@code /actuator}, {@code /error}).
     *
     * @param request the request
     * @return the request without any {@code CPP-ACTION}, media types untouched
     */
    public static ActionRequestWrapper withoutAction(final HttpServletRequest request) {
        return new ActionRequestWrapper(request, null, false);
    }

    @Override
    public String getHeader(final String name) {
        final String value;
        if (ACTION_HEADER.equalsIgnoreCase(name)) {
            value = action;
        } else if (isNeutralised(name)) {
            value = neutralised(super.getHeader(name));
        } else {
            value = super.getHeader(name);
        }
        return value;
    }

    @Override
    public Enumeration<String> getHeaders(final String name) {
        final Enumeration<String> values;
        if (ACTION_HEADER.equalsIgnoreCase(name)) {
            values = Collections.enumeration(action == null ? List.of() : List.of(action));
        } else if (isNeutralised(name)) {
            values = Collections.enumeration(Collections.list(super.getHeaders(name)).stream()
                    .map(ActionRequestWrapper::neutralised)
                    .toList());
        } else {
            values = super.getHeaders(name);
        }
        return values;
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        final List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
        names.removeIf(ACTION_HEADER::equalsIgnoreCase);
        if (action != null) {
            names.add(ACTION_HEADER);
        }
        return Collections.enumeration(names);
    }

    @Override
    public String getContentType() {
        return neutraliseMediaTypes ? neutralised(super.getContentType()) : super.getContentType();
    }

    private boolean isNeutralised(final String name) {
        return neutraliseMediaTypes && (CONTENT_TYPE.equalsIgnoreCase(name) || ACCEPT.equalsIgnoreCase(name));
    }

    private static String neutralised(final String value) {
        return value != null && VENDOR.matcher(value).find() ? JSON : value;
    }
}
