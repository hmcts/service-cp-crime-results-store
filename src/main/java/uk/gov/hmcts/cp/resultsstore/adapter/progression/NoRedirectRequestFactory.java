package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * The progression call's transport (research R11, R12): Apache HttpClient with redirects and
 * automatic retries off.
 *
 * <p>Redirects: a followed 3xx would send the {@code CJSCPPUID} header to wherever it points. Off, a
 * 3xx reaches the client's classifier and fails closed. Retries: {@code HttpURLConnection} silently
 * resends a {@code GET} once when the connection fails before the status line, and no setting stops
 * it; FR-009 allows one request per lookup, so this transport is used instead. Content compression,
 * cookies and protocol upgrades are off too, so the request carries only the headers the store sets
 * and the transport's own {@code Host}, {@code User-Agent} and {@code Connection}. Connections are not
 * reused, so no lookup ever starts on a connection the server has already dropped.
 *
 * <p>Deadline (research R15): the socket timeout bounds each read only, so a server sending one byte at
 * a time, in the status line, the headers or the body, never trips it. Each request is therefore
 * cancelled, its connection closed, once the response deadline has passed since it was created; the
 * blocked read fails at once and nothing is retried. Cancelling a request that has already finished
 * does nothing.
 */
public final class NoRedirectRequestFactory extends HttpComponentsClientHttpRequestFactory {

    private final ConnectionConfig connectionConfig;

    private final Duration responseDeadline;

    /** One daemon thread for every lookup's deadline; it only ever cancels a request. */
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("progression-deadline").daemon().factory());

    /**
     * Creates the factory.
     *
     * @param connectTimeout   the connect timeout
     * @param readTimeout      the timeout of each socket read
     * @param responseDeadline the longest a whole exchange may take, from creating the request
     */
    public NoRedirectRequestFactory(final Duration connectTimeout, final Duration readTimeout,
            final Duration responseDeadline) {
        this(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(readTimeout))
                .build(), readTimeout, responseDeadline);
    }

    private NoRedirectRequestFactory(final ConnectionConfig connectionConfig, final Duration readTimeout,
            final Duration responseDeadline) {
        super(httpClient(connectionConfig, readTimeout));
        this.connectionConfig = connectionConfig;
        this.responseDeadline = responseDeadline;
    }

    /**
     * The connection settings every lookup uses.
     *
     * @return the connect timeout and the socket (read) timeout
     */
    public ConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }

    /**
     * The whole-exchange deadline each request is given.
     *
     * @return the deadline
     */
    public Duration getResponseDeadline() {
        return responseDeadline;
    }

    /** Creates the request and arms its deadline. */
    @Override
    protected ClassicHttpRequest createHttpUriRequest(final HttpMethod httpMethod, final URI uri) {
        // Spring builds an HttpUriRequestBase for every method it knows, and refuses any other.
        final HttpUriRequestBase request = (HttpUriRequestBase) super.createHttpUriRequest(httpMethod, uri);
        deadlines.schedule(request::cancel, responseDeadline.toNanos(), TimeUnit.NANOSECONDS);
        return request;
    }

    /** Stops the deadline thread and closes the HTTP client. */
    @Override
    public void destroy() throws Exception {
        deadlines.shutdownNow();
        super.destroy();
    }

    private static CloseableHttpClient httpClient(final ConnectionConfig connectionConfig,
            final Duration readTimeout) {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(readTimeout))
                        .setRedirectsEnabled(false)
                        .setContentCompressionEnabled(false)
                        .setProtocolUpgradeEnabled(false)
                        .setHardCancellationEnabled(true)
                        .build())
                .setConnectionReuseStrategy((request, response, context) -> false)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
    }
}
