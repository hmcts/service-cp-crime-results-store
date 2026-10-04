package uk.gov.hmcts.cp.resultsstore.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.resultsstore.support.PostgresTestSupport;

/**
 * Readiness with the subscription switched on and the broker unreachable (nothing listens on port 1): the
 * store gates readiness and the broker never does (application.yaml), so a broker outage leaves every
 * replica in service.
 */
@SpringBootTest(properties = {"resultsstore.publicevents.enabled=true", "spring.artemis.broker-url=tcp://localhost:1",
    "management.endpoint.health.group.readiness.show-components=always"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadinessWithoutBrokerIT {

    @Resource
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void store(final DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Test
    void readiness_should_stay_up_when_the_broker_is_unreachable() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.jms").doesNotExist());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
