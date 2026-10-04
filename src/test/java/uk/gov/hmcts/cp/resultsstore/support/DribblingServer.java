package uk.gov.hmcts.cp.resultsstore.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A server that sends its whole response, status line and headers included, one byte at a time.
 * WireMock can dribble only a body; this covers a slow status line or slow headers, where every
 * socket read returns quickly but the response as a whole is late.
 */
public final class DribblingServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DribblingServer.class);

    private final ServerSocket socket;

    private final byte[] response;

    private final Duration gap;

    private final AtomicInteger requestCount = new AtomicInteger();

    private DribblingServer(final ServerSocket socket, final String response, final Duration gap) {
        this.socket = socket;
        this.response = response.getBytes(StandardCharsets.US_ASCII);
        this.gap = gap;
    }

    /**
     * Starts a server on a free loopback port.
     *
     * @param response the raw HTTP response, sent to every request
     * @param gap      the pause before each byte
     * @return the running server
     * @throws IOException when no port can be opened
     */
    public static DribblingServer start(final String response, final Duration gap) throws IOException {
        final DribblingServer server =
                new DribblingServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), response, gap);
        Thread.ofVirtual().name("dribbling-server").start(server::serve);
        return server;
    }

    /** The base URL to point a client at. */
    public String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    /** The requests received so far. */
    public int requests() {
        return requestCount.get();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try {
                answerInBackground(socket.accept());
            } catch (IOException e) {
                LOG.debug("Dribbling server stopped accepting: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void answerInBackground(final Socket connection) {
        Thread.ofVirtual().start(() -> answer(connection));
    }

    private void answer(final Socket connection) {
        try (connection; InputStream in = connection.getInputStream(); OutputStream out = connection.getOutputStream()) {
            skipRequestHead(in);
            requestCount.incrementAndGet();
            for (final byte next : response) {
                Thread.sleep(gap);
                out.write(next);
                out.flush();
            }
        } catch (IOException e) {
            LOG.debug("Client left the dribbling server: {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void skipRequestHead(final InputStream in) throws IOException {
        int matched = 0;
        final byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            final int next = in.read();
            if (next < 0) {
                throw new IOException("request ended early");
            }
            matched = next == end[matched] ? matched + 1 : next == '\r' ? 1 : 0;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
