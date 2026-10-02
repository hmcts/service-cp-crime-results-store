# Data model: Share intake

**Feature**: `001-share-intake` | **Date**: 2026-10-02 | **Plan**: [plan.md](plan.md)

Two new Flyway migrations. `V1__create_event_receipt.sql` is never edited (FR-042).

| Migration | Does |
|---|---|
| `V2__reshape_event_receipt.sql` | Refuses to run if V1's table holds rows; drops it and creates the receipt keyed by the broker's message id |
| `V3__create_share_store.sql` | Creates `hearing_day_head`, `hearing_share`, `hearing_share_payload`, `share_defendant` |

Consumer search indexes (court centre, shared day, defendant id and so on) are **not** created
here; spec 003 adds them with the read API. The only indexes in 001 serve the write path, the
sweep and R1.

## Entity overview

```text
event_receipt (one per message) ──share_id (no FK)──▶ hearing_share
hearing_day_head (one per hearing day) ◀──(hearing_id, hearing_day)── hearing_share
hearing_day_head.latest_share_id ──▶ hearing_share
hearing_share.predecessor_share_id ──▶ hearing_share
hearing_share_payload (1:1) ──▶ hearing_share
share_defendant (0..n per share) ──▶ hearing_share
```

`event_receipt.share_id` has no foreign key on purpose: retention is open, and receipts must
outlive shares for reconciliation R1.

## V2__reshape_event_receipt.sql

```sql
-- V1 created event_receipt keyed by the share's identity, and nothing has ever written to it. It
-- cannot hold a message without an identity, and it refuses a redelivery. V1 is not edited; this
-- migration replaces the table, and refuses to run if the table holds any row.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM event_receipt) THEN
        RAISE EXCEPTION 'event_receipt is not empty; V2 refuses to reshape it';
    END IF;
END $$;

DROP TABLE event_receipt;

CREATE TABLE event_receipt (
    message_id        TEXT        NOT NULL,   -- JMSMessageID as given (e.g. 'ID:...'), or 'sha256:<hex>' when absent
    status            TEXT        NOT NULL,
    hearing_id        UUID        NULL,       -- identity is nullable: a non-share may carry none or part of it
    hearing_day       DATE        NULL,
    shared_at         TIMESTAMPTZ NULL,       -- V1's shared_time, renamed to match hearing_share
    attempts          INTEGER     NOT NULL DEFAULT 1,
    delivery_count    INTEGER     NULL,       -- JMSXDeliveryCount last seen
    first_received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_received_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    settled_at        TIMESTAMPTZ NULL,       -- set on the move to an end state
    reason            TEXT        NULL,       -- bounded code, never exception or payload text
    message_text      TEXT        NULL,       -- the text as received; non-shares only
    share_id          UUID        NULL,       -- the stored share (STORED) or the existing one (DUPLICATE)
    CONSTRAINT event_receipt_pk PRIMARY KEY (message_id),
    CONSTRAINT event_receipt_status_ck
        CHECK (status IN ('RECEIVED', 'STORED', 'DUPLICATE', 'UNREADABLE', 'NO_IDENTITY')),
    CONSTRAINT event_receipt_attempts_ck CHECK (attempts >= 1),
    CONSTRAINT event_receipt_delivery_count_ck CHECK (delivery_count IS NULL OR delivery_count >= 0),
    CONSTRAINT event_receipt_settled_ck CHECK ((status = 'RECEIVED') = (settled_at IS NULL)),
    CONSTRAINT event_receipt_reason_length_ck CHECK (reason IS NULL OR char_length(reason) <= 120),
    CONSTRAINT event_receipt_non_share_reason_ck
        CHECK (status NOT IN ('UNREADABLE', 'NO_IDENTITY') OR reason IS NOT NULL),
    CONSTRAINT event_receipt_text_only_for_non_share_ck
        CHECK (message_text IS NULL OR status IN ('UNREADABLE', 'NO_IDENTITY')),
    CONSTRAINT event_receipt_share_id_ck
        CHECK ((status IN ('STORED', 'DUPLICATE')) = (share_id IS NOT NULL)),
    CONSTRAINT event_receipt_identity_whole_ck
        CHECK (status IN ('UNREADABLE', 'NO_IDENTITY')
               OR (hearing_id IS NOT NULL AND hearing_day IS NOT NULL AND shared_at IS NOT NULL))
);

-- R1: receipts still RECEIVED. Partial, so it stays small.
CREATE INDEX event_receipt_open_ix ON event_receipt (first_received_at) WHERE status = 'RECEIVED';
-- Receipts of one hearing day (spec 004's receipts endpoint; reconciliation by identity).
CREATE INDEX event_receipt_hearing_day_ix ON event_receipt (hearing_id, hearing_day);

-- FR-044: the key, the identity and the first arrival never change; the end-state columns change
-- only on the move out of RECEIVED; after that only the delivery details (attempts,
-- last_received_at, delivery_count) do. The CHECKs above hold each row's shape; this holds its history.
CREATE FUNCTION event_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.message_id, NEW.hearing_id, NEW.hearing_day, NEW.shared_at, NEW.first_received_at)
            IS DISTINCT FROM
       (OLD.message_id, OLD.hearing_id, OLD.hearing_day, OLD.shared_at, OLD.first_received_at) THEN
        RAISE EXCEPTION 'event_receipt_fixed_columns_guard: a receipt''s key, identity and first arrival never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'RECEIVED'
       AND (NEW.status, NEW.reason, NEW.message_text, NEW.share_id, NEW.settled_at)
            IS DISTINCT FROM
           (OLD.status, OLD.reason, OLD.message_text, OLD.share_id, OLD.settled_at) THEN
        RAISE EXCEPTION 'event_receipt_settled_guard: a settled receipt keeps its end state'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER event_receipt_guard_tg
    BEFORE UPDATE ON event_receipt
    FOR EACH ROW EXECUTE FUNCTION event_receipt_guard();
```

