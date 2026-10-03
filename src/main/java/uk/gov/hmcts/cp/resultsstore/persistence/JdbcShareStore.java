package uk.gov.hmcts.cp.resultsstore.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import uk.gov.hmcts.cp.resultsstore.application.KeyDetailsExtractor;
import uk.gov.hmcts.cp.resultsstore.application.ShareStore;
import uk.gov.hmcts.cp.resultsstore.application.StoreRequest;
import uk.gov.hmcts.cp.resultsstore.application.StoreResult;
import uk.gov.hmcts.cp.resultsstore.application.SweepCandidate;
import uk.gov.hmcts.cp.resultsstore.domain.DefendantRef;
import uk.gov.hmcts.cp.resultsstore.domain.IntakeStage;
import uk.gov.hmcts.cp.resultsstore.domain.KeyDetails;
import uk.gov.hmcts.cp.resultsstore.domain.Projection;
import uk.gov.hmcts.cp.resultsstore.domain.ShareIdentity;
import uk.gov.hmcts.cp.resultsstore.domain.SweepRowOutcome;

/**
 * The share tables, written in one store transaction per share (FR-013 to FR-017, research R9 to R11).
 *
 * <p>The transaction locks the hearing day (inserting its row for the day's first share), finds the
 * share's place in the day's chain, inserts the share with {@code ON CONFLICT DO NOTHING} on its
 * identity, then its payload and defendant rows, links it into the chain ({@link ShareChain}),
 * recomputes the day's youth flag ({@link YouthFlags}) and marks the receipt {@code STORED}. A
 * conflict means the identity is already stored: the receipt is marked {@code DUPLICATE} with the
 * stored share's id, looked up by the identity (FR-012). The payload's parsed copy is written under a
 * savepoint: when PostgreSQL refuses the {@code jsonb} conversion, the payload row is written without
 * it and the skip is reported, so a payload {@code jsonb} cannot hold is still stored (FR-015).
 * The transaction first sets its own lock, statement and idle-in-transaction timeouts (FR-020). The
 * key details arrive in the request, read before the transaction opened (FR-021). Any failure
 * rolls everything back, the receipt's mark included, and is thrown as a classified
 * {@link uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException}.
 *
 * <p>The extraction sweep's writes take the same locks in the same order, day then share, inside the
 * same bounded transaction, and change only a {@code FAILED} row's key details and {@code projection_*}
 * columns, its first defendant rows and the day's youth flags (FR-034, FR-036, FR-044).
 */
public class JdbcShareStore implements ShareStore {

    /**
     * Transaction-local, the function form of {@code SET LOCAL} (research R2): the values end with the
     * transaction, committed or rolled back, so the next borrower of the pooled connection has the
     * server's defaults. Bound, never built from strings.
     */
    private static final String SET_TIMEOUTS = """
            SELECT set_config('lock_timeout', :lockTimeout, TRUE),
                   set_config('statement_timeout', :statementTimeout, TRUE),
                   set_config('idle_in_transaction_session_timeout', :idleInTransactionTimeout, TRUE)
            """;

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

    /**
     * The sweep's candidates (research R13): unlocked, never tried first, then the longest since tried,
     * then oldest stored, on the partial index of {@code FAILED} rows in that order
     * ({@code hearing_share_sweep_ix}).
     */
    private static final String SWEEP_CANDIDATES = """
            SELECT share_id, hearing_id, hearing_day, projection_attempts FROM hearing_share
             WHERE projection_status = 'FAILED'
               AND (projection_version < :currentVersion
                    OR (projection_reason LIKE 'UNEXPECTED%' AND projection_attempts < :maxAttempts))
             ORDER BY projection_tried_at NULLS FIRST, stored_seq
             LIMIT :limit
            """;

    /** The sweep's try, alone: no lock but the row's own, and no other column. */
    private static final String SET_PROJECTION_TRIED = """
            UPDATE hearing_share SET projection_tried_at = now() WHERE share_id = :shareId
            """;

    private static final String PAYLOAD_TEXT = """
            SELECT payload_text FROM hearing_share_payload WHERE share_id = :shareId
            """;

    /** Taken after the day lock, the store transaction's lock order. */
    private static final String LOCK_SHARE = """
            SELECT projection_status = 'FAILED' AND projection_attempts = :attempts AS as_selected
              FROM hearing_share
             WHERE share_id = :shareId
               FOR UPDATE
            """;

