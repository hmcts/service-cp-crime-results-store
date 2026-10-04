package uk.gov.hmcts.cp.resultsstore.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.api.ProblemReason;
import uk.gov.hmcts.cp.resultsstore.domain.DayYouthFilter;
import uk.gov.hmcts.cp.resultsstore.domain.EnvelopeMetadata;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadForm;
import uk.gov.hmcts.cp.resultsstore.domain.SearchCursor;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;
import uk.gov.hmcts.cp.resultsstore.domain.StoredPayload;

/**
 * The read API's application service (FR-010, FR-013, FR-014, FR-026 to FR-029, FR-031 to FR-034, FR-039):
 * checks the parameters the store depends on, works out the pull cursor and {@code hasMore}, the search cursor,
 * and the payload bytes with their {@code ETag}. A refusal is a {@link BadParameterException} or a
 * {@link NotFoundException} carrying a bounded reason, never a caller's value.
 */
public class ShareReadService {

    /** The default {@code limit} of pull and search. */
    public static final int DEFAULT_LIMIT = 100;

    /** The largest {@code limit} of pull and search. */
    public static final int MAX_LIMIT = 500;

    /** The longest day-form search, both ends counted. */
    public static final long MAX_DAYS = 31;

    /** The longest time-form search, half-open. */
    public static final Duration MAX_TIME_RANGE = Duration.ofDays(31);

    private static final long NANOS_PER_MICRO = 1000;

    private final ShareQueries queries;

    private final ReadObserver observer;

    private final Duration lag;

    /**
     * Creates the service.
     *
     * @param queries       the read queries
     * @param observer      the read meters
     * @param visibilityLag the pull's visibility lag
     */
    public ShareReadService(final ShareQueries queries, final ReadObserver observer, final Duration visibilityLag) {
        this.queries = queries;
        this.observer = observer;
        this.lag = visibilityLag;
    }

    /**
     * The pull's visibility lag this service holds back by (the effective lag of {@code ReadApiConfig}).
     *
     * @return the lag
     */
    public Duration visibilityLag() {
        return lag;
    }

    /**
     * A pull page (FR-009 to FR-016).
     *
     * @param storedAfterSeq the cursor, not negative
     * @param limit          the most items, 1 to 500; {@code null} for 100
     * @param dayYouthSeen   the day filter, never {@link DayYouthFilter#FALSE}; {@code null} for any day
     * @param courtCentreId  the court, exact match; {@code null} for every court
     * @return the page
     */
    public PullPage pull(final long storedAfterSeq, final Integer limit, final DayYouthFilter dayYouthSeen,
            final UUID courtCentreId) {
        if (storedAfterSeq < 0) {
            throw new BadParameterException(ProblemReason.INVALID_STORED_AFTER_SEQ);
        }
        final DayYouthFilter filter = dayYouthSeen == null ? DayYouthFilter.ANY : dayYouthSeen;
        if (!filter.allowedOnPull()) {
            throw new BadParameterException(ProblemReason.INVALID_DAY_YOUTH_SEEN);
        }
        final int checkedLimit = limit(limit);
        final ShareQueries.PullRows rows = queries.pull(new PullQuery(storedAfterSeq, checkedLimit, filter,
                courtCentreId), lag);
        final boolean hasMore = rows.rows().size() > checkedLimit;
        final List<ShareView> items = hasMore ? rows.rows().subList(0, checkedLimit) : rows.rows();
        final long next = hasMore ? items.getLast().storedSeq()
                : Math.max(storedAfterSeq, rows.boundSeq() == null ? storedAfterSeq : rows.boundSeq());
        observer.pageItems(items.size());
        return new PullPage(items, next, hasMore, rows.visibleUpTo());
    }

    /**
     * A search page (FR-026 to FR-029): the day form is turned into London midnight to London midnight.
     *
     * @param request the search's parameters
     * @return the page
     */
    public SearchPage search(final SearchRequest request) {
        if (request.courtCentreId() == null) {
            throw new BadParameterException(ProblemReason.MISSING_PARAMETER);
        }
        final SharedDays.InstantRange range = range(request);
        final int checkedLimit = limit(request.limit());
        final SearchCursor after = request.cursor() == null ? null : SearchCursor.decode(request.cursor())
                .orElseThrow(() -> new BadParameterException(ProblemReason.INVALID_CURSOR));
        final List<ShareView> rows = queries.search(new SearchQuery(request.courtCentreId(), range.from(),
                range.to(), request.dayYouthSeen() == null ? DayYouthFilter.ANY : request.dayYouthSeen(),
                Boolean.TRUE.equals(request.latestOnly()), after, checkedLimit));
        final boolean hasMore = rows.size() > checkedLimit;
        final List<ShareView> items = hasMore ? rows.subList(0, checkedLimit) : rows;
        final String nextCursor = hasMore
                ? SearchCursor.after(items.getLast().sharedTime(), items.getLast().shareId()).encode()
                : null;
        observer.pageItems(items.size());
        return new SearchPage(items, nextCursor);
    }

    /**
     * One share (FR-031).
     *
     * @param shareId the share
     * @return the share
     * @throws NotFoundException {@code share_not_found}
     */
    public ShareView share(final UUID shareId) {
        return queries.share(shareId).orElseThrow(() -> new NotFoundException(ProblemReason.SHARE_NOT_FOUND));
    }

