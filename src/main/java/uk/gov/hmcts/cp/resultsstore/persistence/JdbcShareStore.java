package uk.gov.hmcts.cp.resultsstore.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult;
import uk.gov.hmcts.cp.resultsstore.domain.DefendantRef;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;

/**
 * The share tables, written in one store transaction per share (FR-013 to FR-017, research R9 to R11).
 *
 * <p>The transaction locks the hearing day (inserting its row for the day's first share), inserts the
 * share with {@code ON CONFLICT DO NOTHING} on its identity, then its payload and defendant rows, moves
 * the day on and marks the receipt {@code STORED}. A conflict means the identity is already stored: the
 * receipt is marked {@code DUPLICATE} with the stored share's id, looked up by the identity (FR-012).
 * The key details arrive in the request, read before the transaction opened (FR-021). Any failure
 * rolls everything back, the receipt's mark included, and is thrown as a classified
 * {@link uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException}.
 */
public class JdbcShareStore implements ShareStore {

    private static final String INSERT_DAY = """
            INSERT INTO hearing_day_head (hearing_id, hearing_day) VALUES (:hearingId, :hearingDay)
            ON CONFLICT DO NOTHING
            """;

    private static final String LOCK_DAY = """
            SELECT share_count FROM hearing_day_head
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay
               FOR UPDATE
            """;

    private static final String INSERT_SHARE = """
            INSERT INTO hearing_share
                (share_id, hearing_id, hearing_day, shared_at, shared_day_london, shared_day_utc, payload_sha256,
                 is_reshare, court_centre_id, court_room_id, lja_code, jurisdiction_type, is_sjp,
                 is_group_proceedings, youth_court_id, any_subject_is_youth, is_latest, predecessor_share_id,
                 arrived_out_of_order, projection_status, projection_reason, projection_version)
            VALUES (:shareId, :hearingId, :hearingDay, :sharedAt, :sharedDayLondon, :sharedDayUtc, :checksum,
                    :reshare, :courtCentreId, :courtRoomId, :ljaCode, :jurisdictionType, :sjp,
                    :groupProceedings, :youthCourtId, :anySubjectIsYouth, FALSE, :predecessor,
                    :outOfOrder, :projectionStatus, :projectionReason, :projectionVersion)
            ON CONFLICT (hearing_id, hearing_day, shared_at) DO NOTHING
            RETURNING stored_at
            """;

    private static final String EXISTING_SHARE = """
            SELECT share_id FROM hearing_share
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay AND shared_at = :sharedAt
            """;

    private static final String INSERT_PAYLOAD = """
            INSERT INTO hearing_share_payload (share_id, payload_text, text_bytes, payload_json)
            VALUES (:shareId, :text, :textBytes, CAST(:parsedCopy AS jsonb))
            """;

    private static final String INSERT_DEFENDANT = """
            INSERT INTO share_defendant (share_id, case_id, defendant_id, master_defendant_id)
            VALUES (:shareId, :caseId, :defendantId, :masterDefendantId)
            """;

    private static final String CLEAR_LATEST = """
            UPDATE hearing_share SET is_latest = FALSE
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay AND is_latest
            """;

    private static final String SET_LATEST = "UPDATE hearing_share SET is_latest = TRUE WHERE share_id = :shareId";

    private static final String MOVE_DAY = """
            UPDATE hearing_day_head SET latest_share_id = :shareId, share_count = share_count + 1
             WHERE hearing_id = :hearingId AND hearing_day = :hearingDay
            """;

    private static final String SHARE_ID = "shareId";

    private static final String HEARING_ID = "hearingId";

    private static final String HEARING_DAY = "hearingDay";

    private static final String SHARED_AT = "sharedAt";

    private final JdbcClient jdbc;

    private final TransactionOperations storeTransaction;

    private final JdbcReceiptStore receipts;

    /**
     * Creates the store.
     *
     * @param jdbc             the database
     * @param storeTransaction the store transaction, bounded by its timeout
     * @param receipts         the receipt table, marked inside the store transaction
     */
    public JdbcShareStore(final JdbcClient jdbc, final TransactionOperations storeTransaction,
            final JdbcReceiptStore receipts) {
        this.jdbc = jdbc;
        this.storeTransaction = storeTransaction;
        this.receipts = receipts;
    }

    /**
     * {@inheritDoc}
     *
     * @throws uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException when the transaction fails,
     *         classified by its SQLSTATE; nothing is written
     * @throws IllegalStateException when the receipt is not {@code RECEIVED}; nothing is written
     */
    @Override
    public StoreResult store(final StoreRequest request) {
        try {
            return storeTransaction.execute(status -> storeLocked(request));
        } catch (final DataAccessException | TransactionException failure) {
            throw RetryableFailures.classify(IntakeStage.STORE, failure);
        }
    }