    private static final String SET_EXTRACTED = """
            UPDATE hearing_share
               SET is_reshare = :reshare, court_centre_id = :courtCentreId, court_room_id = :courtRoomId,
                   lja_code = :ljaCode, jurisdiction_type = :jurisdictionType, is_sjp = :sjp,
                   is_group_proceedings = :groupProceedings, youth_court_id = :youthCourtId,
                   any_subject_is_youth = :anySubjectIsYouth, projection_status = 'OK', projection_reason = NULL,
                   projection_version = :projectionVersion, projection_attempts = projection_attempts + 1,
                   projected_at = clock_timestamp()
             WHERE share_id = :shareId
            """;

    private static final String SET_FAILED_AGAIN = """
            UPDATE hearing_share
               SET projection_reason = :projectionReason, projection_version = :projectionVersion,
                   projection_attempts = projection_attempts + 1, projected_at = clock_timestamp()
             WHERE share_id = :shareId
            """;

    /** SQLSTATE class 22, data exception: {@code jsonb} refused the text (e.g. 22003, 22P05). */
    private static final String DATA_EXCEPTION_CLASS = "22";

    private static final String SHARE_ID = "shareId";

    private static final String HEARING_ID = "hearingId";

    private static final String HEARING_DAY = "hearingDay";

    private static final String SHARED_AT = "sharedAt";

    private static final String PROJECTION_VERSION = "projectionVersion";

    private static final String PROJECTION_REASON = "projectionReason";

    private static final String ANY_SUBJECT_IS_YOUTH = "anySubjectIsYouth";

    private final JdbcClient jdbc;

    private final TransactionOperations storeTransaction;

    private final JdbcReceiptStore receipts;

    private final ShareChain chain;

    private final YouthFlags youth;

    private final Timeouts timeouts;

