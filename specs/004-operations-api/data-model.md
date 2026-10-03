# Data model: Operations API

**Feature**: `004-operations-api` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

004 adds one migration, `V6__operations.sql`: two rerun tables, the sweep's last round per pod, their
guards and indexes, a replaced share guard that lets an `OK` row change only while a pending rerun item
names it, and three indexes for the operations reads. V1 to V5 are not edited (V5 is spec 003's
`V5__read_api.sql`). Every rule of
[../001-share-intake/contracts/schema.md](../001-share-intake/contracts/schema.md),
[../002-enrichment/contracts/schema.md](../002-enrichment/contracts/schema.md) and
[../003-read-api/contracts/schema.md](../003-read-api/contracts/schema.md) still binds, except the one
this file changes on purpose: an `OK` share's key details and `projection_*` columns may now change while
a pending rerun item names it.

## V6 DDL (in full)

```sql
-- V6__operations.sql (spec 004): operator rerun requests the extraction sweep works, the sweep's last
-- round per pod, and the indexes the operations reads need. A rerun never writes a share at request
-- time. The sweep rewrites an OK share in place, from its stored working copy, only while a pending
-- rerun item names it; the share stays OK (constitution I, 2.3.0).

-- 1. Rerun requests. One row per accepted request, written last in the request's transaction, after
--    its items (the items' foreign key to this table is checked at commit), so its counts are the
--    counts of the statements that inserted the items. reason and requested_by are staff-entered or
--    identifying data: never logged, never served. No delete guard: a later retention or erasure spec
--    removes rows (spec 004 D-RERUN-ERASURE).
CREATE TABLE extraction_rerun (
    rerun_id              UUID        NOT NULL,   -- random, made in Java
    selector_kind         TEXT        NOT NULL,
    selector              JSONB       NOT NULL,   -- the canonical selector (ids sorted, instants UTC)
    selector_sha256       CHAR(64)    NOT NULL,   -- SHA-256 over the canonical selector's text
    reason                TEXT        NOT NULL,   -- the operator's words, trimmed
    requested_by          UUID        NOT NULL,   -- the operator's CJSCPPUID
    requested_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    matched_count         INTEGER     NOT NULL,
    already_pending_count INTEGER     NOT NULL,   -- matched shares pending under another open request
    unknown_share_count   INTEGER     NOT NULL,   -- SHARE_IDS only: ids the store does not hold
    status                TEXT        NOT NULL,
    finished_at           TIMESTAMPTZ NULL,
    CONSTRAINT extraction_rerun_pk PRIMARY KEY (rerun_id),
    CONSTRAINT extraction_rerun_kind_ck
        CHECK (selector_kind IN ('STORED_RANGE', 'HEARING_IDS', 'SHARE_IDS')),
    CONSTRAINT extraction_rerun_sha256_ck CHECK (selector_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT extraction_rerun_reason_ck CHECK (char_length(reason) BETWEEN 10 AND 500),
    CONSTRAINT extraction_rerun_counts_ck
        CHECK (matched_count >= 0 AND already_pending_count BETWEEN 0 AND matched_count
               AND unknown_share_count >= 0),
    CONSTRAINT extraction_rerun_unknown_ck CHECK (selector_kind = 'SHARE_IDS' OR unknown_share_count = 0),
    CONSTRAINT extraction_rerun_status_ck CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT extraction_rerun_finished_ck CHECK ((status = 'DONE') = (finished_at IS NOT NULL))
);
-- Idempotency: one OPEN request per selector; the request insert uses it as its ON CONFLICT arbiter.
CREATE UNIQUE INDEX extraction_rerun_open_selector_ux ON extraction_rerun (selector_sha256) WHERE status = 'OPEN';
-- A repeat's look-up: the newest request with this selector, OPEN or DONE.
CREATE INDEX extraction_rerun_selector_ix ON extraction_rerun (selector_sha256, requested_at);
-- The status's most recent requests.
CREATE INDEX extraction_rerun_requested_ix ON extraction_rerun (requested_at);

-- 2. Rerun items. One row per share per request. attempts counts operational failures only (a
--    database read or write that failed); at resultsstore.sweep.rerun-max-attempts the item is
--    ABANDONED. tried_at is stamped when a sweep claims the item.
CREATE TABLE extraction_rerun_item (
    rerun_id   UUID        NOT NULL,
    share_id   UUID        NOT NULL,
    queued_seq BIGINT      GENERATED ALWAYS AS IDENTITY,   -- first in, first out across requests
    state      TEXT        NOT NULL DEFAULT 'PENDING',
    outcome    TEXT        NULL,
    attempts   INTEGER     NOT NULL DEFAULT 0,
    tried_at   TIMESTAMPTZ NULL,
    done_at    TIMESTAMPTZ NULL,
    CONSTRAINT extraction_rerun_item_pk PRIMARY KEY (rerun_id, share_id),
    -- Checked at commit: the request row is written after its items, in the same transaction.
    CONSTRAINT extraction_rerun_item_rerun_fk FOREIGN KEY (rerun_id) REFERENCES extraction_rerun (rerun_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT extraction_rerun_item_share_fk FOREIGN KEY (share_id) REFERENCES hearing_share (share_id),
    CONSTRAINT extraction_rerun_item_state_ck CHECK (state IN ('PENDING', 'DONE')),
    CONSTRAINT extraction_rerun_item_outcome_ck CHECK (outcome IS NULL OR outcome IN
        ('REEXTRACTED', 'UNCHANGED', 'FIXED', 'FAILED_AGAIN', 'KEPT', 'YOUTH_KEPT', 'YOUTH_RAISE_HELD',
         'NEWER_KEPT', 'ABANDONED')),
    CONSTRAINT extraction_rerun_item_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT extraction_rerun_item_done_ck
        CHECK ((state = 'DONE') = (outcome IS NOT NULL) AND (state = 'DONE') = (done_at IS NOT NULL))
);
-- A share waits in at most one request. The item insert's ON CONFLICT arbiter, and the share guard's
-- look-up (hearing_share_rerun_guard below).
CREATE UNIQUE INDEX extraction_rerun_item_one_pending_ux
    ON extraction_rerun_item (share_id) WHERE state = 'PENDING';
-- The sweep's queue: never claimed first, then the oldest claim, then first queued.
CREATE INDEX extraction_rerun_item_queue_ix
    ON extraction_rerun_item (tried_at NULLS FIRST, queued_seq) WHERE state = 'PENDING';
-- Closing finished requests; pending counts per request.
CREATE INDEX extraction_rerun_item_pending_ix ON extraction_rerun_item (rerun_id) WHERE state = 'PENDING';
-- The status's held and abandoned items, newest first.
CREATE INDEX extraction_rerun_item_attention_ix
    ON extraction_rerun_item (outcome, done_at) WHERE outcome IN ('YOUTH_RAISE_HELD', 'ABANDONED');

-- 3. Guards on the rerun tables (UPDATE only; no DELETE guard, see 1).
CREATE FUNCTION extraction_rerun_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.rerun_id, NEW.selector_kind, NEW.selector, NEW.selector_sha256, NEW.reason, NEW.requested_by,
        NEW.requested_at, NEW.matched_count, NEW.already_pending_count, NEW.unknown_share_count)
            IS DISTINCT FROM
       (OLD.rerun_id, OLD.selector_kind, OLD.selector, OLD.selector_sha256, OLD.reason, OLD.requested_by,
        OLD.requested_at, OLD.matched_count, OLD.already_pending_count, OLD.unknown_share_count) THEN
        RAISE EXCEPTION 'extraction_rerun_fixed_columns_guard: a rerun request never changes'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'DONE' AND (NEW.status, NEW.finished_at) IS DISTINCT FROM (OLD.status, OLD.finished_at) THEN
        RAISE EXCEPTION 'extraction_rerun_done_guard: a finished request stays finished'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION extraction_rerun_item_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.rerun_id, NEW.share_id, NEW.queued_seq) IS DISTINCT FROM (OLD.rerun_id, OLD.share_id, OLD.queued_seq) THEN
        RAISE EXCEPTION 'extraction_rerun_item_fixed_columns_guard: an item''s key never changes'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.state = 'DONE' AND (NEW.state, NEW.outcome, NEW.attempts, NEW.tried_at, NEW.done_at)
            IS DISTINCT FROM (OLD.state, OLD.outcome, OLD.attempts, OLD.tried_at, OLD.done_at) THEN
        RAISE EXCEPTION 'extraction_rerun_item_done_guard: a done item never changes'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER extraction_rerun_guard_tg
    BEFORE UPDATE ON extraction_rerun
    FOR EACH ROW EXECUTE FUNCTION extraction_rerun_guard();
CREATE TRIGGER extraction_rerun_item_guard_tg
    BEFORE UPDATE ON extraction_rerun_item
    FOR EACH ROW EXECUTE FUNCTION extraction_rerun_item_guard();

-- 4. The share guard, replaced (the trigger hearing_share_guard_tg of V3 stays attached). The
--    fixed-columns branch is V3's, unchanged. When any key detail or projection_* column changes,
--    the checks below run in this order, each naming itself:
--    hearing_share_projection_version_guard, any row: the extractor version never goes down, the
--      attempts go up, the extraction time never goes back (a rolling deploy's older pod cannot
--      overwrite a newer reading);
--    for an OK row only:
--    hearing_share_projection_guard: it stays OK (never moved to FAILED);
--    hearing_share_youth_guard: a youth subject, once TRUE, stays TRUE (YouthFlags relies on it);
--    hearing_share_rerun_guard: a pending rerun item names the row. This proves that a pending item
--      names the row, not that an operator asked: any session that can insert an item can then
--      rewrite the row while the item is pending (spec 004 D-RERUN-GUARD).
--    A FAILED row's changes are the sweep's retry, as in V3. projection_tried_at, the chain columns and
--    day_youth_seen are not listed, as in V3.
CREATE OR REPLACE FUNCTION hearing_share_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.share_id, NEW.hearing_id, NEW.hearing_day, NEW.shared_at, NEW.shared_day_london,
        NEW.shared_day_utc, NEW.stored_at, NEW.stored_seq, NEW.payload_sha256, NEW.arrived_out_of_order,
        NEW.enrichment_applied)
            IS DISTINCT FROM
       (OLD.share_id, OLD.hearing_id, OLD.hearing_day, OLD.shared_at, OLD.shared_day_london,
        OLD.shared_day_utc, OLD.stored_at, OLD.stored_seq, OLD.payload_sha256, OLD.arrived_out_of_order,
        OLD.enrichment_applied) THEN
        RAISE EXCEPTION 'hearing_share_fixed_columns_guard: a share''s facts never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.is_reshare, NEW.court_centre_id, NEW.court_room_id, NEW.lja_code, NEW.jurisdiction_type,
        NEW.is_sjp, NEW.is_group_proceedings, NEW.youth_court_id, NEW.any_subject_is_youth,
        NEW.projection_status, NEW.projection_reason, NEW.projection_version, NEW.projection_attempts,
        NEW.projected_at)
            IS DISTINCT FROM
       (OLD.is_reshare, OLD.court_centre_id, OLD.court_room_id, OLD.lja_code, OLD.jurisdiction_type,
        OLD.is_sjp, OLD.is_group_proceedings, OLD.youth_court_id, OLD.any_subject_is_youth,
        OLD.projection_status, OLD.projection_reason, OLD.projection_version, OLD.projection_attempts,
        OLD.projected_at) THEN
        IF NEW.projection_version < OLD.projection_version
           OR NEW.projection_attempts <= OLD.projection_attempts
           OR NEW.projected_at < OLD.projected_at THEN
            RAISE EXCEPTION 'hearing_share_projection_version_guard: an extraction is counted and never goes back'
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF OLD.projection_status = 'OK' THEN
            IF NEW.projection_status <> 'OK' THEN
                RAISE EXCEPTION 'hearing_share_projection_guard: an OK share stays OK'
                    USING ERRCODE = 'restrict_violation';
            END IF;
            IF OLD.any_subject_is_youth IS TRUE AND NEW.any_subject_is_youth IS NOT TRUE THEN
                RAISE EXCEPTION 'hearing_share_youth_guard: a youth subject, once true, stays true'
                    USING ERRCODE = 'restrict_violation';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM extraction_rerun_item i
                            WHERE i.share_id = NEW.share_id AND i.state = 'PENDING') THEN
                RAISE EXCEPTION 'hearing_share_rerun_guard: an OK share is re-read only while a pending rerun item names it'
                    USING ERRCODE = 'restrict_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- 5. The sweep's last round, one row per pod (resultsstore.sweep.pod-name), upserted after every round,
--    empty rounds included. Times are the database's clock (clock_timestamp() in the upsert; the start
--    is the finish minus the round's measured length). Rows older than
--    resultsstore.operations.status.pod-recent are deleted by the same statement, so pods that a
--    rollout replaced do not pile up. No guard: an operational record, not evidence.
CREATE TABLE sweep_round (
    pod               TEXT        NOT NULL,
    extractor_version INTEGER     NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL,
    rows_failed       INTEGER     NOT NULL,   -- FAILED rows worked
    rows_rerun        INTEGER     NOT NULL,   -- rerun items worked
    rows_fixed        INTEGER     NOT NULL,   -- FIXED, either path
    rows_failed_again INTEGER     NOT NULL,   -- FAILED_AGAIN, either path
    rows_error        INTEGER     NOT NULL,   -- operational errors, either path
    rows_cancelled    INTEGER     NOT NULL,
    last_worked_at    TIMESTAMPTZ NULL,       -- the last round on this pod that worked any row
    CONSTRAINT sweep_round_pk PRIMARY KEY (pod),
    CONSTRAINT sweep_round_pod_ck CHECK (pod ~ '^[a-z0-9][a-z0-9.-]{0,252}$'),
    CONSTRAINT sweep_round_version_ck CHECK (extractor_version >= 1),
    CONSTRAINT sweep_round_clock_ck CHECK (finished_at >= started_at),
    CONSTRAINT sweep_round_counts_ck CHECK (LEAST(rows_failed, rows_rerun, rows_fixed, rows_failed_again,
                                                  rows_error, rows_cancelled) >= 0)
);
CREATE INDEX sweep_round_finished_ix ON sweep_round (finished_at);

-- 6. Operations reads.
-- Daily reconciliation: receipts first received in a London day (event_receipt_open_ix covers
-- RECEIVED rows only).
CREATE INDEX event_receipt_first_received_ix ON event_receipt (first_received_at);
-- R1: receipts still RECEIVED, by their last delivery.
CREATE INDEX event_receipt_stale_ix ON event_receipt (last_received_at) WHERE status = 'RECEIVED';
-- Daily reconciliation by stored day, and the rerun's stored-range selector.
CREATE INDEX hearing_share_stored_at_ix ON hearing_share (stored_at);

COMMENT ON TABLE extraction_rerun IS 'Operator rerun requests (spec 004)';
COMMENT ON TABLE extraction_rerun_item IS 'One share named by one rerun request; worked by the sweep (spec 004)';
COMMENT ON TABLE sweep_round IS 'Each pod''s last extraction sweep round (spec 004)';
```