## V3__create_share_store.sql

```sql
CREATE TABLE hearing_day_head (
    hearing_id      UUID        NOT NULL,
    hearing_day     DATE        NOT NULL,
    latest_share_id UUID        NULL,         -- NULL only inside the transaction that creates the row
    share_count     INTEGER     NOT NULL DEFAULT 0,
    youth_seen      BOOLEAN     NULL,         -- three values: TRUE (sticky), NULL (unknown), FALSE
    first_stored_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT hearing_day_head_pk PRIMARY KEY (hearing_id, hearing_day),
    CONSTRAINT hearing_day_head_count_ck CHECK (share_count >= 0),
    -- latest and count move together, in one UPDATE
    CONSTRAINT hearing_day_head_latest_ck CHECK ((latest_share_id IS NULL) = (share_count = 0))
);

CREATE TABLE hearing_share (
    share_id             UUID        NOT NULL,   -- UUID v5 over 'hearingId|hearingDay|sharedTime' as sent
    hearing_id           UUID        NOT NULL,
    hearing_day          DATE        NOT NULL,
    shared_at            TIMESTAMPTZ NOT NULL,
    shared_day_london    DATE        NOT NULL,   -- worked out in Java (Europe/London)
    shared_day_utc       DATE        NOT NULL,
    stored_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    stored_seq           BIGINT      GENERATED ALWAYS AS IDENTITY,
    payload_sha256       CHAR(64)    NOT NULL,
    -- key details: all nullable; empty while extraction has FAILED
    is_reshare           BOOLEAN     NULL,
    court_centre_id      UUID        NULL,
    court_room_id        UUID        NULL,
    lja_code             TEXT        NULL,
    jurisdiction_type    TEXT        NULL,       -- recorded as stated; no CHECK on its values
    is_sjp               BOOLEAN     NULL,
    is_group_proceedings BOOLEAN     NULL,
    youth_court_id       UUID        NULL,
    any_subject_is_youth BOOLEAN     NULL,
    -- the day's youth flag, propagated under the lock
    day_youth_seen       BOOLEAN     NULL,
    -- chain
    is_latest            BOOLEAN     NOT NULL,
    predecessor_share_id UUID        NULL,
    arrived_out_of_order BOOLEAN     NOT NULL,
    -- enrichment (spec 002); always false in 001
    enrichment_applied   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- extraction
    projection_status    TEXT        NOT NULL,
    projection_reason    TEXT        NULL,
    projection_version   INTEGER     NOT NULL,
    projection_attempts  INTEGER     NOT NULL DEFAULT 1,
    projected_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- retention (open); never set in 001
    expires_at           TIMESTAMPTZ NULL,
    CONSTRAINT hearing_share_pk PRIMARY KEY (share_id),
    CONSTRAINT hearing_share_identity_uk UNIQUE (hearing_id, hearing_day, shared_at),
    CONSTRAINT hearing_share_stored_seq_uk UNIQUE (stored_seq),
    -- the target of the same-day foreign keys below
    CONSTRAINT hearing_share_day_share_uk UNIQUE (hearing_id, hearing_day, share_id),
    CONSTRAINT hearing_share_day_fk FOREIGN KEY (hearing_id, hearing_day)
        REFERENCES hearing_day_head (hearing_id, hearing_day),
    -- the predecessor is a share of the same day (and an earlier one: hearing_share_predecessor_guard)
    CONSTRAINT hearing_share_predecessor_fk FOREIGN KEY (hearing_id, hearing_day, predecessor_share_id)
        REFERENCES hearing_share (hearing_id, hearing_day, share_id),
    CONSTRAINT hearing_share_not_own_predecessor_ck
        CHECK (predecessor_share_id IS NULL OR predecessor_share_id <> share_id),
    CONSTRAINT hearing_share_sha256_ck CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT hearing_share_projection_status_ck CHECK (projection_status IN ('OK', 'FAILED')),
    CONSTRAINT hearing_share_projection_reason_ck
        CHECK ((projection_status = 'FAILED') = (projection_reason IS NOT NULL)),
    CONSTRAINT hearing_share_projection_reason_length_ck
        CHECK (projection_reason IS NULL OR char_length(projection_reason) <= 120),
    CONSTRAINT hearing_share_projection_version_ck CHECK (projection_version >= 1),
    CONSTRAINT hearing_share_projection_attempts_ck CHECK (projection_attempts >= 1),
    CONSTRAINT hearing_share_failed_is_empty_ck
        CHECK (projection_status = 'OK'
               OR (is_reshare IS NULL AND court_centre_id IS NULL AND court_room_id IS NULL
                   AND lja_code IS NULL AND jurisdiction_type IS NULL AND is_sjp IS NULL
                   AND is_group_proceedings IS NULL AND youth_court_id IS NULL
                   AND any_subject_is_youth IS NULL)),
    CONSTRAINT hearing_share_expires_unset_ck CHECK (expires_at IS NULL)
);

-- the latest share is a share of the same day (and is_latest: hearing_day_head_latest_check)
ALTER TABLE hearing_day_head
    ADD CONSTRAINT hearing_day_head_latest_fk FOREIGN KEY (hearing_id, hearing_day, latest_share_id)
        REFERENCES hearing_share (hearing_id, hearing_day, share_id);

-- At most one latest share per hearing day. Not deferrable: clear the old latest first.
CREATE UNIQUE INDEX hearing_share_one_latest_ux
    ON hearing_share (hearing_id, hearing_day) WHERE is_latest;
-- The sweep's scan.
CREATE INDEX hearing_share_failed_ix
    ON hearing_share (stored_seq) WHERE projection_status = 'FAILED';

CREATE TABLE hearing_share_payload (
    share_id     UUID    NOT NULL,
    payload_text TEXT    NOT NULL,   -- the message text exactly as received (envelope with _metadata)
    text_bytes   INTEGER NOT NULL,   -- UTF-8 length of payload_text
    payload_json JSONB   NULL,       -- parsed copy; NULL when jsonb cannot hold it (\u0000); unread in 001
    CONSTRAINT hearing_share_payload_pk PRIMARY KEY (share_id),
    CONSTRAINT hearing_share_payload_share_fk FOREIGN KEY (share_id) REFERENCES hearing_share (share_id),
    CONSTRAINT hearing_share_payload_bytes_ck CHECK (text_bytes = octet_length(payload_text))
);

CREATE TABLE share_defendant (
    share_id            UUID NOT NULL,
    case_id             UUID NOT NULL,   -- hearing.prosecutionCases[].id
    defendant_id        UUID NOT NULL,   -- .defendants[].id
    master_defendant_id UUID NULL,       -- .defendants[].masterDefendantId
    CONSTRAINT share_defendant_pk PRIMARY KEY (share_id, case_id, defendant_id),
    CONSTRAINT share_defendant_share_fk FOREIGN KEY (share_id) REFERENCES hearing_share (share_id)
);

-- FR-044: what may change after insert. Every guard raises restrict_violation (23001) naming
-- itself; the CHECKs above hold each row's shape, these hold its history.

-- Refuses every row of the operation it is attached to: <table>_<update|delete>_guard.
CREATE FUNCTION refuse_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%_%_guard: % rows are never changed by %', TG_TABLE_NAME, lower(TG_OP), TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

-- The day row: only latest_share_id, share_count and youth_seen change.
CREATE FUNCTION hearing_day_head_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.hearing_id, NEW.hearing_day, NEW.first_stored_at)
            IS DISTINCT FROM (OLD.hearing_id, OLD.hearing_day, OLD.first_stored_at) THEN
        RAISE EXCEPTION 'hearing_day_head_fixed_columns_guard: a day row''s key and first store never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

-- The share: is_latest, predecessor_share_id and day_youth_seen change freely; the key details and
-- projection_* only while the row is FAILED (the sweep); nothing else.
CREATE FUNCTION hearing_share_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.share_id, NEW.hearing_id, NEW.hearing_day, NEW.shared_at, NEW.shared_day_london,
        NEW.shared_day_utc, NEW.stored_at, NEW.stored_seq, NEW.payload_sha256, NEW.arrived_out_of_order,
        NEW.enrichment_applied, NEW.expires_at)
            IS DISTINCT FROM
       (OLD.share_id, OLD.hearing_id, OLD.hearing_day, OLD.shared_at, OLD.shared_day_london,
        OLD.shared_day_utc, OLD.stored_at, OLD.stored_seq, OLD.payload_sha256, OLD.arrived_out_of_order,
        OLD.enrichment_applied, OLD.expires_at) THEN
        RAISE EXCEPTION 'hearing_share_fixed_columns_guard: a share''s facts never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.projection_status <> 'FAILED'
       AND (NEW.is_reshare, NEW.court_centre_id, NEW.court_room_id, NEW.lja_code, NEW.jurisdiction_type,
            NEW.is_sjp, NEW.is_group_proceedings, NEW.youth_court_id, NEW.any_subject_is_youth,
            NEW.projection_status, NEW.projection_reason, NEW.projection_version, NEW.projection_attempts,
            NEW.projected_at)
            IS DISTINCT FROM
           (OLD.is_reshare, OLD.court_centre_id, OLD.court_room_id, OLD.lja_code, OLD.jurisdiction_type,
            OLD.is_sjp, OLD.is_group_proceedings, OLD.youth_court_id, OLD.any_subject_is_youth,
            OLD.projection_status, OLD.projection_reason, OLD.projection_version, OLD.projection_attempts,
            OLD.projected_at) THEN
        RAISE EXCEPTION 'hearing_share_projection_guard: key details and projection change only while FAILED'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

-- The predecessor was shared earlier, so the chain can never loop.
CREATE FUNCTION hearing_share_predecessor_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.predecessor_share_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM hearing_share p
                   WHERE p.share_id = NEW.predecessor_share_id AND p.shared_at >= NEW.shared_at) THEN
        RAISE EXCEPTION 'hearing_share_predecessor_earlier_guard: a predecessor is shared before its successor'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- The share a day row names as latest has is_latest. Checked at commit, because the store
-- transaction clears the old latest before it moves the day row on.
CREATE FUNCTION hearing_day_head_latest_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM hearing_day_head h
               JOIN hearing_share s ON s.share_id = h.latest_share_id
               WHERE h.hearing_id = NEW.hearing_id AND h.hearing_day = NEW.hearing_day AND NOT s.is_latest) THEN
        RAISE EXCEPTION 'hearing_day_head_latest_is_latest_guard: a day row names a share that is not latest'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER hearing_day_head_guard_tg
    BEFORE UPDATE ON hearing_day_head
    FOR EACH ROW EXECUTE FUNCTION hearing_day_head_guard();
CREATE TRIGGER hearing_day_head_delete_guard_tg
    BEFORE DELETE ON hearing_day_head
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
CREATE CONSTRAINT TRIGGER hearing_day_head_latest_check_tg
    AFTER INSERT OR UPDATE OF latest_share_id ON hearing_day_head
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION hearing_day_head_latest_check();

CREATE TRIGGER hearing_share_guard_tg
    BEFORE UPDATE ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION hearing_share_guard();
CREATE TRIGGER hearing_share_predecessor_guard_tg
    BEFORE INSERT OR UPDATE OF predecessor_share_id ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION hearing_share_predecessor_guard();
CREATE TRIGGER hearing_share_delete_guard_tg
    BEFORE DELETE ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
CREATE CONSTRAINT TRIGGER hearing_share_latest_check_tg
    AFTER UPDATE OF is_latest ON hearing_share
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION hearing_day_head_latest_check();

-- The payload and the defendant index are insert-only.
CREATE TRIGGER hearing_share_payload_guard_tg
    BEFORE UPDATE OR DELETE ON hearing_share_payload
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
CREATE TRIGGER share_defendant_guard_tg
    BEFORE UPDATE OR DELETE ON share_defendant
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
```

