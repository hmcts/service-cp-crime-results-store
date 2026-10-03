package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.function.LongSupplier;

/**
 * A deadline for a whole response body (research R15). {@code HttpURLConnection}'s read timeout is
 * per read, so a server sending a byte at a time never trips it; this stream fails before and after
 * each read once the deadline has passed, as the socket would on a stuck read.
 */
public class DeadlineInputStream extends FilterInputStream {

    private final long deadlineNanos;

    private final LongSupplier nanoClock;

    /**
     * Wraps a body.
     *
     * @param delegate      the body
     * @param deadlineNanos the deadline on {@code nanoClock}'s scale
     * @param nanoClock     a monotonic clock in nanoseconds, {@code System::nanoTime} in production
     */
    public DeadlineInputStream(final InputStream delegate, final long deadlineNanos, final LongSupplier nanoClock) {
        super(delegate);
        this.deadlineNanos = deadlineNanos;
        this.nanoClock = nanoClock;
    }

    @Override
    public int read() throws IOException {
        checkDeadline();
        final int next = super.read();
        checkDeadline();
        return next;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        checkDeadline();
        final int count = super.read(buffer, offset, length);
        checkDeadline();
        return count;
    }

    private void checkDeadline() throws SocketTimeoutException {
        if (nanoClock.getAsLong() - deadlineNanos > 0) {
            throw new SocketTimeoutException("response deadline passed");
        }
    }
}
