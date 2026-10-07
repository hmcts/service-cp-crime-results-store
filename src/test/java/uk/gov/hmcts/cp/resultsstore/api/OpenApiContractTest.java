package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import uk.gov.hmcts.cp.resultsstore.filters.ApiRoute;
import uk.gov.hmcts.cp.resultsstore.openapi.api.SharesApi;

/**
 * The served routes, the OpenAPI document, the route table and the allow rules agree both ways (FR-053, FR-063):
 * every mapping the controller registers (the generated interface's) is described, every described route is
 * served, every route has a rule, and the controller overrides every generated operation, so no default answers
 * {@code 501}.
 */
@DisplayName("the OpenAPI document against the controller")
class OpenApiContractTest {

    private static OpenAPI api;

    private static String rules;

    @BeforeAll
    static void read() throws IOException {
        api = new OpenAPIV3Parser().readContents(text("results-store-openapi.yaml")).getOpenAPI();
        rules = text("acl/results-store-rules.drl");
    }

    private static String text(final String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The generated operations: the interface's methods that carry a mapping. */
    private static List<Method> operations() {
        return Arrays.stream(SharesApi.class.getMethods())
                .filter(method -> method.isAnnotationPresent(RequestMapping.class))
                .toList();
    }

    private static String path(final Method operation) {
        return operation.getAnnotation(RequestMapping.class).value()[0];
    }

    private static Map<String, Operation> describedGets() {
        return api.getPaths().entrySet().stream()
                .filter(entry -> entry.getValue().getGet() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getGet()));
    }

    @Test
    void every_controller_mapping_should_be_described() {
        assertThat(operations()).hasSize(4).allSatisfy(operation -> {
            assertThat(operation.getAnnotation(RequestMapping.class).method()).containsExactly(RequestMethod.GET);
            assertThat(describedGets()).containsKey(path(operation));
            assertThat(describedGets().get(path(operation)).getOperationId()).isEqualTo(operation.getName());
        });
    }

    @Test
    void every_described_route_should_have_a_controller_mapping() {
        assertThat(api.getPaths().values()).allSatisfy(item -> assertThat(item.readOperations()).hasSize(1));
        assertThat(describedGets().keySet())
                .containsExactlyInAnyOrderElementsOf(operations().stream().map(OpenApiContractTest::path).toList());
    }

    @ParameterizedTest
    @EnumSource(ApiRoute.class)
    void every_api_route_should_have_a_drl_rule_and_a_controller_mapping(final ApiRoute route) {
        assertThat(rules).contains("\"" + route.action() + "\"");
        assertThat(operations()).extracting(OpenApiContractTest::path).contains(route.template());
    }

    @Test
    void every_path_parameter_should_be_named_as_the_controller_names_it() {
        assertThat(operations()).allSatisfy(operation -> {
            final List<Parameter> described = describedGets().get(path(operation)).getParameters();
            assertThat(names(operation, PathVariable.class)).as(operation.getName())
                    .isEqualTo(described(described, "path"));
            assertThat(names(operation, RequestParam.class)).as(operation.getName())
                    .isEqualTo(described(described, "query"));
        });
    }

    @Test
    void every_sharesapi_operation_should_be_overridden() throws NoSuchMethodException {
        for (final Method operation : operations()) {
            assertThat(SharesController.class.getMethod(operation.getName(), operation.getParameterTypes())
                    .getDeclaringClass()).as(operation.getName()).isEqualTo(SharesController.class);
        }
    }

    private static Set<String> names(final Method operation, final Class<?> kind) {
        return Arrays.stream(operation.getParameters())
                .map(parameter -> kind == PathVariable.class
                        ? pathName(parameter.getAnnotation(PathVariable.class))
                        : queryName(parameter.getAnnotation(RequestParam.class)))
                .filter(name -> name != null)
                .collect(Collectors.toSet());
    }

    private static String pathName(final PathVariable variable) {
        return variable == null ? null : variable.value();
    }

    private static String queryName(final RequestParam parameter) {
        return parameter == null ? null : parameter.value();
    }

    private static Set<String> described(final List<Parameter> parameters, final String in) {
        return parameters == null ? Set.of() : parameters.stream()
                .map(parameter -> parameter.get$ref() == null ? parameter
                        : api.getComponents().getParameters().get(parameter.get$ref().substring(
                                parameter.get$ref().lastIndexOf('/') + 1)))
                .filter(parameter -> in.equals(parameter.getIn()))
                .map(Parameter::getName)
                .collect(Collectors.toSet());
    }
}
