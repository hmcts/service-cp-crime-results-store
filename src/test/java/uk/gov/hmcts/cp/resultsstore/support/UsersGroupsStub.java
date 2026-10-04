package uk.gov.hmcts.cp.resultsstore.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * An in-process usersgroups for the end-to-end suites: the logged-in user's permissions, matched on the
 * {@code CJSCPPUID} header and the library's media type. Three callers: one in "System Users", one in "Second Line
 * Support" (both admitted on every read route) and one whose only group is "Other Group" (refused {@code 403}).
 */
public final class UsersGroupsStub {

    /** The path the authorisation library asks. */
    public static final String IDENTITY_PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions";

    /** The header naming the caller. */
    public static final String USER_ID_HEADER = "CJSCPPUID";

    /** A caller in "System Users". */
    public static final String SYSTEM_USER = "7a0c5b8e-1d2f-4e3a-9b6c-0d1e2f3a4b5c";

    /** A caller in "Second Line Support". */
    public static final String SECOND_LINE_USER = "d6c1b4e5-7f8a-4b9c-9d2e-3f4a5b6c7d8e";

    /** A caller in neither admitted group. */
    public static final String NO_GROUP_USER = "8b1d6c9f-2e3a-4f4b-8c7d-1e2f3a4b5c6d";

    private static final String MEDIA_TYPE = "application/vnd.usersgroups.get-logged-in-user-permissions+json";

    private final WireMockServer server;

    private UsersGroupsStub(final WireMockServer server) {
        this.server = server;
    }

    /**
     * Starts the stub with the three callers.
     *
     * @return the running stub
     */
    public static UsersGroupsStub start() {
        final WireMockServer server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        final UsersGroupsStub stub = new UsersGroupsStub(server);
        stub.caller(SYSTEM_USER, "System Users");
        stub.caller(SECOND_LINE_USER, "Second Line Support");
        stub.caller(NO_GROUP_USER, "Other Group");
        return stub;
    }

    /** The value of {@code authz.http.identity-url-template} that reaches this stub. */
    public String identityUrl() {
        return "http://localhost:" + server.port() + IDENTITY_PATH;
    }

    private void caller(final String userId, final String group) {
        server.stubFor(WireMock.get(urlPathEqualTo(IDENTITY_PATH))
                .withHeader(USER_ID_HEADER, equalTo(userId))
                .withHeader("Accept", equalTo(MEDIA_TYPE))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"groups": [{"groupId": "grp-1", "groupName": "%s",
                                  "prosecutingAuthority": null}],
                                 "switchableRoles": [], "permissions": []}""".formatted(group))));
    }
}
