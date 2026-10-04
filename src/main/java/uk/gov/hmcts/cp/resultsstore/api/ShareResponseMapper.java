package uk.gov.hmcts.cp.resultsstore.api;

import java.util.List;
import uk.gov.hmcts.cp.resultsstore.application.PullPage;
import uk.gov.hmcts.cp.resultsstore.application.SearchPage;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.openapi.model.DayVersions;
import uk.gov.hmcts.cp.resultsstore.openapi.model.ShareSummary;

/**
 * Maps the read side's answers to the contract jar's generated models (research R23 C3; contracts/read-api.md
 * §3, §4). Every field is set, nulls kept: the models carry no {@code NON_NULL} rule, so a null is written as
 * {@code null} (FR-004).
 */
public final class ShareResponseMapper {

    private ShareResponseMapper() {
        // Static functions only.
    }

    /**
     * One share item.
     *
     * @param view the share
     * @return the item; {@code keyDetails} {@code null} exactly when the share's extraction {@code FAILED}
     */
    public static ShareSummary summary(final ShareView view) {
        return ShareSummary.builder()
                .shareId(view.shareId())
                .hearingId(view.hearingId())
                .hearingDay(view.hearingDay())
                .sharedTime(view.sharedTime())
                .storedSeq(view.storedSeq())
                .storedAt(view.storedAt())
                .sharedDayLondon(view.sharedDayLondon())
                .sharedDayUtc(view.sharedDayUtc())
                .keyDetails(keyDetails(view.keyDetails()))
                .anySubjectIsYouth(view.anySubjectIsYouth())
                .dayYouthSeen(view.dayYouthSeen())
                .isLatest(view.latest())
                .predecessorShareId(view.predecessorShareId())
                .arrivedOutOfOrder(view.arrivedOutOfOrder())
                .enrichmentApplied(view.enrichmentApplied())
                .projectionStatus(ShareSummary.ProjectionStatusEnum.fromValue(view.projectionStatus().name()))
                .projectionVersion(view.projectionVersion())
                .projectedAt(view.projectedAt())
                .versionNumber(Math.toIntExact(view.versionNumber()))
                .build();
    }

    /**
     * A pull page.
     *
     * @param page the page
     * @return the model
     */
    public static uk.gov.hmcts.cp.resultsstore.openapi.model.PullPage pullPage(final PullPage page) {
        return uk.gov.hmcts.cp.resultsstore.openapi.model.PullPage.builder()
                .items(items(page.items()))
                .nextStoredAfterSeq(page.nextStoredAfterSeq())
                .hasMore(page.hasMore())
                .visibleUpTo(page.visibleUpTo())
                .build();
    }

    /**
     * A search page.
     *
     * @param page the page
     * @return the model; {@code nextCursor} {@code null} on the last page
     */
    public static uk.gov.hmcts.cp.resultsstore.openapi.model.SearchPage searchPage(final SearchPage page) {
        return uk.gov.hmcts.cp.resultsstore.openapi.model.SearchPage.builder()
                .items(items(page.items()))
                .nextCursor(page.nextCursor())
                .build();
    }

    /**
     * A day's versions, in the order read.
     *
     * @param views the shares
     * @return the model
     */
    public static DayVersions dayVersions(final List<ShareView> views) {
        return DayVersions.builder().items(items(views)).build();
    }

    private static List<ShareSummary> items(final List<ShareView> views) {
        return views.stream().map(ShareResponseMapper::summary).toList();
    }

    private static uk.gov.hmcts.cp.resultsstore.openapi.model.KeyDetails keyDetails(final KeyDetails details) {
        return details == null ? null : uk.gov.hmcts.cp.resultsstore.openapi.model.KeyDetails.builder()
                .courtCentreId(details.courtCentreId())
                .courtRoomId(details.courtRoomId())
                .ljaCode(details.ljaCode())
                .jurisdictionType(details.jurisdictionType())
                .isSjp(details.sjp())
                .isGroupProceedings(details.groupProceedings())
                .youthCourtId(details.youthCourtId())
                .isReshare(details.reshare())
                .build();
    }
}