    private StoreResult storeLocked(final StoreRequest request) {
        final ShareIdentity identity = request.identity();
        lockDay(identity);
        final boolean jsonbSafe = NulSafety.isJsonbSafe(request.text());
        final Optional<Instant> storedAt = insertShare(request);
        final StoreResult result;
        if (storedAt.isPresent()) {
            insertPayload(request, jsonbSafe);
            insertDefendants(request);
            joinChain(identity, request.shareId());
            settle(receipts.markStored(request.messageId(), request.shareId()));
            result = new StoreResult.Stored(request.shareId(), storedAt.get(), false, !jsonbSafe);
        } else {
            final UUID existing = existingShare(identity);
            settle(receipts.markDuplicate(request.messageId(), existing));
            result = new StoreResult.Duplicate(existing);
        }
        return result;
    }

    /** Takes the hearing-day lock, creating the day row for its first share (research R10). */
    private void lockDay(final ShareIdentity identity) {
        jdbc.sql(INSERT_DAY).param(HEARING_ID, identity.hearingId()).param(HEARING_DAY, identity.hearingDay())
                .update();
        jdbc.sql(LOCK_DAY).param(HEARING_ID, identity.hearingId()).param(HEARING_DAY, identity.hearingDay())
                .query(Integer.class).single();
    }

    private Optional<Instant> insertShare(final StoreRequest request) {
        final ShareIdentity identity = request.identity();
        final KeyDetails details = keyDetails(request.projection());
        return jdbc.sql(INSERT_SHARE)
                .param(SHARE_ID, request.shareId())
                .param(HEARING_ID, identity.hearingId())
                .param(HEARING_DAY, identity.hearingDay())
                .param(SHARED_AT, utc(identity.sharedAt()))
                .param("sharedDayLondon", request.sharedDays().london())
                .param("sharedDayUtc", request.sharedDays().utc())
                .param("checksum", request.checksum())
                .param("reshare", details.reshare())
                .param("courtCentreId", details.courtCentreId())
                .param("courtRoomId", details.courtRoomId())
                .param("ljaCode", details.ljaCode())
                .param("jurisdictionType", details.jurisdictionType())
                .param("sjp", details.sjp())
                .param("groupProceedings", details.groupProceedings())
                .param("youthCourtId", details.youthCourtId())
                .param("anySubjectIsYouth", anySubjectIsYouth(request.projection()))
                .param("predecessor", null)
                .param("outOfOrder", false)
                .param("projectionStatus", request.projection().status().name())
                .param("projectionReason", reason(request.projection()))
                .param("projectionVersion", KeyDetailsExtractor.EXTRACTOR_VERSION)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    private void insertPayload(final StoreRequest request, final boolean jsonbSafe) {
        jdbc.sql(INSERT_PAYLOAD)
                .param(SHARE_ID, request.shareId())
                .param("text", request.text())
                .param("textBytes", request.text().getBytes(StandardCharsets.UTF_8).length)
                .param("parsedCopy", jsonbSafe ? request.text() : null)
                .update();
    }

    private void insertDefendants(final StoreRequest request) {
        if (request.projection() instanceof Projection.Extracted extracted) {
            for (final DefendantRef defendant : extracted.defendants()) {
                jdbc.sql(INSERT_DEFENDANT)
                        .param(SHARE_ID, request.shareId())
                        .param("caseId", defendant.caseId())
                        .param("defendantId", defendant.defendantId())
                        .param("masterDefendantId", defendant.masterDefendantId())
                        .update();
            }
        }
    }

    /** Clears the old latest before setting the new one: the one-latest index is checked per statement. */
    private void joinChain(final ShareIdentity identity, final UUID shareId) {
        jdbc.sql(CLEAR_LATEST).param(HEARING_ID, identity.hearingId()).param(HEARING_DAY, identity.hearingDay())
                .update();
        jdbc.sql(SET_LATEST).param(SHARE_ID, shareId).update();
        jdbc.sql(MOVE_DAY).param(SHARE_ID, shareId).param(HEARING_ID, identity.hearingId())
                .param(HEARING_DAY, identity.hearingDay()).update();
    }

    private UUID existingShare(final ShareIdentity identity) {
        return jdbc.sql(EXISTING_SHARE)
                .param(HEARING_ID, identity.hearingId())
                .param(HEARING_DAY, identity.hearingDay())
                .param(SHARED_AT, utc(identity.sharedAt()))
                .query(UUID.class)
                .single();
    }

    /**
     * A receipt not {@code RECEIVED} here was settled by another delivery of the same message. The
     * transaction is rolled back; the broker's redelivery then finds the receipt settled.
     */
    private static void settle(final boolean marked) {
        if (!marked) {
            throw new IllegalStateException("the receipt was not RECEIVED inside the store transaction");
        }
    }

    private static KeyDetails keyDetails(final Projection projection) {
        return projection instanceof Projection.Extracted extracted
                ? extracted.keyDetails()
                : new KeyDetails(null, null, null, null, null, null, null, null);
    }

    private static Boolean anySubjectIsYouth(final Projection projection) {
        return projection instanceof Projection.Extracted extracted ? extracted.anySubjectIsYouth() : null;
    }

    private static String reason(final Projection projection) {
        return projection instanceof Projection.Failed failed ? failed.reason() : null;
    }

    /** {@code timestamptz} bound as a {@code java.time} value at UTC. */
    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
