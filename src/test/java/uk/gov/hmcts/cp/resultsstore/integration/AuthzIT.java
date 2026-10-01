package uk.gov.hmcts.cp.resultsstore.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * cp-auth-rules-filter as configured in application.yaml, with usersgroups stubbed. There are no
 * rules yet, so everything outside the excluded prefixes is refused.
 */
@SpringBootTest(properties = "authz.http.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthzIT {

    private static final String IDENTITY_PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions";

    private static final String API_PATH = "/results-store/v1/anything";

    private static final WireMockServer USERSGROUPS =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        USERSGROUPS.start();
        USERSGROUPS.stubFor(WireMock.get(urlPathEqualTo(IDENTITY_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {"groups": [{"groupId": "grp-1", "groupName": "System Users",
                                  "prosecutingAuthority": null}],
                                 "switchableRoles": [], "permissions": []}""")));
    }

    @Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void identityUrl(final DynamicPropertyRegistry registry) {
        registry.add("authz.http.identity-url-template",
                () -> "http://localhost:" + USERSGROUPS.port() + IDENTITY_PATH);
    }

    @Test
    void actuator_should_be_reachable_without_an_identity() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void the_api_should_refuse_a_caller_with_no_identity() throws Exception {
        mockMvc.perform(get(API_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_api_should_refuse_a_known_caller_because_no_rule_allows_anything() throws Exception {
        mockMvc.perform(get(API_PATH)
                        .header("CJSCPPUID", "7a0c5b8e-1d2f-4e3a-9b6c-0d1e2f3a4b5c")
                        .header("CPP-ACTION", "results-store.anything"))
                .andExpect(status().isForbidden());
    }
}
