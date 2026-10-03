package uk.gov.hmcts.cp.resultsstore.support;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A connection factory whose sessions fail their first {@code commit()} (research R19): the database
 * has committed, the acknowledgement is lost, and the broker redelivers. Only the first commit across
 * every session of the factory fails; the real session is left uncommitted, so closing it rolls the
 * message back. Everything else is passed to the factory it wraps.
 */
public final class FailingFirstCommitConnectionFactory implements ConnectionFactory {

    private final ConnectionFactory target;

    private final AtomicBoolean failed = new AtomicBoolean();

    /**
     * Wraps a factory.
     *
     * @param target the real factory
     */
    public FailingFirstCommitConnectionFactory(final ConnectionFactory target) {
        this.target = target;
    }

    /** Whether the one failing commit has happened. */
    public boolean hasFailed() {
        return failed.get();
    }

    @Override
    public Connection createConnection() throws JMSException {
        return connection(target.createConnection());
    }

    @Override
    public Connection createConnection(final String userName, final String password) throws JMSException {
        return connection(target.createConnection(userName, password));
    }

    @Override
    public JMSContext createContext() {
        throw new UnsupportedOperationException("the listener container uses connections");
    }

    @Override
    public JMSContext createContext(final String userName, final String password) {
        throw new UnsupportedOperationException("the listener container uses connections");
    }

    @Override
    public JMSContext createContext(final String userName, final String password, final int sessionMode) {
        throw new UnsupportedOperationException("the listener container uses connections");
    }

    @Override
    public JMSContext createContext(final int sessionMode) {
        throw new UnsupportedOperationException("the listener container uses connections");
    }

    private Connection connection(final Connection connection) {
        return proxy(Connection.class, (proxy, method, arguments) -> {
            final Object result = invoke(method, connection, arguments);
            return result instanceof Session session ? session(session) : result;
        });
    }

    private Session session(final Session session) {
        return proxy(Session.class, (proxy, method, arguments) -> {
            if ("commit".equals(method.getName()) && failed.compareAndSet(false, true)) {
                throw new JMSException("first commit refused by the test");
            }
            return invoke(method, session, arguments);
        });
    }

    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (final InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
