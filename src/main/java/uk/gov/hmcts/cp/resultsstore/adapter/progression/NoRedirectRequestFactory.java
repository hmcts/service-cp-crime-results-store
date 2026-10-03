package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * {@link SimpleClientHttpRequestFactory} with redirects off (research R11). Spring turns them on for
 * {@code GET}, and {@code HttpURLConnection} would then send the {@code CJSCPPUID} header to wherever
 * a 3xx points. Off, a 3xx reaches the client's classifier and fails closed.
 */
public final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {

    /**
     * Creates the factory.
     *
     * @param connectTimeout the connect timeout
     * @param readTimeout    the timeout of each socket read
     */
    public NoRedirectRequestFactory(final Duration connectTimeout, final Duration readTimeout) {
        super();
        setConnectTimeout(connectTimeout);
        setReadTimeout(readTimeout);
    }

    @Override
    protected void prepareConnection(final HttpURLConnection connection, final String httpMethod)
            throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
