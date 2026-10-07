package uk.gov.hmcts.cp.resultsstore.filters;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gov.hmcts.cp.filter.audit.model.AuditPayload;
import uk.gov.hmcts.cp.filter.audit.model.ResponseInfo;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.resultsstore.config.ApiWebConfig;

/** D-AUDIT option 4 (E1, FR-052): the payload routes' response body is replaced by a fixed marker in the event. */
@DisplayName("payload-body-free audit payload generation")
class PayloadBodyFreeAuditPayloadGenerationServiceTest {

    private static final String BODY = "{\"hearing\":{\"id\":\"x\",\"note\":\"zz-payload-zz\"},\"isReshare\":false}";

    private static final String USER = "7a0c5b8e-1d2f-4e3a-9b6c-0d1e2f3a4b5c";

    /** As the library's own {@code auditObjectMapper}. */
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module());

    private final PayloadBodyFreeAuditPayloadGenerationService service =
            new PayloadBodyFreeAuditPayloadGenerationService(mapper);

    private final AuditPayloadGenerationService library = new AuditPayloadGenerationService(mapper);

    private static ResponseInfo response(final String headerName, final String action) {
        return new ResponseInfo("", Map.of("CJSCPPUID", USER, headerName, action), BODY);
    }

    @ParameterizedTest
    @EnumSource(value = ApiRoute.class, names = "GET_SHARE_PAYLOAD")
    void the_payload_routes_should_be_replaced_by_the_marker(final ApiRoute route) {
        for (final String headerName : new String[] {"CPP-ACTION", "cpp-action"}) {
            final AuditPayload payload = service.generatePayload(response(headerName, route.action()));

            assertThat(payload.content().path("payloadOmitted").asBoolean()).isTrue();
            assertThat(payload.content().toString()).doesNotContain("zz-payload-zz").doesNotContain("hearing");
            assertThat(payload._metadata().context()).hasValueSatisfying(context ->
                    assertThat(context.user()).isEqualTo(USER));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ApiRoute.class, names = "GET_SHARE_PAYLOAD",
            mode = EnumSource.Mode.EXCLUDE)
    void every_other_route_should_be_left_to_the_library(final ApiRoute route) {
        final ObjectNode ours = service.generatePayload(response("CPP-ACTION", route.action())).content();
        final ObjectNode theirs = library.generatePayload(response("CPP-ACTION", route.action())).content();

        ours.remove("_metadata");
        theirs.remove("_metadata");
        assertThat(ours).isEqualTo(theirs);
        assertThat(ours.get("hearing").get("note").asText()).isEqualTo("zz-payload-zz");
    }

    @Test
    void a_response_with_no_headers_should_be_left_to_the_library() {
        final ObjectNode ours = service.generatePayload(new ResponseInfo("", null, BODY)).content();

        assertThat(ours.has("hearing")).isTrue();
    }

    @Test
    void the_bean_should_exist_only_with_cp_audit_enabled() {
        final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ApiWebConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("auditObjectMapper", ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("authz.http.enabled=true");

        runner.withPropertyValues("cp.audit.enabled=true").run(context -> assertThat(context).hasNotFailed()
                .getBean(AuditPayloadGenerationService.class)
                .isInstanceOf(PayloadBodyFreeAuditPayloadGenerationService.class));
        runner.withPropertyValues("cp.audit.enabled=false").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(AuditPayloadGenerationService.class));
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AuditPayloadGenerationService.class));
    }
}
