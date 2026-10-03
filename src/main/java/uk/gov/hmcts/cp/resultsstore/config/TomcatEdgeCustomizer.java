package uk.gov.hmcts.cp.resultsstore.config;

import java.util.Arrays;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Pipeline;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import uk.gov.hmcts.cp.resultsstore.api.ProblemErrorReportValve;

/**
 * The read API's HTTP connector policy and the host's error report (contracts/read-api.md §2.2, §6).
 *
 * <ul>
 *   <li>The connector rejects an encoded slash ({@code %2F}) and a backslash, and refuses {@code TRACE}.
 *       These are Tomcat's defaults, set here explicitly so nobody loosens them by accident; Boot has no
 *       property for the first two. A rejected URI never reaches the service's filters.</li>
 *   <li>The host's error report is {@link ProblemErrorReportValve}, so a request the connector rejects gets
 *       the four-field problem body rather than Tomcat's HTML page. Boot's own {@link ErrorReportValve} is
 *       removed from the host, and the host is told the valve's class, so it adds no default one at start.</li>
 * </ul>
 *
 * <p>Runs last ({@link Ordered#LOWEST_PRECEDENCE}), after Boot's Tomcat customiser has added its valve.
 */
public class TomcatEdgeCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    @Override
    public void customize(final TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(TomcatEdgeCustomizer::connectorPolicy);
        factory.addContextCustomizers(TomcatEdgeCustomizer::problemErrorReport);
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    private static void connectorPolicy(final Connector connector) {
        connector.setEncodedSolidusHandling(EncodedSolidusHandling.REJECT.getValue());
        connector.setAllowBackslash(false);
        connector.setAllowTrace(false);
    }

    private static void problemErrorReport(final Context context) {
        final Container parent = context.getParent();
        if (parent instanceof StandardHost host) {
            final Pipeline pipeline = host.getPipeline();
            Arrays.stream(pipeline.getValves())
                    .filter(ErrorReportValve.class::isInstance)
                    .forEach(pipeline::removeValve);
            pipeline.addValve(new ProblemErrorReportValve());
            host.setErrorReportValveClass(ProblemErrorReportValve.class.getName());
        }
    }
}
