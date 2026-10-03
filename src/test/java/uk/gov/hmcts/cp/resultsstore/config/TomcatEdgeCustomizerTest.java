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

/** The connector policy and the host's error report, set explicitly so nobody loosens them by accident. */
@DisplayName("the Tomcat edge customiser")
class TomcatEdgeCustomizerTest {

    private final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

    private final TomcatEdgeCustomizer customizer = new TomcatEdgeCustomizer();

    @Test
    void the_connector_should_reject_encoded_slashes_backslashes_and_trace() {
        customizer.customize(factory);
        final Connector connector = new Connector();
        connector.setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue());
        connector.setAllowBackslash(true);
        connector.setAllowTrace(true);

        factory.getConnectorCustomizers().forEach(each -> each.customize(connector));

        assertThat(connector.getEncodedSolidusHandling()).isEqualTo(EncodedSolidusHandling.REJECT.getValue());
        assertThat(connector.getAllowBackslash()).isFalse();
        assertThat(connector.getAllowTrace()).isFalse();
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

    /** After Boot's own customiser (order 0), which adds its error report valve to the host. */
    @Test
    void it_should_run_after_boots_tomcat_customiser() {
        assertThat(customizer.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }
}