    /**
     * Creates the store.
     *
     * @param jdbc             the database
     * @param storeTransaction the store transaction, bounded by its timeout
     * @param receipts         the receipt table, marked inside the store transaction
     * @param timeouts         the PostgreSQL timeouts set for each store transaction
     */
    public JdbcShareStore(final JdbcClient jdbc, final TransactionOperations storeTransaction,
            final JdbcReceiptStore receipts, final Timeouts timeouts) {
        this.jdbc = jdbc;
        this.storeTransaction = storeTransaction;
        this.receipts = receipts;
        this.chain = new ShareChain(jdbc);
        this.youth = new YouthFlags(jdbc);
        this.timeouts = timeouts;
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
            return storeTransaction.execute(status -> storeLocked(request, status));
        } catch (final DataAccessException | TransactionException failure) {
            throw RetryableFailures.classify(IntakeStage.STORE, failure);
        }
    }

    private StoreResult storeLocked(final StoreRequest request, final TransactionStatus status) {
        final ShareIdentity identity = request.identity();
        setTimeouts();
        lockDay(identity.hearingId(), identity.hearingDay());
        final ShareChain.Place place = chain.place(identity);
        final Optional<Instant> storedAt = insertShare(request, place);
        final StoreResult result;
        if (storedAt.isPresent()) {
            final boolean parsed = NulSafety.isJsonbSafe(request.text()) && insertPayloadParsed(request, status);
            if (!parsed) {
                insertPayload(request, null);
            }
            if (request.projection() instanceof Projection.Extracted extracted) {
                insertDefendants(request.shareId(), extracted);
            }
            chain.join(identity, request.shareId(), place);
            youth.recompute(identity.hearingId(), identity.hearingDay());
            settle(receipts.markStored(request.messageId(), request.shareId()));
            result = new StoreResult.Stored(request.shareId(), storedAt.get(), place.isLate(), !parsed);
        } else {
            final UUID existing = existingShare(identity);
            settle(receipts.markDuplicate(request.messageId(), existing));
            result = new StoreResult.Duplicate(existing);
        }
        return result;
    }

    @Override
    public List<SweepCandidate> sweepCandidates(final int currentVersion, final int maxAttempts, final int limit) {
        return jdbc.sql(SWEEP_CANDIDATES)
                .param("currentVersion", currentVersion)
                .param("maxAttempts", maxAttempts)
                .param("limit", limit)
                .query((row, rowNumber) -> new SweepCandidate(row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class), row.getObject(3, LocalDate.class), row.getInt(4)))
                .list();
    }

    /**
     * {@inheritDoc}
     *
     * @throws uk.gov.hmcts.cp.resultsstore.application.RetryableIntakeException when the database read
     *         fails, classified by its SQLSTATE: an operational failure, not the row's
     * @throws IncorrectResultSizeDataAccessException when the share has no payload row: the row's own
     *         failure, thrown as it is
     */
    @Override
    public String payloadText(final UUID shareId) {
        try {
            return jdbc.sql(PAYLOAD_TEXT).param(SHARE_ID, shareId).query(String.class).single();
        } catch (final IncorrectResultSizeDataAccessException missing) {
            throw missing;
        } catch (final DataAccessException failure) {
            throw RetryableFailures.classify(IntakeStage.STORE, failure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bounded by the store transaction's own timeouts. A failure is not classified: it is the
     * sweep's, not an intake's, and the sweep counts it as the row's {@code error}.
     */
    @Override
    public SweepRowOutcome recordReextraction(final SweepCandidate candidate, final Projection projection,
            final int version) {
        return storeTransaction.execute(status -> reextractLocked(candidate, projection, version));
    }

    /**
     * {@inheritDoc}
     *
     * <p>One short transaction bounded by the store's timeouts. It takes the share row's lock alone,
     * holding no other, so it cannot deadlock with a transaction that takes the day lock first.
     */
    @Override
    public void recordSweepAttempt(final UUID shareId) {
        storeTransaction.executeWithoutResult(status -> {
            setTimeouts();
            jdbc.sql(SET_PROJECTION_TRIED).param(SHARE_ID, shareId).update();
        });
    }

    private SweepRowOutcome reextractLocked(final SweepCandidate candidate, final Projection projection,
            final int version) {
        setTimeouts();
        lockDay(candidate.hearingId(), candidate.hearingDay());
        final boolean asSelected = jdbc.sql(LOCK_SHARE)
                .param(SHARE_ID, candidate.shareId())
                .param("attempts", candidate.attempts())
                .query(Boolean.class)
                .single();
        final SweepRowOutcome outcome;
        if (asSelected) {
            outcome = switch (projection) {
                case Projection.Extracted extracted -> fixed(candidate, extracted, version);
                case Projection.Failed failed -> failedAgain(candidate.shareId(), failed, version);
            };
        } else {
            outcome = SweepRowOutcome.SKIPPED;
        }
        return outcome;
    }

    private SweepRowOutcome fixed(final SweepCandidate candidate, final Projection.Extracted extracted,
            final int version) {
        withKeyDetails(jdbc.sql(SET_EXTRACTED), extracted.keyDetails())
                .param(ANY_SUBJECT_IS_YOUTH, extracted.anySubjectIsYouth())
                .param(PROJECTION_VERSION, version)
                .param(SHARE_ID, candidate.shareId())
                .update();
        // A FAILED share was stored with no defendant rows, so these are its first.
        insertDefendants(candidate.shareId(), extracted);
        youth.recompute(candidate.hearingId(), candidate.hearingDay());
        return SweepRowOutcome.FIXED;
    }

    private SweepRowOutcome failedAgain(final UUID shareId, final Projection.Failed failed, final int version) {
        jdbc.sql(SET_FAILED_AGAIN)
                .param(PROJECTION_REASON, failed.reason())
                .param(PROJECTION_VERSION, version)
                .param(SHARE_ID, shareId)
                .update();
        return SweepRowOutcome.FAILED_AGAIN;
    }

    private void setTimeouts() {
        jdbc.sql(SET_TIMEOUTS)
                .param("lockTimeout", milliseconds(timeouts.lock()))
                .param("statementTimeout", milliseconds(timeouts.statement()))
                .param("idleInTransactionTimeout", milliseconds(timeouts.idleInTransaction()))
                .query()
                .singleRow();
    }

    /** Takes the hearing-day lock, creating the day row for its first share (research R10). */
    private void lockDay(final UUID hearingId, final LocalDate hearingDay) {
        jdbc.sql(INSERT_DAY).param(HEARING_ID, hearingId).param(HEARING_DAY, hearingDay).update();
        jdbc.sql(LOCK_DAY).param(HEARING_ID, hearingId).param(HEARING_DAY, hearingDay).query(Integer.class).single();
    }

    private Optional<Instant> insertShare(final StoreRequest request, final ShareChain.Place place) {
        final ShareIdentity identity = request.identity();
        return withKeyDetails(jdbc.sql(INSERT_SHARE), keyDetails(request.projection()))
                .param(SHARE_ID, request.shareId())
                .param(HEARING_ID, identity.hearingId())
                .param(HEARING_DAY, identity.hearingDay())
                .param(SHARED_AT, utc(identity.sharedAt()))
                .param("sharedDayLondon", request.sharedDays().london())
                .param("sharedDayUtc", request.sharedDays().utc())
                .param("checksum", request.checksum())
                .param(ANY_SUBJECT_IS_YOUTH, anySubjectIsYouth(request.projection()))
                .param("predecessor", place.predecessor())
                .param("outOfOrder", place.isLate())
                .param("projectionStatus", request.projection().status().name())
                .param(PROJECTION_REASON, reason(request.projection()))
                .param(PROJECTION_VERSION, KeyDetailsExtractor.EXTRACTOR_VERSION)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /** Binds the eight key-detail columns' parameters, shared by the insert and the sweep's update. */
    private static JdbcClient.StatementSpec withKeyDetails(final JdbcClient.StatementSpec statement,
            final KeyDetails details) {
        return statement
                .param("reshare", details.reshare())
                .param("courtCentreId", details.courtCentreId())
                .param("courtRoomId", details.courtRoomId())
                .param("ljaCode", details.ljaCode())
                .param("jurisdictionType", details.jurisdictionType())
                .param("sjp", details.sjp())
                .param("groupProceedings", details.groupProceedings())
                .param("youthCourtId", details.youthCourtId());
    }

    /**
     * Writes the payload row with its parsed copy, under a savepoint. A data exception (SQLSTATE class
     * 22) can only be the {@code jsonb} conversion refusing the text, e.g. a number beyond its range:
     * the savepoint is rolled back and the outcome is "not parsed", which the caller records. Any other
     * failure is thrown and rolls the whole transaction back.
     *
     * @return whether the row was written with its parsed copy
     */
    private boolean insertPayloadParsed(final StoreRequest request, final TransactionStatus status) {
        final Object savepoint = status.createSavepoint();
        boolean parsed;
        try {
            insertPayload(request, request.text());
            parsed = true;
        } catch (final DataAccessException failure) {
            if (!isDataException(failure)) {
                throw failure;
            }
            status.rollbackToSavepoint(savepoint);
            parsed = false;
        }
        if (parsed) {
            status.releaseSavepoint(savepoint);
        }
        return parsed;
    }

    private void insertPayload(final StoreRequest request, final String parsedCopy) {
        jdbc.sql(INSERT_PAYLOAD)
                .param(SHARE_ID, request.shareId())
                .param("text", request.text())
                .param("textBytes", request.text().getBytes(StandardCharsets.UTF_8).length)
                .param("parsedCopy", parsedCopy)
                .update();
    }

    private static boolean isDataException(final DataAccessException failure) {
        final String sqlState = RetryableFailures.sqlState(failure);
        return sqlState != null && sqlState.startsWith(DATA_EXCEPTION_CLASS);
    }

    private void insertDefendants(final UUID shareId, final Projection.Extracted extracted) {
        for (final DefendantRef defendant : extracted.defendants()) {
            jdbc.sql(INSERT_DEFENDANT)
                    .param(SHARE_ID, shareId)
                    .param("caseId", defendant.caseId())
                    .param("defendantId", defendant.defendantId())
                    .param("masterDefendantId", defendant.masterDefendantId())
                    .update();
        }
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
                : KeyDetails.NONE;
    }

    private static Boolean anySubjectIsYouth(final Projection projection) {
        return projection instanceof Projection.Extracted extracted ? extracted.anySubjectIsYouth() : null;
    }

    private static String reason(final Projection projection) {
        return projection instanceof Projection.Failed failed ? failed.reason() : null;
    }

    /** A PostgreSQL duration setting, in milliseconds with its unit. */
    private static String milliseconds(final Duration timeout) {
        return timeout.toMillis() + "ms";
    }

    /** {@code timestamptz} bound as a {@code java.time} value at UTC. */
    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * The PostgreSQL timeouts of one store transaction ({@code resultsstore.intake.store.*}).
     *
     * @param lock              {@code lock_timeout}
     * @param statement         {@code statement_timeout}
     * @param idleInTransaction {@code idle_in_transaction_session_timeout}
     */
    public record Timeouts(Duration lock, Duration statement, Duration idleInTransaction) {

        /** The defaults of contracts/configuration.md. */
        public static final Timeouts DEFAULTS =
                new Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(10));
    }
}
