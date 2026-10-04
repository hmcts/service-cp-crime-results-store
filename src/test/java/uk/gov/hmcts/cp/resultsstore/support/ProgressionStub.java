package uk.gov.hmcts.cp.resultsstore.support;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.UUID;

/**
 * An in-process progression on a dynamic port (research R23). Stubs and request counts are per
 * application id, never global: a late redelivery from an earlier test may still reach a shared stub.
 */
public final class ProgressionStub implements AutoCloseable {

    /** Progression's application-only query, up to the id. */
    public static final String PATH_PREFIX = "/progression-query-api/query/api/rest/progression/applications/";

    /** The media type that selects {@code progression.query.application-only}. */
    public static final String MEDIA_TYPE = "application/vnd.progression.query.application-only+json";

    private final WireMockServer wireMock;

    private ProgressionStub(final WireMockServer wireMock) {
        this.wireMock = wireMock;
    }

    /** Starts a stub on a free port. */
    public static ProgressionStub start() {
        final WireMockServer server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        return new ProgressionStub(server);
    }

    /** The URL to configure as {@code resultsstore.progression.base-url}. */
    public String baseUrl() {
        return wireMock.baseUrl();
    }

    /** The server, for faults and scenarios the helpers here do not cover. */
    public WireMockServer server() {
        return wireMock;
    }

    /** The query's path for one application. */
    public static String pathFor(final UUID applicationId) {
        return PATH_PREFIX + applicationId;
    }

    /** Answers every GET for one application with the response given. */
    public void answer(final UUID applicationId, final ResponseDefinitionBuilder response) {
        wireMock.stubFor(get(urlPathEqualTo(pathFor(applicationId))).willReturn(response));
    }

    /** The requests received for one application. */
    public List<LoggedRequest> requestsFor(final UUID applicationId) {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo(pathFor(applicationId))));
    }

    @Override
    public void close() {
        wireMock.stop();
    }
}
