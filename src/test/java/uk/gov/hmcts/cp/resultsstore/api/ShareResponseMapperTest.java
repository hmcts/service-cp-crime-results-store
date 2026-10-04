package uk.gov.hmcts.cp.resultsstore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.resultsstore.application.PullPage;
import uk.gov.hmcts.cp.resultsstore.application.SearchPage;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.openapi.model.DayVersions;
import uk.gov.hmcts.cp.resultsstore.openapi.model.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.openapi.model.ShareSummary;
import uk.gov.hmcts.cp.resultsstore.support.ShareViews;

/** The read side's answers mapped to the generated models, every field set and nulls kept (research R23 C3). */
@DisplayName("share response mapper")
class ShareResponseMapperTest {

    @Test
    void every_share_view_field_should_reach_the_model() {
        final ShareView view = ShareViews.complete();

        final ShareSummary summary = ShareResponseMapper.summary(view);

        assertThat(summary).isEqualTo(ShareSummary.builder()
                .shareId(view.shareId()).hearingId(view.hearingId()).hearingDay(view.hearingDay())
                .sharedTime(view.sharedTime()).storedSeq(48_213L).storedAt(view.storedAt())
                .sharedDayLondon(view.sharedDayLondon()).sharedDayUtc(view.sharedDayUtc())
                .keyDetails(KeyDetails.builder().courtCentreId(ShareViews.COURT_CENTRE_ID)
                        .courtRoomId(ShareViews.COURT_ROOM_ID).ljaCode("2577").jurisdictionType("MAGISTRATES")
                        .isSjp(false).isGroupProceedings(true).youthCourtId(ShareViews.YOUTH_COURT_ID).isReshare(false)
                        .build())
                .anySubjectIsYouth(true).dayYouthSeen(true).isLatest(true)
                .predecessorShareId(ShareViews.PREDECESSOR_ID).arrivedOutOfOrder(false).enrichmentApplied(true)
                .projectionStatus(ShareSummary.ProjectionStatusEnum.OK).projectionVersion(1)
                .projectedAt(view.projectedAt()).versionNumber(2)
                .build());
    }

    @Test
    void key_details_should_be_null_when_failed() {
        final ShareSummary summary = ShareResponseMapper.summary(ShareViews.failed());

        assertThat(summary.getKeyDetails()).isNull();
        assertThat(summary.getProjectionStatus()).isEqualTo(ShareSummary.ProjectionStatusEnum.FAILED);
        assertThat(summary.getAnySubjectIsYouth()).isNull();
        assertThat(summary.getDayYouthSeen()).isNull();
        assertThat(summary.getPredecessorShareId()).isNull();
    }

    @Test
    void pull_and_search_pages_should_map_every_field() {
        final Instant visibleUpTo = Instant.parse("2026-10-03T18:00:04Z");

        final var pull = ShareResponseMapper.pullPage(new PullPage(List.of(ShareViews.complete()), 48_213L, true,
                visibleUpTo));
        final var search = ShareResponseMapper.searchPage(new SearchPage(List.of(ShareViews.complete()), "djF8"));
        final var lastSearch = ShareResponseMapper.searchPage(new SearchPage(List.of(), null));

        assertThat(pull.getItems()).containsExactly(ShareResponseMapper.summary(ShareViews.complete()));
        assertThat(pull.getNextStoredAfterSeq()).isEqualTo(48_213L);
        assertThat(pull.getHasMore()).isTrue();
        assertThat(pull.getVisibleUpTo()).isEqualTo(visibleUpTo);
        assertThat(search.getItems()).containsExactly(ShareResponseMapper.summary(ShareViews.complete()));
        assertThat(search.getNextCursor()).isEqualTo("djF8");
        assertThat(lastSearch.getItems()).isEmpty();
        assertThat(lastSearch.getNextCursor()).isNull();
    }

    @Test
    void the_day_versions_should_keep_their_order() {
        final UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        final UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");

        final DayVersions versions = ShareResponseMapper.dayVersions(List.of(ShareViews.complete(second, 9L, 1),
                ShareViews.complete(first, 3L, 2)));

        assertThat(versions.getItems()).extracting(ShareSummary::getShareId).containsExactly(second, first);
        assertThat(versions.getItems()).extracting(ShareSummary::getVersionNumber).containsExactly(1, 2);
    }
}
