package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The factory's settings. What they do on the wire (no redirect followed, no retry, the read timeout)
 * is proved against WireMock in {@link ProgressionApplicationClientTest}.
 */
@DisplayName("progression request factory")
class NoRedirectRequestFactoryTest {

    @Test
    void connections_should_carry_the_connect_and_read_timeouts_and_the_deadline_given() throws Exception {
        final NoRedirectRequestFactory factory =
                new NoRedirectRequestFactory(Duration.ofSeconds(3), Duration.ofSeconds(7), Duration.ofSeconds(11));
        try {
            final ConnectionConfig connection = factory.getConnectionConfig();

            assertThat(connection.getConnectTimeout()).isEqualTo(Timeout.ofSeconds(3));
            assertThat(connection.getSocketTimeout()).isEqualTo(Timeout.ofSeconds(7));
            assertThat(factory.getResponseDeadline()).isEqualTo(Duration.ofSeconds(11));
        } finally {
            factory.destroy();
        }
    }
}
