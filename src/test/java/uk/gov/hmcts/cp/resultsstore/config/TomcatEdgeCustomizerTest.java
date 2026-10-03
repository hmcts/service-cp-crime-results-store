package uk.gov.hmcts.cp.resultsstore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.core.Ordered;
import uk.gov.hmcts.cp.resultsstore.api.ProblemErrorReportValve;
import uk.gov.hmcts.cp.resultsstore.support.RecordingRefusalObserver;

/** The connector policy and the host's error report, set explicitly so nobody loosens them by accident. */
@DisplayName("the Tomcat edge customiser")
class TomcatEdgeCustomizerTest {

    private final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

    private final TomcatEdgeCustomizer customizer = new TomcatEdgeCustomizer(new RecordingRefusalObserver());

    /**
     * {@code TRACE} is let through the connector so the action filter refuses it like any other method
     * ({@code 405 method_not_allowed}, {@code Allow: GET}, counted); Tomcat's own refusal would answer with a
     * generic reason, its own {@code Allow} and no count.
     */
    @Test
    void the_connector_should_reject_encoded_slashes_and_backslashes_and_pass_trace_to_the_filters() {
        customizer.customize(factory);
        final Connector connector = new Connector();
        connector.setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue());
        connector.setAllowBackslash(true);
        connector.setAllowTrace(false);

        factory.getConnectorCustomizers().forEach(each -> each.customize(connector));

        assertThat(connector.getEncodedSolidusHandling()).isEqualTo(EncodedSolidusHandling.REJECT.getValue());
        assertThat(connector.getAllowBackslash()).isFalse();
        assertThat(connector.getAllowTrace()).isTrue();
    }

    @Test
    void the_host_should_report_errors_with_the_problem_valve_only() {
        customizer.customize(factory);
        final StandardHost host = new StandardHost();
        final ErrorReportValve bootsValve = new ErrorReportValve();
        host.getPipeline().addValve(bootsValve);
        final StandardContext context = new StandardContext();
        context.setName("");
        context.setPath("");
        host.addChild(context);

        factory.getContextCustomizers().forEach(each -> each.customize(context));

        final Valve[] valves = host.getPipeline().getValves();
        assertThat(Arrays.stream(valves).filter(ErrorReportValve.class::isInstance).toList())
                .singleElement().isInstanceOf(ProblemErrorReportValve.class);
        assertThat(host.getErrorReportValveClass()).isEqualTo(ProblemErrorReportValve.class.getName());
    }

    /** A context with no host above it (or another kind of parent) is left alone, without failing. */
    @Test
    void a_context_without_a_standard_host_should_be_left_alone() {
        customizer.customize(factory);
        final StandardContext context = new StandardContext();

        factory.getContextCustomizers().forEach(each -> each.customize(context));

        assertThat(context.getParent()).isNull();
        assertThat(context.getPipeline().getValves()).noneMatch(ProblemErrorReportValve.class::isInstance);
    }

    /** After Boot's own customiser (order 0), which adds its error report valve to the host. */
    @Test
    void it_should_run_after_boots_tomcat_customiser() {
        assertThat(customizer.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
