package uk.gov.hmcts.cp.resultsstore.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.SHARE_ID;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.needsStoredAfterSeq;
import static uk.gov.hmcts.cp.resultsstore.support.ApiRouteSamples.samplePath;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.filters.ActionRequestWrapper;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * cp-auth-rules-filter as configured in application.yaml, behind the action filter, on a real server so
 * the library's {@code sendError} reaches the service's {@code /error} page. usersgroups is stubbed and
 * matched on {@code CJSCPPUID} and its media type: one "System Users" caller and one caller whose only
 * group is "Other Group". No endpoint serves a share yet (phase A), so an admitted caller is let through
 * authorisation and nothing more is asserted of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "authz.http.enabled=true")
@ActiveProfiles("test")
@DisplayName("authorisation of the read API")
class AuthzIT {

    private static final String IDENTITY_PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions";

    private static final String USERSGROUPS_MEDIA_TYPE = "application/vnd.usersgroups.get-logged-in-user-permissions+json";

    private static final String USER_ID_HEADER = "CJSCPPUID";

    private static final String SYSTEM_USER = "7a0c5b8e-1d2f-4e3a-9b6c-0d1e2f3a4b5c";

    private static final String OTHER_GROUP_USER = "8b1d6c9f-2e3a-4f4b-8c7d-1e2f3a4b5c6d";

    private static final String UNMAPPED_CALLER = "9c2e7d0a-3f4b-4a5c-9d8e-2f3a4b5c6d7e";

    private static final String HEAD_CALLER = "a3f8e1b2-4c5d-4e6f-8a9b-0c1d2e3f4a5b";

    private static final String OPTIONS_CALLER = "b4a9f2c3-5d6e-4f7a-9b0c-1d2e3f4a5b6c";

    private static final String TRACE_CALLER = "c5b0a3d4-6e7f-4a8b-8c1d-2e3f4a5b6c7d";

    private static final String REFUSED_METER = "resultsstore.read.refused";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final WireMockServer USERSGROUPS =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        USERSGROUPS.start();
        stubCaller(SYSTEM_USER, "System Users");
        stubCaller(OTHER_GROUP_USER, "Other Group");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Environment environment;

