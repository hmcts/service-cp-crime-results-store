package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
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
 */
public final class NoRedirectRequestFactory extends HttpComponentsClientHttpRequestFactory {

    private final ConnectionConfig connectionConfig;

    /**
     * Creates the factory.
     *
     * @param connectTimeout the connect timeout
     * @param readTimeout    the timeout of each socket read
     */
    public NoRedirectRequestFactory(final Duration connectTimeout, final Duration readTimeout) {
        this(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(readTimeout))
                .build(), readTimeout);
    }

    private NoRedirectRequestFactory(final ConnectionConfig connectionConfig, final Duration readTimeout) {
        super(httpClient(connectionConfig, readTimeout));
        this.connectionConfig = connectionConfig;
    }

    /**
     * The connection settings every lookup uses.
     *
     * @return the connect timeout and the socket (read) timeout
     */
    public ConnectionConfig getConnectionConfig() {
        return connectionConfig;
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
