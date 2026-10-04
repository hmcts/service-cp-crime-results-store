package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The service's own {@code results-store-openapi.yaml} says the same as the contract jar's
 * {@code openapi/openapi-spec.yml} (specs/003-read-api research R23 C1, FR-063): paths, components and tags
 * compared as parsed objects. {@code info} and {@code servers} are not compared: the api repo's CI rewrites the
 * version and adds the contact, the licence and the servers. A failure names the path or component only.
 */
@DisplayName("the OpenAPI document against the contract jar")
class OpenApiContractDriftTest {

    private static final String JAR_SPEC = "openapi/openapi-spec.yml";

    private static final String SERVICE_SPEC = "results-store-openapi.yaml";

    private static String jarText;

    private static OpenAPI jar;

    private static OpenAPI service;

    @BeforeAll
    static void parse() throws IOException {
        jarText = read(new ClassPathResource(JAR_SPEC));
        jar = parse(jarText);
        service = parse(read(new ClassPathResource(SERVICE_SPEC)));
    }

    @Test
    void the_jar_spec_should_be_on_the_classpath_exactly_once() throws IOException {
        final Resource[] found = new PathMatchingResourcePatternResolver().getResources("classpath*:" + JAR_SPEC);

        assertThat(found).hasSize(1);
        assertThat(found[0].getURL().toString()).contains("api-cp-crime-results-store");
    }

    @Test
    void the_paths_should_be_identical() {
        assertThat(differingPaths(service, jar)).as("paths that differ").isEmpty();
        assertThat(service.getPaths()).isNotEmpty();
    }

    @Test
    void the_components_should_be_identical() {
        assertThat(differingComponents(service, jar)).as("components that differ").isEmpty();
        assertThat(service.getComponents().getSchemas()).isNotEmpty();
    }

    @Test
    void the_tags_should_be_identical() {
        assertThat(service.getTags()).isEqualTo(jar.getTags());
    }

    @Test
    void info_and_servers_should_not_be_compared() {
        final OpenAPI other = parse(jarText);
        other.setInfo(new Info().title("another title").version("9.9.9"));
        other.setServers(List.of(new Server().url("https://elsewhere.example")));

        assertThat(differences(other, jar)).isEmpty();
    }

    @Test
    void a_changed_schema_should_fail_naming_the_path_or_component_only() {
        final OpenAPI changed = parse(jarText);
        final Schema<?> shareSummary = changed.getComponents().getSchemas().get("ShareSummary");
        final Map<String, Schema> properties = shareSummary.getProperties();
        properties.get("storedSeq").setType("string");
        changed.getPaths().get("/results-store/v1/shares/{shareId}").getGet().setOperationId("renamed");

        final List<String> differences = differences(changed, jar);

        assertThat(differences).containsExactly("paths /results-store/v1/shares/{shareId}",
                "components.schemas ShareSummary");
    }

    /** Every path and component that differs, named by its key only; never the document's text. */
    private static List<String> differences(final OpenAPI left, final OpenAPI right) {
        final List<String> differences = new ArrayList<>(differingPaths(left, right));
        differences.addAll(differingComponents(left, right));
        if (!Objects.equals(left.getTags(), right.getTags())) {
            differences.add("tags");
        }
        return differences;
    }

    private static List<String> differingPaths(final OpenAPI left, final OpenAPI right) {
        return differing("paths", left.getPaths(), right.getPaths());
    }

    private static List<String> differingComponents(final OpenAPI left, final OpenAPI right) {
        final Components l = left.getComponents();
        final Components r = right.getComponents();
        final List<String> differences = new ArrayList<>();
        differences.addAll(differing("components.schemas", l.getSchemas(), r.getSchemas()));
        differences.addAll(differing("components.parameters", l.getParameters(), r.getParameters()));
        differences.addAll(differing("components.responses", l.getResponses(), r.getResponses()));
        differences.addAll(differing("components.headers", l.getHeaders(), r.getHeaders()));
        differences.addAll(differing("components.requestBodies", l.getRequestBodies(), r.getRequestBodies()));
        return differences;
    }

    private static List<String> differing(final String section, final Map<String, ?> left,
            final Map<String, ?> right) {
        final Map<String, ?> l = left == null ? Map.of() : left;
        final Map<String, ?> r = right == null ? Map.of() : right;
        final Set<String> keys = new TreeSet<>(l.keySet());
        keys.addAll(r.keySet());
        return keys.stream().filter(key -> !Objects.equals(l.get(key), r.get(key)))
                .map(key -> section + " " + key).toList();
    }

    private static OpenAPI parse(final String text) {
        return new OpenAPIV3Parser().readContents(text).getOpenAPI();
    }

    private static String read(final Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