    /**
     * The day's versions in {@code sharedTime} order (FR-032).
     *
     * @param hearingId  the hearing
     * @param hearingDay the day
     * @return the shares, at least one
     * @throws NotFoundException {@code hearing_day_not_found} when the day has none
     */
    public List<ShareView> dayVersions(final UUID hearingId, final LocalDate hearingDay) {
        final List<ShareView> shares = queries.dayVersions(hearingId, hearingDay);
        if (shares.isEmpty()) {
            throw new NotFoundException(ProblemReason.HEARING_DAY_NOT_FOUND);
        }
        return shares;
    }

    /**
     * The payload as served (FR-033, FR-034): the working copy as the database wrote it without
     * {@code _metadata}, or the arrived text with {@code _metadata} removed here; the {@code ETag} is over exactly
     * the bytes returned.
     *
     * @param shareId the share
     * @return the bytes, their {@code ETag} and the share's facts
     * @throws NotFoundException {@code share_not_found}
     * @throws EnvelopeMetadata.UnreadablePayloadException when an arrived text does not parse ({@code 500
     *         internal_error}); never the text
     */
    public ServedPayload payload(final UUID shareId) {
        final StoredPayload stored = queries.payload(shareId)
                .orElseThrow(() -> new NotFoundException(ProblemReason.SHARE_NOT_FOUND));
        final String text = stored.form() == PayloadForm.ARRIVED_TEXT
                ? EnvelopeMetadata.strip(stored.body())
                : stored.body();
        final byte[] body = text.getBytes(StandardCharsets.UTF_8);
        observer.payloadBytes(body.length);
        return new ServedPayload(body, "\"" + PayloadChecksum.sha256Hex(body) + "\"", stored.shareId(),
                stored.hearingId(), stored.hearingDay(), stored.sharedTime(), stored.enrichmentApplied(),
                stored.form());
    }

    private static int limit(final Integer limit) {
        final int checked = limit == null ? DEFAULT_LIMIT : limit;
        if (checked < 1 || checked > MAX_LIMIT) {
            throw new BadParameterException(ProblemReason.LIMIT_OUT_OF_RANGE);
        }
        return checked;
    }

    /** The checked half-open range of either form. */
    private static SharedDays.InstantRange range(final SearchRequest request) {
        final boolean anyDay = request.sharedDayFrom() != null || request.sharedDayTo() != null;
        final boolean anyTime = request.sharedFrom() != null || request.sharedTo() != null;
        if (anyDay && anyTime) {
            throw new BadParameterException(ProblemReason.CONFLICTING_PARAMETERS);
        }
        final SharedDays.InstantRange range;
        if (request.sharedDayFrom() != null && request.sharedDayTo() != null) {
            range = dayRange(request.sharedDayFrom(), request.sharedDayTo());
        } else if (request.sharedFrom() != null && request.sharedTo() != null) {
            range = timeRange(request.sharedFrom(), request.sharedTo());
        } else {
            throw new BadParameterException(ProblemReason.MISSING_PARAMETER);
        }
        return range;
    }

    private static SharedDays.InstantRange dayRange(final LocalDate from, final LocalDate to) {
        if (from.isAfter(to)) {
            throw new BadParameterException(ProblemReason.DAY_RANGE_REVERSED);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BadParameterException(ProblemReason.DAY_RANGE_TOO_LONG);
        }
        return SharedDays.londonDays(from, to);
    }

    private static SharedDays.InstantRange timeRange(final Instant from, final Instant to) {
        if (from.getNano() % NANOS_PER_MICRO != 0) {
            throw new BadParameterException(ProblemReason.INVALID_SHARED_FROM);
        }
        if (to.getNano() % NANOS_PER_MICRO != 0) {
            throw new BadParameterException(ProblemReason.INVALID_SHARED_TO);
        }
        if (!to.isAfter(from)) {
            throw new BadParameterException(ProblemReason.TIME_RANGE_REVERSED);
        }
        if (to.isAfter(from.plus(MAX_TIME_RANGE))) {
            throw new BadParameterException(ProblemReason.TIME_RANGE_TOO_LONG);
        }
        return new SharedDays.InstantRange(from, to);
    }

    /**
     * A search's parameters as the controller read them. Exactly one form is complete: the day form
     * ({@code sharedDayFrom}, {@code sharedDayTo}) or the time form ({@code sharedFrom}, {@code sharedTo}).
     *
     * @param courtCentreId the court, required
     * @param sharedDayFrom the first London day, day form
     * @param sharedDayTo   the last London day, included, day form
     * @param sharedFrom    the first {@code sharedTime} included, time form
     * @param sharedTo      the first {@code sharedTime} excluded, time form
     * @param dayYouthSeen  the day filter; {@code null} for any day
     * @param latestOnly    whether only latest shares are wanted; {@code null} for false
     * @param limit         the most items, 1 to 500; {@code null} for 100
     * @param cursor        the previous page's {@code nextCursor}; {@code null} for the first page
     */
    public record SearchRequest(UUID courtCentreId, LocalDate sharedDayFrom, LocalDate sharedDayTo,
            Instant sharedFrom, Instant sharedTo, DayYouthFilter dayYouthSeen, Boolean latestOnly, Integer limit,
            String cursor) {
    }
}
