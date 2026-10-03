package uk.gov.hmcts.cp.resultsstore.integration;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The actuator endpoints, in a context with the store (research R1: every context needs a data source
 * once the read beans are wired unconditionally), so the readiness group carries {@code db} as deployed.
 */
@SpringBootTest(properties = "management.endpoint.health.group.readiness.show-components=always")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorIntegrationTest {

    @Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Test
    void actuator_info_should_have_build_fields() throws Exception {
        final String name = "service-cp-crime-results-store";
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.artifact").value(name))
                .andExpect(jsonPath("$.build.name").value(name))
                .andExpect(jsonPath("$.build.time").exists())
                .andExpect(jsonPath("$.build.version").exists());
    }

    @Test
    void actuator_info_should_have_gorylenko_git_fields() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.branch").exists())
                .andExpect(jsonPath("$.git.commit.id").exists())
                .andExpect(jsonPath("$.git.commit.time").exists());
    }

    @Test
    void actuator_health_should_have_correct_fields() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.groups[0]").value("liveness"))
                .andExpect(jsonPath("$.groups[1]").value("readiness"));
    }

    /** The group names the sweep schedule (a missing contributor would fail the context); UP with the sweep off. */
    @Test
    void liveness_should_be_up_with_the_sweep_schedule_in_its_group() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** The store gates readiness (application.yaml); the broker never does. */
    @Test
    void readiness_should_be_up_with_db_in_its_group() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"));
    }
}