### Database rules mapped to FR-043

| Rule | Enforced by |
|---|---|
| one share per (`hearingId`, `hearingDay`, `sharedTime`) | `hearing_share_identity_uk` |
| one latest share per day | `hearing_share_one_latest_ux` |
| checksum is 64 hex characters | `hearing_share_sha256_ck` |
| message text only on non-share receipts | `event_receipt_text_only_for_non_share_ck` |
| a `FAILED` row has a reason | `hearing_share_projection_reason_ck` |
| `stored_seq` cannot be set by the caller | `GENERATED ALWAYS AS IDENTITY` (an explicit value is refused without `OVERRIDING SYSTEM VALUE`) |
| a day row's latest share and a share's predecessor belong to the same day | `hearing_day_head_latest_fk`, `hearing_share_predecessor_fk` (composite, onto `hearing_share_day_share_uk`) |
| the share a day row names as latest has `is_latest` | `hearing_day_head_latest_check` (constraint triggers, deferred to commit) |
| a predecessor was shared earlier than its successor, so the chain cannot loop | `hearing_share_predecessor_guard` |
| a settled receipt keeps its end state; a receipt's key, identity and first arrival never change | `event_receipt_guard` (`event_receipt_settled_guard`, `event_receipt_fixed_columns_guard`) |
| a share's facts never change; its key details and `projection_*` change only while it is `FAILED`; a day row's key and first store never change | `hearing_share_guard` (`hearing_share_fixed_columns_guard`, `hearing_share_projection_guard`), `hearing_day_head_guard` (`hearing_day_head_fixed_columns_guard`) |
| no share or day row is deleted; payload and defendant rows are insert-only | `refuse_row_change` (`<table>_update_guard` / `<table>_delete_guard`) |
| `expires_at` stays empty | `hearing_share_expires_unset_ck` (a later retention spec drops it) |
| V2 refuses a non-empty V1 table | the `DO` block |

