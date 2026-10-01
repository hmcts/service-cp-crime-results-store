package uk.gov.hmcts.cp.resultsstore.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures one logger's events for the life of a test. Close it to detach. */
public final class CapturedLog implements AutoCloseable {

    private final Logger logger;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private CapturedLog(final Class<?> owner) {
        logger = (Logger) LoggerFactory.getLogger(owner);
        appender.start();
        logger.addAppender(appender);
    }

    public static CapturedLog forClass(final Class<?> owner) {
        return new CapturedLog(owner);
    }

    /** The formatted messages captured so far. */
    public List<String> messages() {
        synchronized (appender.list) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    /** The captured events so far. */
    public List<ILoggingEvent> events() {
        synchronized (appender.list) {
            return List.copyOf(appender.list);
        }
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
