package uk.gov.hmcts.cp.resultsstore.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.ProjectionStatus;
import uk.gov.hmcts.cp.resultsstore.domain.ShareView;

/** Share views for the serving tests: every field set to a distinct value, or a {@code FAILED} share. */
public final class ShareViews {

    /** The sample share's id. */
    public static final UUID SHARE_ID = UUID.fromString("6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b");

    /** The sample share's hearing. */
    public static final UUID HEARING_ID = UUID.fromString("1a2b3c4d-0000-4000-8000-000000000001");

    /** The sample share's court centre. */
    public static final UUID COURT_CENTRE_ID = UUID.fromString("2b3c4d5e-0000-4000-8000-000000000002");

    /** The sample share's courtroom. */
    public static final UUID COURT_ROOM_ID = UUID.fromString("3c4d5e6f-0000-4000-8000-000000000003");

    /** The sample share's youth court. */
    public static final UUID YOUTH_COURT_ID = UUID.fromString("4d5e6f7a-0000-4000-8000-000000000004");

    /** The sample share's predecessor. */
    public static final UUID PREDECESSOR_ID = UUID.fromString("5e6f7a8b-0000-4000-8000-000000000005");

    /** The sample hearing day. */
    public static final LocalDate HEARING_DAY = LocalDate.parse("2026-10-02");

    private ShareViews() {
        // Static fixtures only.
    }

    /** A share with every field set, key details included. */
    public static ShareView complete() {
        return complete(SHARE_ID, 48_213L, 2);
    }

    /**
     * A share with every field set.
     *
     * @param shareId       its id
     * @param storedSeq     its sequence number
     * @param versionNumber its place in the day
     * @return the share
     */
    public static ShareView complete(final UUID shareId, final long storedSeq, final long versionNumber) {
        return new ShareView(shareId, HEARING_ID, HEARING_DAY, Instant.parse("2026-10-02T16:41:07.512Z"),
                storedSeq, Instant.parse("2026-10-02T16:41:08.003117Z"), LocalDate.parse("2026-10-02"),
                LocalDate.parse("2026-10-01"), new KeyDetails(COURT_CENTRE_ID, COURT_ROOM_ID, "2577", "MAGISTRATES",
                        false, true, YOUTH_COURT_ID, false),
                true, true, true, PREDECESSOR_ID, false, true, ProjectionStatus.OK, 1,
                Instant.parse("2026-10-02T16:41:08Z"), versionNumber);
    }

    /** A share whose extraction failed: no key details, youth unknown, first of its day. */
    public static ShareView failed() {
        return new ShareView(SHARE_ID, HEARING_ID, HEARING_DAY, Instant.parse("2026-10-02T16:41:07Z"), 7L,
                Instant.parse("2026-10-02T16:41:08Z"), HEARING_DAY, HEARING_DAY, null, null, null, true, null, false,
                false, ProjectionStatus.FAILED, 1, Instant.parse("2026-10-02T16:41:08Z"), 1);
    }
}