## Entities

### Receipt (`event_receipt`)

One row per message received, keyed by the broker's message id (R3 in research). Written in its
own short transaction before any store work. Holds arrival times, the broker's delivery count, an
attempt count, the share's identity when present, a status, the time it settled, a bounded
reason, the message text (non-shares only), and the share id once stored or found as a duplicate.

### Hearing day (`hearing_day_head`)

One row per (`hearing_id`, `hearing_day`). The lock point for every write to that day. Holds the
latest share, the number of shares stored and the day's youth flag.

### Share (`hearing_share`)

One row per share, i.e. per version of a hearing day. `share_id` is computed (R4); the unique key
on the three identity values is the whole idempotency mechanism. Holds both clocks (`shared_at`;
`stored_at` and `stored_seq`), both calendar days, the checksum, the key details, the youth facts,
the chain columns, the extraction status, and `expires_at` (empty).

### Payload (`hearing_share_payload`)

One row per share: the exact text, its size in bytes and a parsed copy that may be NULL. A
separate table so chain updates never rewrite the large value. Never updated.

### Defendant index (`share_defendant`)

One row per (share, case, defendant), with the master defendant id. Ids only. Repeats in one
payload are merged; the same defendant on two cases gives two rows. Written by the store
transaction, or by the sweep for a row whose extraction had failed. Never updated.

