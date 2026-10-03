package uk.gov.hmcts.cp.resultsstore.adapter.progression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The factory's settings and each request's deadline. What they do on the wire (no redirect followed,
 * no retry, the read timeout) is proved against WireMock in {@link ProgressionApplicationClientTest}.
 */
@DisplayName("progression request factory")
class NoRedirectRequestFactoryTest {

    private static final URI TARGET = URI.create("http://progression.invalid/applications/1");

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

    @Test
    void request_should_carry_one_absolute_deadline_fixed_when_it_is_created() throws Exception {
        final long createdAt = 5_000_000_000L;
        final NoRedirectRequestFactory factory = new NoRedirectRequestFactory(Duration.ofSeconds(3),
                Duration.ofSeconds(7), Duration.ofSeconds(11), () -> createdAt);
        try {
            final ProgressionExchange exchange =
                    ProgressionExchange.exchangeOf(factory.createRequest(TARGET, HttpMethod.GET));

            assertThat(exchange.getDeadlineNanos()).isEqualTo(createdAt + Duration.ofSeconds(11).toNanos());
            assertThat(exchange.isExpired()).isFalse();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void deadline_should_cancel_the_request_and_record_that_it_did() throws Exception {
        final NoRedirectRequestFactory factory = new NoRedirectRequestFactory(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofMillis(50));
        try {
            final ProgressionExchange exchange =
                    ProgressionExchange.exchangeOf(factory.createRequest(TARGET, HttpMethod.GET));

            await().atMost(Duration.ofSeconds(5)).until(exchange::isExpired);
        } finally {
            factory.destroy();
        }
    }
}
