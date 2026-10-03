package uk.gov.hmcts.cp.resultsstore.support;

import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.resultsstore.application.Arrival;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Reading;
import uk.gov.hmcts.cp.resultsstore.application.ShareIdentityParser.Share;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.domain.PayloadChecksum;
import uk.gov.hmcts.cp.resultsstore.domain.SharedDays;

/**
 * Real-shaped {@code public.events.hearing.hearing-resulted} bodies (quickstart.md §4): identifiers
 * only, synthetic values. Each body has one prosecution case with one defendant.
 */
public final class SampleShares {

    /** The court centre every sample names. */
    public static final UUID COURT_CENTRE = UUID.fromString("9d2e4f6a-1b3c-4d5e-8f70-a1b2c3d4e5f6");

    /** The court room every sample names. */
    public static final UUID COURT_ROOM = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");

    /** The prosecution case every sample names. */
    public static final UUID CASE_ID = UUID.fromString("c1c1c1c1-0000-4000-8000-000000000001");

    /** The defendant every sample names. */
    public static final UUID DEFENDANT_ID = UUID.fromString("d1d1d1d1-0000-4000-8000-000000000001");

    /** The defendant's master defendant id. */
    public static final UUID MASTER_DEFENDANT_ID = UUID.fromString("e1e1e1e1-0000-4000-8000-000000000001");

    /** The event name the broker's selector admits. */
    public static final String HEARING_RESULTED = "public.events.hearing.hearing-resulted";

    private static final ShareIdentityParser PARSER = new ShareIdentityParser(JsonMapper.builder().build());

    private static final KeyDetailsExtractor EXTRACTOR = new KeyDetailsExtractor();

    private SampleShares() {
        // Static fixture builders.
    }

    /**
     * A share whose one defendant is stated not to be a youth.
     *
     * @param hearingId  {@code hearing.id}
     * @param hearingDay {@code hearingDay}
     * @param sharedTime {@code sharedTime}
     * @return the body
     */
    public static String share(final UUID hearingId, final String hearingDay, final String sharedTime) {
        return share(hearingId, hearingDay, sharedTime, "false", "");
    }

    /**
     * A share.
     *
     * @param hearingId  {@code hearing.id}
     * @param hearingDay {@code hearingDay}
     * @param sharedTime {@code sharedTime}
     * @param isYouth    the defendant's {@code isYouth} as JSON ({@code true} / {@code false}), or
     *                   {@code null} to leave it unstated
     * @param note       the content of a {@code note} string field, already escaped as JSON
     * @return the body
     */
    public static String share(final UUID hearingId, final String hearingDay, final String sharedTime,
            final String isYouth, final String note) {
        final String youth = isYouth == null ? "" : ",\"isYouth\":" + isYouth;
        return """
                {"_metadata":{"id":"%s","name":"%s","createdAt":"2026-10-02T14:19:51.012Z"},
                 "hearing":{"id":"%s","jurisdictionType":"MAGISTRATES",
                   "courtCentre":{"id":"%s","roomId":"%s","lja":{"ljaCode":"2577"}},
                   "prosecutionCases":[{"id":"%s",
                     "defendants":[{"id":"%s","masterDefendantId":"%s"%s}]}]},
                 "hearingDay":"%s","sharedTime":"%s","isReshare":false,"note":"%s"}
                """.formatted(UUID.randomUUID(), HEARING_RESULTED, hearingId, COURT_CENTRE, COURT_ROOM, CASE_ID,
                DEFENDANT_ID, MASTER_DEFENDANT_ID, youth, hearingDay, sharedTime, note);
    }

    /**
     * The share a body is read as.
     *
     * @param text the body
     * @return the parser's reading, which must be a share
     */
    public static Share read(final String text) {
        final Reading reading = PARSER.read(text);
        if (reading instanceof Share share) {
            return share;
        }
        throw new IllegalArgumentException("not a share: " + reading);
    }

    /**
     * The first arrival of a body, as the receipt records it.
     *
     * @param messageId the message id
     * @param text      the body
     * @return the arrival
     */
    public static Arrival arrival(final String messageId, final String text) {
        return new Arrival(messageId, 1, text, PARSER.read(text));
    }

    /**
     * The store request intake builds for a body: the identity read, the key details extracted.
     *
     * @param messageId the receipt's key
     * @param text      the body
     * @return the request
     */
    public static StoreRequest request(final String messageId, final String text) {
        final Share share = read(text);
        return new StoreRequest(messageId, share.identity(), share.identity().shareId(),
                SharedDays.from(share.identity().sharedAt()), PayloadChecksum.sha256Hex(text), text,
                EXTRACTOR.extract(share.body()));
    }
}