## What may change after insert (FR-044)

| Table | Column | Changed by | When |
|---|---|---|---|
| `event_receipt` | `attempts`, `last_received_at`, `delivery_count` | receipt upsert | every delivery |
| `event_receipt` | `status`, `reason`, `message_text`, `share_id`, `settled_at` | receipt upsert, store transaction | only while `status = 'RECEIVED'` |
| `hearing_day_head` | `latest_share_id`, `share_count` | store transaction | under the day lock, one `UPDATE` |
| `hearing_day_head` | `youth_seen` | store transaction, sweep | under the day lock |
| `hearing_share` | `is_latest`, `predecessor_share_id`, `day_youth_seen` | store transaction (and sweep for `day_youth_seen`) | under the day lock |
| `hearing_share` | `is_reshare`, `court_centre_id`, `court_room_id`, `lja_code`, `jurisdiction_type`, `is_sjp`, `is_group_proceedings`, `youth_court_id`, `any_subject_is_youth` | sweep only | under the day lock, row still `FAILED` |
| `hearing_share` | `projection_status`, `projection_reason`, `projection_version`, `projection_attempts`, `projected_at` | sweep only | under the day lock, row still `FAILED` |

Nothing else is ever updated. `hearing_share_payload` and `share_defendant` are insert-only; no
row is deleted in 001. The guard triggers in V2 and V3 enforce this table (SQLSTATE 23001,
`restrict_violation`, naming the guard); the receipt's key, identity and `first_received_at` never
change. Row triggers do not fire on `TRUNCATE`, so test suites still empty the tables that way
(R19).

