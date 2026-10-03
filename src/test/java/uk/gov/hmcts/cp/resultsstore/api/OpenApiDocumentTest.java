package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;

/**
 * {@code results-store-openapi.yaml}: the read API's machine-readable contract, also read at runtime by
 * the audit library to resolve path parameters (contracts/read-api.md, FR-053 document half).
 */
@DisplayName("the OpenAPI document")
class OpenApiDocumentTest {

    private static final String DOCUMENT = "results-store-openapi.yaml";

    private static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    private static SwaggerParseResult result;

    private static OpenAPI api;

    @BeforeAll
    static void parse() throws IOException {
        try (InputStream in = new ClassPathResource(DOCUMENT).getInputStream()) {
            result = new OpenAPIV3Parser().readContents(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        api = result.getOpenAPI();
    }

    private static List<Operation> operations() {
        return api.getPaths().values().stream()
                .flatMap(item -> item.readOperations().stream())
                .toList();
    }

    @Test
    void the_document_should_parse() {
        assertThat(result.getMessages()).isEmpty();
        assertThat(api.getInfo().getVersion()).isEqualTo("0.2.0");
    }

    @Test
    void the_paths_should_be_exactly_the_api_route_templates() {
        assertThat(api.getPaths().keySet())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ApiRoute.values())
                        .map(ApiRoute::template)
                        .collect(Collectors.toSet()));
        assertThat(api.getPaths().values())
                .allSatisfy(item -> assertThat(item.readOperationsMap().keySet())
                        .containsExactly(PathItem.HttpMethod.GET));
    }

    @Test
    void every_templated_path_should_declare_its_path_parameters() {
        assertThat(api.getPaths()).allSatisfy((template, item) -> {
            final Set<String> variables = new HashSet<>();
            final Matcher matcher = VARIABLE.matcher(template);
            while (matcher.find()) {
                variables.add(matcher.group(1));
            }
            final List<Parameter> declared = new ArrayList<>(Optional.ofNullable(item.getParameters()).orElse(List.of()));
            declared.addAll(Optional.ofNullable(item.getGet().getParameters()).orElse(List.of()));
            final List<Parameter> pathParameters = declared.stream().filter(p -> "path".equals(p.getIn())).toList();

            assertThat(pathParameters).extracting(Parameter::getName).containsExactlyInAnyOrderElementsOf(variables);
            assertThat(pathParameters).allSatisfy(p -> assertThat(p.getRequired()).isTrue());
        });
    }

    @Test
    void every_operation_should_declare_the_problem_body_for_4xx_and_5xx() {
        assertThat(operations()).isNotEmpty().allSatisfy(operation -> {
            final Map<String, ApiResponse> errors = operation.getResponses().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("4") || entry.getKey().startsWith("5"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            assertThat(errors.keySet()).anyMatch(code -> code.startsWith("4")).anyMatch(code -> code.startsWith("5"))
                    .contains("401", "403", "404", "500", "503");
            assertThat(errors.values()).allSatisfy(response -> {
                assertThat(response.getContent()).isNotEmpty();
                assertThat(response.getContent().values()).extracting(MediaType::getSchema)
                        .allSatisfy(schema -> assertThat(schema.get$ref()).isEqualTo(PROBLEM_SCHEMA));
            });
        });
        final Schema<?> problem = api.getComponents().getSchemas().get("ProblemDetail");
        final Set<String> fields = problem.getProperties().keySet();
        assertThat(fields).containsExactlyInAnyOrder("type", "title", "status", "reason");
    }

    @Test
    void the_payload_operation_should_declare_the_etag_and_results_store_headers() {
        final Operation payload = api.getPaths().get(ApiRoute.GET_SHARE_PAYLOAD.template()).getGet();

        assertThat(payload.getResponses().get("200").getHeaders().keySet()).containsExactlyInAnyOrder(
                "ETag", "Results-Store-Share-Id", "Results-Store-Hearing-Id", "Results-Store-Hearing-Day",
                "Results-Store-Shared-Time", "Results-Store-Enrichment-Applied", "Results-Store-Payload-Form",
                "Cache-Control", "Content-Length");
        assertThat(payload.getResponses().get("304").getHeaders().keySet()).containsExactly("ETag");
        assertThat(payload.getParameters()).extracting(Parameter::getName).contains("If-None-Match");
        assertThat(api.getComponents().getSchemas().keySet()).contains("ShareSummary", "KeyDetails", "PullPage",
                "SearchPage", "DayVersions", "ProblemDetail");
    }

    /** The audit library finds the document by the suffix glob {@code classpath*:**}{@code /*<name>}. */
    @Test
    void the_audit_glob_should_resolve_exactly_one_document() throws IOException {
        final StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        final String name = environment.getProperty("audit.http.openapi-rest-spec");

        final Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:**/*" + name);

        assertThat(name).isEqualTo(DOCUMENT);
        assertThat(found).hasSize(1);
    }
}
