package uk.gov.hmcts.cp.resultsstore.config;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.ApplicationAnswer;
import uk.gov.hmcts.cp.resultsstore.application.ProgressionApplications;
import uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeFailureCause;
import uk.gov.hmcts.cp.resultsstore.support.ProgressionStub;

/** The progression client {@link ProgressionConfig} builds: its base URL, user and timeouts. */
@DisplayName("progression wiring")
class ProgressionConfigTest {

    private static final ProgressionStub PROGRESSION = ProgressionStub.start();

    private static final String SYSTEM_USER_ID = "5e4d3c2b-1a09-4f8e-9d7c-6b5a49382716";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final ProgressionConfig config = new ProgressionConfig();

    @AfterAll
    static void stopProgression() {
        PROGRESSION.close();
    }

    private static ProgressionProperties settings(final String baseUrl, final String systemUserId,
            final Duration readTimeout) {
        return new ProgressionProperties(baseUrl, systemUserId, Duration.ofSeconds(5), readTimeout);
    }

    @Test
    void built_client_should_ask_progression_at_the_base_url_as_the_system_user() {
        final UUID applicationId = UUID.randomUUID();
        PROGRESSION.answer(applicationId, okJson("{\"courtApplication\": {\"applicationStatus\": \"LISTED\"}}"));
        final ProgressionApplications client = config.progressionApplications(
                settings(PROGRESSION.baseUrl(), SYSTEM_USER_ID, Duration.ofSeconds(10)), mapper);

        assertThat(client.find(applicationId)).isInstanceOf(ApplicationAnswer.Found.class);

        assertThat(PROGRESSION.requestsFor(applicationId)).singleElement()
                .satisfies(request -> assertThat(request.getHeader("CJSCPPUID")).isEqualTo(SYSTEM_USER_ID));
    }

    @Test
    void built_client_should_time_out_at_the_read_timeout() {
        final UUID applicationId = UUID.randomUUID();
        PROGRESSION.answer(applicationId, okJson("{}").withFixedDelay(2_000));
        final ProgressionApplications client = config.progressionApplications(
                settings(PROGRESSION.baseUrl(), SYSTEM_USER_ID, Duration.ofSeconds(1)), mapper);

        assertThatThrownBy(() -> client.find(applicationId))
                .isInstanceOfSatisfying(RetryableIntakeException.class, failure -> assertThat(
                        failure.getFailureCause()).isEqualTo(IntakeFailureCause.PROGRESSION_TIMEOUT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blank_base_url_should_stop_the_client_being_built(final String baseUrl) {
        final ProgressionProperties settings = settings(baseUrl, SYSTEM_USER_ID, Duration.ofSeconds(10));

        assertThatThrownBy(() -> config.progressionApplications(settings, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("resultsstore.progression.base-url must be set")
                .hasMessageNotContaining(SYSTEM_USER_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blank_system_user_id_should_stop_the_client_being_built(final String systemUserId) {
        final ProgressionProperties settings = settings(PROGRESSION.baseUrl(), systemUserId, Duration.ofSeconds(10));

        assertThatThrownBy(() -> config.progressionApplications(settings, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("resultsstore.progression.system-user-id must be set")
                .hasMessageNotContaining(PROGRESSION.baseUrl());
    }
}