## State machines

### Receipt status

```text
                 (identity read)            store tx: inserted
  [arrival] ──────────────────▶ RECEIVED ──────────────────────▶ STORED
      │                            │       store tx: conflict
      │                            └─────────────────────────────▶ DUPLICATE
      │ not JSON / not text / NUL
      ├──────────────────────────────────────────────────────────▶ UNREADABLE
      │ identity missing or invalid
      └──────────────────────────────────────────────────────────▶ NO_IDENTITY
```

- A non-share is written straight into its end state by the receipt upsert, in the same short
  transaction that would otherwise write `RECEIVED` (the parse before it is pure, in-memory work).
- The four end states are final. A later delivery of the same message id only raises `attempts`,
  `last_received_at` and `delivery_count`, and the message is acknowledged with no further work
  (FR-004).
- A store transaction that fails leaves the receipt `RECEIVED` (its update rolls back with
  everything else). A receipt left `RECEIVED` after the broker gives up is what R1 finds.
- `settled_at` is set in the same statement as the move to an end state
  (`event_receipt_settled_ck`).

### Projection (extraction) status

```text
  intake: extracted ─────────────────────────────▶ OK   (final in 001)
  intake: failed ──▶ FAILED ── sweep: extracted ──▶ OK
                       │  ▲
                       └──┘ sweep: failed again (version and attempts updated)
```

A `FAILED` row is picked by the sweep when `projection_version` is older than the current
extractor version, or when its reason starts `UNEXPECTED` and `projection_attempts` < 3. The
intake counts as attempt 1. An `OK` row is never re-extracted in 001 (marking rows for a rerun is
spec 004).

## Youth flags (three values)