Locking: the three indexes on `event_receipt` and `hearing_share` are plain `CREATE INDEX` inside
Flyway's transaction, which blocks writes to those tables while they build. V6 deploys before go-live;
the `CONCURRENTLY` fallback is in plan.md *Risks*.

## What V6 does not change

| Item | Status |
|---|---|
| `hearing_share`, `hearing_share_payload`, `share_defendant`, `hearing_day_head`, `event_receipt` columns and `CHECK`s | unchanged. `hearing_share_failed_is_empty_ck` stays: it constrains `FAILED` rows only, and a rerun never moves a row to `FAILED` |
| The fixed-columns branch of `hearing_share_guard` | byte-identical to V3 (`OperationsSchemaIT` re-runs V3's fixed-column refusals) |
| `share_defendant_guard_tg` (insert-only) | unchanged: the rerun adds missing rows with `ON CONFLICT DO NOTHING`, never removes |
| `hearing_share_payload_guard_tg` | unchanged |
| V5's trigger and indexes | unchanged |

## Rerun request: the statements (shape; the constants live in `JdbcRerunRequests`)

One transaction (`resultsstore.operations.rerun.request.*` timeouts, set with `set_config(..., TRUE)` as
`JdbcShareStore` does). Before it, one autocommit read looks for an open request with the same hash; if
there is one, the answer is a repeat and nothing else runs.

1. Stored range only: `SELECT now() - make_interval(secs => :lagSeconds) >= :storedTo`. False → the
   transaction ends with "range not settled" (`400 range_invalid`).
2. Chunks, until a chunk matches fewer than `chunk-size` rows:

   ```sql
   WITH matched AS (
       SELECT s.share_id FROM hearing_share s
        WHERE <selector predicate> AND s.share_id > :afterShareId
        ORDER BY s.share_id
        LIMIT :chunkSize
   ), queued AS (
       INSERT INTO extraction_rerun_item (rerun_id, share_id)
       SELECT :rerunId, m.share_id FROM matched m
       ON CONFLICT (share_id) WHERE state = 'PENDING' DO NOTHING
       RETURNING share_id
   )
   SELECT (SELECT count(*) FROM matched)          AS matched,
          (SELECT count(*) FROM queued)           AS queued,
          (SELECT max(m.share_id) FROM matched m) AS last_share_id
   ```

   Selector predicates: `s.stored_at >= :storedFrom AND s.stored_at < :storedTo`
   (`hearing_share_stored_at_ix`); `s.hearing_id = ANY(:hearingIds)` (`hearing_share_identity_uk`);
   `s.share_id = ANY(:shareIds)` (`hearing_share_pk`). Each chunk's `matched` and `queued` come from the
   statement that inserted its items (ruling C1). When the running `matched` passes `max-matched`, the
   transaction is rolled back ("too wide", `400 selector_too_wide`).
3. The request row, last:

   ```sql
   INSERT INTO extraction_rerun (rerun_id, selector_kind, selector, selector_sha256, reason, requested_by,
                                 matched_count, already_pending_count, unknown_share_count, status, finished_at)
   VALUES (:rerunId, :kind, CAST(:selector AS jsonb), :sha256, :reason, :requestedBy,
           :matched, :matched - :queued, :unknown,
           CASE WHEN :matched = 0 THEN 'DONE' ELSE 'OPEN' END,
           CASE WHEN :matched = 0 THEN clock_timestamp() END)
   ON CONFLICT (selector_sha256) WHERE status = 'OPEN' DO NOTHING
   RETURNING rerun_id
   ```

   No row returned → an identical request opened first: the transaction is rolled back (its items
   go), then `SELECT … FROM extraction_rerun WHERE selector_sha256 = :sha256 AND status IN ('OPEN',
   'DONE') ORDER BY requested_at DESC LIMIT 1`. `OPEN` → a repeat of that request. `DONE` (it closed in
   between) → the whole request is tried once more; a second conflict answers a repeat of the request
   then found.

Locks: inserting an item takes `FOR KEY SHARE` on its `hearing_share` row (the foreign key) until
commit. The sweep's share lock is `FOR NO KEY UPDATE` and intake's youth propagation is a non-key
update, so neither waits on a request being written.

## The sweep's rerun statements (shape; the constants live in `JdbcShareStore`)

**Claim** (autocommit, one statement):

```sql
UPDATE extraction_rerun_item i SET tried_at = now()
  FROM hearing_share s
 WHERE s.share_id = i.share_id
   AND (i.rerun_id, i.share_id) IN (
        SELECT q.rerun_id, q.share_id FROM extraction_rerun_item q
         WHERE q.state = 'PENDING'
         ORDER BY q.tried_at NULLS FIRST, q.queued_seq
         LIMIT :limit
           FOR UPDATE SKIP LOCKED)
RETURNING i.rerun_id, i.share_id, s.hearing_id, s.hearing_day, i.attempts
```

**Write** (one store transaction, `setTimeouts()` first, the store's lock order):

1. `lockDay(hearingId, hearingDay)` (as today).
2. `SELECT state FROM extraction_rerun_item WHERE rerun_id = :r AND share_id = :s FOR UPDATE`. Not
   `PENDING` → `SKIPPED`, nothing else.
3. `SELECT projection_status, projection_version, <the nine key details> FROM hearing_share
   WHERE share_id = :s FOR NO KEY UPDATE`, and the share's defendant rows.
4. Decide (spec FR-018) and write:
   - `REEXTRACTED`: `UPDATE hearing_share SET <key details>, any_subject_is_youth = :y,
     projection_version = :v, projection_attempts = projection_attempts + 1,
     projected_at = GREATEST(clock_timestamp(), projected_at) WHERE share_id = :s` (status stays `OK`,
     reason stays NULL); then `INSERT INTO share_defendant … ON CONFLICT (share_id, case_id,
     defendant_id) DO NOTHING` for each defendant read; then `YouthFlags.recompute`.
   - `UNCHANGED` with an older stored version: `UPDATE hearing_share SET projection_version = :v,
     projection_attempts = projection_attempts + 1, projected_at = GREATEST(clock_timestamp(),
     projected_at) WHERE share_id = :s`. With the current version: no share write.
   - `FIXED` and `FAILED_AGAIN`: the existing `SET_EXTRACTED` (with defendants and the youth
     recompute) and `SET_FAILED_AGAIN`.
   - `NEWER_KEPT`, `KEPT`, `YOUTH_KEPT`, `YOUTH_RAISE_HELD`: no share write.
5. `UPDATE extraction_rerun_item SET state = 'DONE', outcome = :o, done_at = clock_timestamp()
   WHERE rerun_id = :r AND share_id = :s`, after the share write, so the guard still sees the item
   pending.

**Operational error** (its own short transaction):

```sql
UPDATE extraction_rerun_item
   SET attempts = attempts + 1,
       state    = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DONE' ELSE 'PENDING' END,
       outcome  = CASE WHEN attempts + 1 >= :maxAttempts THEN 'ABANDONED' END,
       done_at  = CASE WHEN attempts + 1 >= :maxAttempts THEN clock_timestamp() END
 WHERE rerun_id = :r AND share_id = :s AND state = 'PENDING'
RETURNING state
```

**Close finished requests** (autocommit, at the end of every round):

```sql
UPDATE extraction_rerun r SET status = 'DONE', finished_at = clock_timestamp()
 WHERE r.status = 'OPEN'
   AND NOT EXISTS (SELECT 1 FROM extraction_rerun_item i WHERE i.rerun_id = r.rerun_id AND i.state = 'PENDING')
```

**Record the round** (`JdbcSweepRounds`, autocommit, one statement):

```sql
WITH pruned AS (
    DELETE FROM sweep_round
     WHERE finished_at < clock_timestamp() - make_interval(secs => :podRecentSeconds) AND pod <> :pod
)
INSERT INTO sweep_round (pod, extractor_version, started_at, finished_at, rows_failed, rows_rerun, rows_fixed,
                         rows_failed_again, rows_error, rows_cancelled, last_worked_at)
VALUES (:pod, :version, clock_timestamp() - make_interval(secs => :roundSeconds), clock_timestamp(),
        :failed, :rerun, :fixed, :failedAgain, :error, :cancelled,
        CASE WHEN :failed + :rerun > 0 THEN clock_timestamp() END)
ON CONFLICT (pod) DO UPDATE
   SET extractor_version = EXCLUDED.extractor_version, started_at = EXCLUDED.started_at,
       finished_at = EXCLUDED.finished_at, rows_failed = EXCLUDED.rows_failed, rows_rerun = EXCLUDED.rows_rerun,
       rows_fixed = EXCLUDED.rows_fixed, rows_failed_again = EXCLUDED.rows_failed_again,
       rows_error = EXCLUDED.rows_error, rows_cancelled = EXCLUDED.rows_cancelled,
       last_worked_at = COALESCE(EXCLUDED.last_worked_at, sweep_round.last_worked_at)
```

## Operations reads (shape; the constants live in `JdbcOperationsQueries`)

Autocommit, read-only, over a dedicated `JdbcTemplate` with a query timeout
(`resultsstore.operations.statement-timeout`). None names `hearing_share_payload` or `message_text`.

**Extraction counts** (on `hearing_share_sweep_ix`, the `FAILED` partial index):

```sql
SELECT count(*) FILTER (WHERE projection_version < :v
                          OR (projection_reason LIKE 'UNEXPECTED%' AND projection_attempts < :max)) AS retryable,
       count(*) FILTER (WHERE projection_version >= :v
                          AND projection_reason LIKE 'UNEXPECTED%' AND projection_attempts >= :max) AS exhausted,
       count(*) FILTER (WHERE projection_version >= :v
                          AND projection_reason NOT LIKE 'UNEXPECTED%')                         AS awaiting_new_extractor
  FROM hearing_share WHERE projection_status = 'FAILED'
```

**Rerun counts**: pending items (`extraction_rerun_item_pending_ix`), open requests
(`extraction_rerun_open_selector_ux`), held and abandoned counts and their newest 51 share ids each
(`extraction_rerun_item_attention_ix`; 51 read, 50 shown, `truncated` when 51 come back).

**Recent requests**: the newest 21 by `requested_at` (20 shown), then for those ids
`SELECT rerun_id, state, outcome, count(*) FROM extraction_rerun_item WHERE rerun_id = ANY(:ids)
GROUP BY rerun_id, state, outcome` on the primary key. Never `reason` or `requested_by`.

**Sweep rounds**: `SELECT pod, extractor_version, started_at, finished_at, rows_*, last_worked_at FROM
sweep_round WHERE finished_at >= now() - make_interval(secs => :podRecentSeconds) ORDER BY finished_at
DESC LIMIT 21`.

**Receipts** (the exact column list; `OperationsSqlTest` holds it):

```sql
SELECT message_id, status, hearing_id, hearing_day, shared_at, attempts, delivery_count,
       first_received_at, last_received_at, settled_at, reason, share_id
  FROM event_receipt
 WHERE hearing_id = :hearingId AND hearing_day = :hearingDay     -- event_receipt_hearing_day_ix (V2)
 ORDER BY first_received_at, message_id
 LIMIT :maxRowsPlusOne
```

and the same columns `WHERE message_id = :messageId` (`event_receipt_pk`).

**Daily receipts**: `SELECT status, count(*) FROM event_receipt WHERE first_received_at >= :from AND
first_received_at < :to GROUP BY status` (`event_receipt_first_received_ix`).

**Daily shares**:

```sql
SELECT count(*)                                                        AS stored,
       count(*) FILTER (WHERE arrived_out_of_order)                    AS out_of_order,
       count(*) FILTER (WHERE projection_status = 'FAILED')            AS extraction_failed,
       count(*) FILTER (WHERE projection_status = 'FAILED' AND projection_version >= :v
                          AND projection_reason LIKE 'UNEXPECTED%' AND projection_attempts >= :max) AS failed_exhausted,
       count(*) FILTER (WHERE projection_version < :v)                 AS stale_version
  FROM hearing_share
 WHERE stored_at >= :from AND stored_at < :to                          -- hearing_share_stored_at_ix
```

**R1**: `SELECT message_id FROM event_receipt WHERE status = 'RECEIVED' AND first_received_at >= :from AND
first_received_at < :to AND last_received_at < now() - make_interval(secs => :giveUpSeconds) ORDER BY
first_received_at, message_id LIMIT 51`, and a `count(*)` with the same predicate (a `RECEIVED`
`event_receipt_stale_ix`, the index V6 adds for it; `OperationsQueriesPlanIT` asserts that index by name).

## In-memory types

| Type | Layer | Holds |
|---|---|---|
| `RerunSelector` | `domain/` | sealed: `StoredRange(from, to)`, `HearingIds(sorted set)`, `ShareIds(sorted set)`; `kind()`, `canonicalText()` |
| `SelectorKind` | `domain/` | `STORED_RANGE`, `HEARING_IDS`, `SHARE_IDS`; `tag()` |
| `RerunReason` | `domain/` | the trimmed reason; refuses length outside 10 to 500 and control characters; `toString()` never prints it |
| `OperatorId` | `domain/` | the operator's UUID; `toString()` never prints it |
| `RerunRowOutcome` | `domain/` | the twelve outcomes of spec FR-018, FR-025, FR-027; `tag()`; `stored()` true for the nine the item `CHECK` lists |
| `OperationsEndpoint` | `domain/` | `RERUN`, `STATUS`, `RECEIPTS`, `RECONCILIATION`; `tag()` |
| `ReconciliationWindow` | `domain/` | the date, the London instants `from` and `to`; refuses a date after London today |
| `RerunRequest`, `RerunAccepted`, `RerunCreation` (sealed: `Created`, `Repeat`, `TooWide`, `RangeNotSettled`) | `application/` | what the service asks the port and what comes back |
| `RerunCandidate` | `application/` | a claimed item: rerun id, share id, hearing id and day, attempts |
| `ExtractionCounts`, `RerunOverview`, `RecentRerun`, `SweepRoundView`, `ReceiptView`, `DailyCounts`, `R1Finding` | `application/` | the operations reads' answers |
| `SweepRoundRecord` | `application/` | what a round reports: pod, version, length, counts |

## Invariants the tests hold

1. An `OK` share's key details and `projection_*` change only while a pending item names it, stay `OK`,
   never lower the version or a `true` youth subject (`OperationsSchemaIT`, one case per guard).
2. A request's counts equal its items: `matched − already_pending_count` = items with its `rerun_id`
   (`JdbcRerunRequestsIT`).
3. At most one pending item per share (`OperationsSchemaIT`, `JdbcRerunRequestsIT`).
4. Each claimed item is written at most once, across pods (`RerunSweepIT`).
5. No operations query names `hearing_share_payload` or `message_text`, and the receipts query names
   exactly its twelve columns (`OperationsSqlTest`).
6. A share's `stored_seq` never changes (V3's fixed-columns branch, unchanged).
