package uk.gov.hmcts.cp.resultsstore.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the share item")
class ShareViewTest {

    private static final KeyDetails DETAILS = new KeyDetails(UUID.randomUUID(), null, "2577", "MAGISTRATES",
            false, false, null, false);

    @Test
    void key_details_should_be_null_exactly_when_failed() {
        assertThatCode(() -> view(ProjectionStatus.OK, DETAILS)).doesNotThrowAnyException();
        assertThatCode(() -> view(ProjectionStatus.FAILED, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> view(ProjectionStatus.OK, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyDetails must be null exactly when projectionStatus is FAILED");
        assertThatThrownBy(() -> view(ProjectionStatus.FAILED, DETAILS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyDetails must be null exactly when projectionStatus is FAILED");
    }

    @Test
    void a_missing_identity_or_status_should_be_refused() {
        assertThatThrownBy(() -> new ShareView(null, UUID.randomUUID(), LocalDate.EPOCH, Instant.EPOCH, 1L,
                Instant.EPOCH, LocalDate.EPOCH, LocalDate.EPOCH, DETAILS, null, null, true, null, false, false,
                ProjectionStatus.OK, 1, Instant.EPOCH, 1L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view(null, null)).isInstanceOf(NullPointerException.class);
    }

    private static ShareView view(final ProjectionStatus status, final KeyDetails details) {
        return new ShareView(UUID.randomUUID(), UUID.randomUUID(), LocalDate.EPOCH, Instant.EPOCH, 1L,
                Instant.EPOCH, LocalDate.EPOCH, LocalDate.EPOCH, details, null, null, true, null, false, false,
                status, 1, Instant.EPOCH, 1L);
    }
}
