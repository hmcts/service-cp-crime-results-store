package uk.gov.hmcts.cp.resultsstore.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.ResponseInfo;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;

/**
 * The audit library's event builder, with the payload endpoints' response body replaced by a fixed marker
 * (D-AUDIT option 4, E1; FR-052; research R14): the response event of a payload request carries
 * {@code {"payloadOmitted":true}} in place of the hearing-resulted payload, so no special-category or youth data is
 * copied to the audit store. Every other event, the request events included, is the library's own.
 *
 * <p>The route is told by the request's {@code CPP-ACTION} as the library hands it over in
 * {@link ResponseInfo#headers()}: the action filter derives it from method and path and overwrites whatever the
 * caller sent, so it names the route served. {@link ResponseInfo#contextPath()} cannot be used: the library fills
 * it with the servlet context path, which is empty for this service ({@code AuditIT} pins both facts, so a library
 * upgrade that changes them fails the build). To be deleted when the library offers a body-exclusion switch
 * (option 2, asked for in parallel).
 */
public class PayloadBodyFreeAuditPayloadGenerationService extends AuditPayloadGenerationService {

    /** What the payload endpoints' response event carries in place of the body. */
    public static final String MARKER = "{\"payloadOmitted\":true}";

    /** The actions whose response body is the payload. */
    private static final Set<String> PAYLOAD_ACTIONS = Set.of(ApiRoute.GET_SHARE_PAYLOAD.action());

    /**
     * Creates the builder.
     *
     * @param objectMapper the audit library's own mapper
     */
    public PayloadBodyFreeAuditPayloadGenerationService(final ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public AuditPayload generatePayload(final ResponseInfo responseInfo) {
        return super.generatePayload(isPayloadRoute(responseInfo.headers())
                ? new ResponseInfo(responseInfo.contextPath(), responseInfo.headers(), MARKER)
                : responseInfo);
    }

    private static boolean isPayloadRoute(final Map<String, String> headers) {
        return headers != null && headers.entrySet().stream()
                .anyMatch(header -> ActionRequestWrapper.ACTION_HEADER.equalsIgnoreCase(header.getKey())
                        && PAYLOAD_ACTIONS.contains(header.getValue()));
    }
}
