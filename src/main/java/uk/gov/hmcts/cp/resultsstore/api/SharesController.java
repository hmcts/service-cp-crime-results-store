package uk.gov.hmcts.cp.resultsstore.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.resultsstore.application.BadParameterException;
import uk.gov.hmcts.cp.resultsstore.application.ShareReadService;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.openapi.api.SharesApi;
import uk.gov.hmcts.cp.resultsstore.openapi.model.DayVersions;
import uk.gov.hmcts.cp.resultsstore.openapi.model.PullOrSearchShares200Response;
import uk.gov.hmcts.cp.resultsstore.openapi.model.ShareSummary;

/**
 * The read API (contracts/read-api.md §4): the one implementation of the contract jar's generated
 * {@link SharesApi} (research R23 C3; the interface's mappings would register once per implementing class). It
 * adds no mapping of its own; each operation calls {@link ShareReadService} once and answers through
 * {@link ShareResponseMapper} or {@link PayloadResponses}. The parameters reach it already checked by
 * {@link ShareParametersInterceptor}. Every {@code 200} is {@code application/json}, whatever {@code Accept}
 * named among the produced types.
 */
@RestController
public class SharesController implements SharesApi {

    private final ShareReadService service;

    /**
     * Creates the controller.
     *
     * @param service the read service
     */
    public SharesController(final ShareReadService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<PullOrSearchShares200Response> pullOrSearchShares(final Long storedAfterSeq,
            final Integer limit, final String dayYouthSeen, final UUID courtCentreId, final LocalDate sharedDayFrom,
            final LocalDate sharedDayTo, final Instant sharedFrom, final Instant sharedTo, final Boolean latestOnly,
            final String cursor) {
        final DayYouthFilter filter = dayYouthFilter(dayYouthSeen);
        final PullOrSearchShares200Response page = storedAfterSeq == null
                ? ShareResponseMapper.searchPage(service.search(new ShareReadService.SearchRequest(courtCentreId,
                        sharedDayFrom, sharedDayTo, sharedFrom, sharedTo, filter, latestOnly, limit, cursor)))
                : ShareResponseMapper.pullPage(service.pull(storedAfterSeq, limit, filter, courtCentreId));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(page);
    }

    @Override
    public ResponseEntity<ShareSummary> getShare(final UUID shareId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(ShareResponseMapper.summary(service.share(shareId)));
    }

    @Override
    public ResponseEntity<byte[]> getSharePayload(final UUID shareId, final String ifNoneMatch) {
        // Spring answers If-None-Match from the entity's ETag (research R11); only "*" is answered here.
        return PayloadResponses.answer(service.payload(shareId), ifNoneMatch);
    }

    @Override
    public ResponseEntity<DayVersions> listHearingDayShares(final UUID hearingId, final LocalDate hearingDay) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(ShareResponseMapper.dayVersions(service.dayVersions(hearingId, hearingDay)));
    }

    private static DayYouthFilter dayYouthFilter(final String value) {
        return value == null ? null : DayYouthFilter.fromValue(value)
                .orElseThrow(() -> new BadParameterException(ProblemReason.INVALID_DAY_YOUTH_SEEN));
    }
}