| Column | TRUE | FALSE | NULL |
|---|---|---|---|
| `hearing_share.any_subject_is_youth` | any `prosecutionCases[].defendants[].isYouth` is `true` | at least one defendant, and every one states `false` | any defendant does not state it, there are no defendants, or extraction failed |
| `hearing_day_head.youth_seen` | any share of the day is TRUE (stays TRUE) | every share of the day is FALSE | no share is TRUE and at least one is NULL |
| `hearing_share.day_youth_seen` | copy of the day's `youth_seen`, set on every share of the day whenever it changes | same | same |

`youth_court_id` is recorded as stated. Nothing is derived from `youthCourtDefendantIds` or
`hearing.youthCourt` (FR-029).

## Chain rules (under the day lock)

For a new share S of day (H, D) at time t, with P = the share with the greatest `shared_at` < t
and N = the share with the least `shared_at` > t:

| Case | Insert | Then |
|---|---|---|
| first share of the day (no P, no N) | `is_latest=false`, predecessor NULL, `arrived_out_of_order=false` | set S latest; day row: latest S, count 1 |
| newest (P, no N) | `is_latest=false`, predecessor P, `arrived_out_of_order=false` | clear P's latest, then set S latest; day row: latest S, count + 1 |
| late (N exists) | `is_latest=false`, predecessor P (or NULL), `arrived_out_of_order=true` | N's predecessor := S; latest unchanged; day row: count + 1 |

Then the youth recompute (research R15), then the receipt `STORED`.

## Validation rules (from the FRs)

| Field | Rule | On failure |
|---|---|---|
| body | a `TextMessage`, no raw U+0000, valid JSON with nothing after it, an object | receipt `UNREADABLE` with code |
| `hearing.id` | a string in canonical UUID form | receipt `NO_IDENTITY`, `MISSING_HEARING_ID` / `INVALID_HEARING_ID` |
| `hearingDay` | a string, ISO `yyyy-MM-dd` with a four ASCII-digit unsigned year (0000 to 9999) | `MISSING_HEARING_DAY` / `INVALID_HEARING_DAY` |
| `sharedTime` | a string, ISO date-time with offset and a four ASCII-digit unsigned year (0000 to 9999); any offset. Every such instant fits `timestamptz` (proved through JDBC by `FlywayMigrationIT`) | `MISSING_SHARED_TIME` / `INVALID_SHARED_TIME` |
| `hearing.courtCentre.id`, `.roomId`, `hearing.youthCourt.youthCourtId` | absent/null → NULL; else canonical UUID string | `projection_status = FAILED`, `INVALID_UUID:<path>` or `WRONG_TYPE:<path>` |
| `hearing.courtCentre`, `hearing.courtCentre.lja`, `hearing.youthCourt` (parents) | absent, null or not an object → its key details are NULL; not an extraction failure | — (`OK`) |
| `hearing.courtCentre.lja.ljaCode`, `hearing.jurisdictionType` | absent/null → NULL; else a string with no U+0000 (a `text` column cannot hold it, research R8) | `WRONG_TYPE:<path>` / `NUL_CHARACTER:<path>` |
| `hearing.isSJPHearing`, `hearing.isGroupProceedings`, `isReshare` | absent/null → NULL; else a boolean | `WRONG_TYPE:<path>` |
| `hearing.prosecutionCases` | absent/null → no defendants; else an array of objects | `WRONG_TYPE:<path>` |
| `prosecutionCases[].id`, `.defendants[].id` | present, canonical UUID | `MISSING:<path>` / `INVALID_UUID:<path>` |
| `.defendants[].masterDefendantId` | absent/null → NULL; else canonical UUID | `INVALID_UUID:<path>` |
| `.defendants[].isYouth` | absent/null → unknown; else a boolean | `WRONG_TYPE:<path>` |
| anything else thrown while extracting | — | `UNEXPECTED:<exception simple class name>` |

`<path>` is the schema path with array positions left out (for example
`hearing.prosecutionCases.defendants.id`), so the reason is a short bounded code and never holds
payload text. A failure of any field fails the whole extraction: every key-detail column stays
NULL and no defendant row is written (`hearing_share_failed_is_empty_ck`).

Every other field of the payload is ignored and stored as sent (FR-007, Principle V).