    private static void stubCaller(final String userId, final String group) {
        USERSGROUPS.stubFor(WireMock.get(urlPathEqualTo(IDENTITY_PATH))
                .withHeader(USER_ID_HEADER, equalTo(userId))
                .withHeader("Accept", equalTo(USERSGROUPS_MEDIA_TYPE))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"groups": [{"groupId": "grp-1", "groupName": "%s",
                                  "prosecutingAuthority": null}],
                                 "switchableRoles": [], "permissions": []}""".formatted(group))));
    }

    @DynamicPropertySource
    static void identityUrlAndStore(final DynamicPropertyRegistry registry) {
        registry.add("authz.http.identity-url-template",
                () -> "http://localhost:" + USERSGROUPS.port() + IDENTITY_PATH);
        PostgresTestSupport.register(registry);
    }

    private HttpResponse<String> get(final String path, final Map<String, String> headers)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(final String method, final String path, final Map<String, String> headers)
            throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String pathWithQuery(final ApiRoute route) {
        return samplePath(route) + (needsStoredAfterSeq(route) ? "?storedAfterSeq=0" : "");
    }

    private static void assertBoundedBody(final HttpResponse<String> response, final int status, final String title,
                                          final String reason) throws IOException {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/json"));
        assertThat(response.body()).doesNotContain("results-store/v1").doesNotContain(SHARE_ID);
        final JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(body.get("type").asString()).isEqualTo("about:blank");
        assertThat(body.get("title").asString()).isEqualTo(title);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("reason").asString()).isEqualTo(reason);
    }

    private double refused(final String reason) {
        return meterRegistry.get(REFUSED_METER).tag("reason", reason).counter().count();
    }

    @Test
    void the_api_should_refuse_a_caller_with_no_identity_with_a_bounded_401_body()
            throws IOException, InterruptedException {
        assertBoundedBody(get(samplePath(ApiRoute.GET_SHARE), Map.of()), 401, "Unauthorized", "unauthenticated");
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void the_api_should_refuse_a_caller_in_neither_group_with_a_bounded_403_body(final ApiRoute route)
            throws IOException, InterruptedException {
        assertBoundedBody(get(pathWithQuery(route), Map.of(USER_ID_HEADER, OTHER_GROUP_USER)), 403, "Forbidden",
                "forbidden");
    }

    @Test
    void a_401_with_accept_text_html_should_get_the_four_field_json_body() throws IOException, InterruptedException {
        final HttpResponse<String> response = get(samplePath(ApiRoute.GET_SHARE_PAYLOAD),
                Map.of("Accept", "text/html,application/xhtml+xml;q=0.9"));

        assertBoundedBody(response, 401, "Unauthorized", "unauthenticated");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test
    void a_401_and_a_403_should_each_move_read_refused_once() throws IOException, InterruptedException {
        final double unauthenticated = refused("unauthenticated");
        final double forbidden = refused("forbidden");

        get(samplePath(ApiRoute.GET_SHARE), Map.of());
        assertThat(refused("unauthenticated")).isEqualTo(unauthenticated + 1);
        assertThat(refused("forbidden")).isEqualTo(forbidden);

        get(samplePath(ApiRoute.GET_SHARE), Map.of(USER_ID_HEADER, OTHER_GROUP_USER));
        assertThat(refused("unauthenticated")).isEqualTo(unauthenticated + 1);
        assertThat(refused("forbidden")).isEqualTo(forbidden + 1);
    }

    @Test
    void an_unmapped_path_should_be_refused_404_route_not_found_before_authentication()
            throws IOException, InterruptedException {
        final double routeNotFound = refused("route_not_found");

        final HttpResponse<String> response = get("/results-store/v1/anything",
                Map.of(USER_ID_HEADER, UNMAPPED_CALLER, "CPP-ACTION", "results-store.get-share"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(MAPPER.readTree(response.body()).get("reason").asString()).isEqualTo("route_not_found");
        assertThat(response.body()).doesNotContain("anything");
        assertThat(refused("route_not_found")).isEqualTo(routeNotFound + 1);
        USERSGROUPS.verify(0, getRequestedFor(urlPathEqualTo(IDENTITY_PATH))
                .withHeader(USER_ID_HEADER, equalTo(UNMAPPED_CALLER)));
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void a_system_users_caller_should_pass_authorisation_on_every_mapped_route(final ApiRoute route)
            throws IOException, InterruptedException {
        final HttpResponse<String> response = get(pathWithQuery(route), Map.of(USER_ID_HEADER, SYSTEM_USER,
                "Accept", "application/vnd.results-store.anything+json"));

        assertThat(response.statusCode()).isNotIn(401, 403);
    }

    /**
     * FR-048 (d) in the running chain: the guard runs after authorisation, sees the route the action filter
     * left on the wrapped request, and classifies the {@code Content-Type} as sent even when a vendor token in
     * a parameter was neutralised for the authorisation library.
     */
    @ParameterizedTest
    @ValueSource(strings = {"multipart/form-data; boundary=x",
        "multipart/related; type=\"application/vnd.results-store.anything+json\"; boundary=x"})
    void multipart_from_a_system_users_caller_should_be_refused_415_and_counted(final String contentType)
            throws IOException, InterruptedException {
        final double unsupported = refused("unsupported_content_type");

        final HttpResponse<String> response = get(samplePath(ApiRoute.GET_SHARE),
                Map.of(USER_ID_HEADER, SYSTEM_USER, "Content-Type", contentType));

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(MAPPER.readTree(response.body()).get("reason").asString()).isEqualTo("unsupported_content_type");
        assertThat(refused("unsupported_content_type")).isEqualTo(unsupported + 1);
    }

    @Test
    void multipart_without_an_identity_should_be_401_not_415() throws IOException, InterruptedException {
        final double unsupported = refused("unsupported_content_type");

        final HttpResponse<String> response = get(samplePath(ApiRoute.GET_SHARE),
                Map.of("Content-Type", "multipart/form-data; boundary=x"));

        assertBoundedBody(response, 401, "Unauthorized", "unauthenticated");
        assertThat(refused("unsupported_content_type")).isEqualTo(unsupported);
    }

    /**
     * The action filter tells pull from search by the raw query string alone, so a multipart body is never
     * parsed before authorisation: with no identity the answer is the library's {@code 401}, and usersgroups is
     * never asked.
     */
    @Test
    void a_multipart_get_on_shares_without_an_identity_should_be_401_and_never_reach_usersgroups()
            throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/results-store/v1/shares?storedAfterSeq=0"))
                .header("Content-Type", "multipart/form-data; boundary=x")
                .method("GET", HttpRequest.BodyPublishers.ofString("--x\r\nContent-Disposition: form-data; "
                        + "name=\"storedAfterSeq\"\r\n\r\n0\r\n--x--\r\n"))
                .build();

        final HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertBoundedBody(response, 401, "Unauthorized", "unauthenticated");
        USERSGROUPS.verify(0, getRequestedFor(urlPathEqualTo(IDENTITY_PATH)).withoutHeader(USER_ID_HEADER));
    }

    /**
     * {@code HEAD} and {@code OPTIONS} on a served path, on the running server: refused {@code 405} by the action
     * filter before authorisation, so usersgroups is never asked about the caller. A {@code HEAD} answer
     * carries no body on the wire.
     */
    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS"})
    void head_and_options_on_a_served_path_should_be_refused_405_before_authorisation(final String method)
            throws IOException, InterruptedException {
        final String caller = "HEAD".equals(method) ? HEAD_CALLER : OPTIONS_CALLER;
        final double notAllowed = refused("method_not_allowed");

        final HttpResponse<String> response = send(method, samplePath(ApiRoute.GET_SHARE),
                Map.of(USER_ID_HEADER, caller));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().allValues("Allow")).containsExactly("GET");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.body()).doesNotContain("results-store/v1").doesNotContain(SHARE_ID);
        if ("HEAD".equals(method)) {
            assertThat(response.body()).isEmpty();
        } else {
            final JsonNode body = MAPPER.readTree(response.body());
            assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
            assertThat(body.get("reason").asString()).isEqualTo("method_not_allowed");
        }
        assertThat(refused("method_not_allowed")).isEqualTo(notAllowed + 1);
        USERSGROUPS.verify(0, getRequestedFor(urlPathEqualTo(IDENTITY_PATH)).withHeader(USER_ID_HEADER, equalTo(caller)));
    }

    /**
     * {@code TRACE} never reaches the service's filters: Tomcat ({@code allowTrace=false}, the default) refuses it
     * {@code 405} in its connector and sends the error to {@code /error}, whose bounded page writes the four
     * fields with the generic {@code 4xx} reason. The action filter neither sees nor counts it.
     */
    @Test
    void trace_should_be_refused_405_by_the_connector_and_never_counted() throws IOException, InterruptedException {
        final double notAllowed = refused("method_not_allowed");

        final HttpResponse<String> response = send("TRACE", samplePath(ApiRoute.GET_SHARE),
                Map.of(USER_ID_HEADER, TRACE_CALLER));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.body()).doesNotContain("results-store/v1").doesNotContain(SHARE_ID);
        final JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("type", "title", "status", "reason");
        assertThat(body.get("status").asInt()).isEqualTo(405);
        assertThat(body.get("reason").asString()).isEqualTo("bad_request");
        assertThat(refused("method_not_allowed")).isEqualTo(notAllowed);
        USERSGROUPS.verify(0, getRequestedFor(urlPathEqualTo(IDENTITY_PATH))
                .withHeader(USER_ID_HEADER, equalTo(TRACE_CALLER)));
    }

    /** The library reads the action from a configurable header; the wrapper writes a fixed one. */
    @Test
    void the_configured_action_header_should_be_the_one_the_wrapper_writes() {
        assertThat(environment.getProperty("authz.http.action-header")).isEqualTo(ActionRequestWrapper.ACTION_HEADER);
    }

    @Test
    void actuator_should_be_reachable_without_an_identity() throws IOException, InterruptedException {
        assertThat(get("/actuator/health", Map.of()).statusCode()).isEqualTo(200);
    }
}
