package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("request factory with redirects off")
class NoRedirectRequestFactoryTest {

    @Test
    void prepared_connection_should_not_follow_redirects_and_should_carry_the_timeouts() throws IOException {
        final NoRedirectRequestFactory factory =
                new NoRedirectRequestFactory(Duration.ofSeconds(5), Duration.ofSeconds(10));
        // Opening a connection object makes no network call; prepareConnection only sets it up.
        final HttpURLConnection connection =
                (HttpURLConnection) URI.create("http://progression.invalid/").toURL().openConnection();

        factory.prepareConnection(connection, "GET");

        assertThat(connection.getInstanceFollowRedirects()).isFalse();
        assertThat(connection.getConnectTimeout()).isEqualTo(5000);
        assertThat(connection.getReadTimeout()).isEqualTo(10_000);
    }
}
