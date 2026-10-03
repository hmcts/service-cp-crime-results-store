# Data model: Read API

**Feature**: `003-read-api` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

003 adds no table and no column. It adds one migration, `V5__read_api.sql`: a trigger that sets
`hearing_share.stored_at` after the row's sequence number is taken, and three partial indexes for the
read queries. V1 to V4 are not edited. Every rule of
[../001-share-intake/contracts/schema.md](../001-share-intake/contracts/schema.md) and
[../002-enrichment/contracts/schema.md](../002-enrichment/contracts/schema.md) still binds.

## V5 DDL (in full)

```sql
-- V5__read_api.sql (spec 003): what the read API needs from the schema.
--
-- 1. stored_at is read from the clock after the row's stored_seq is taken. V3 declares stored_at
--    (DEFAULT clock_timestamp()) before stored_seq (identity), so the default could read the clock
--    before the number was taken. Pull safety (specs/003-read-api/research.md R4) needs a share's
--    stored_at to be no earlier than the moment its number was taken. A BEFORE INSERT trigger runs
--    after the defaults and the identity value are set. The column default stays; the trigger
--    overrides any value an INSERT supplies. hearing_share_guard (V3) already refuses any UPDATE of
--    stored_at.
CREATE FUNCTION hearing_share_stored_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.stored_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER hearing_share_stored_at_tg
    BEFORE INSERT ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION hearing_share_stored_at();

-- 2. Pull for youth-relevant days: dayYouthSeen=notFalse, and =true written as
--    "day_youth_seen IS NOT FALSE AND day_youth_seen" so the planner can prove the predicate.
--    A range scan in stored_seq order that stops after limit + 1 rows.
--    day_youth_seen is rewritten by youth propagation, so membership changes are non-HOT updates;
--    the volume is low.
CREATE INDEX hearing_share_youth_feed_ix
    ON hearing_share (stored_seq)
    WHERE day_youth_seen IS NOT FALSE;

-- 3. Pull with courtCentreId: an exact match (FAILED rows, which have no court, are never returned),
--    so one range scan in stored_seq order serves it and stops after limit + 1 rows.
CREATE INDEX hearing_share_centre_feed_ix
    ON hearing_share (court_centre_id, stored_seq)
    WHERE court_centre_id IS NOT NULL;

-- 4. Search by court over a shared_at range, keyset on (shared_at, share_id), so no sort step. Both
--    search forms use it: the service turns London days into a [from, to) shared_at range.
--    Partial: FAILED rows have no court and never match court_centre_id = :courtCentreId.
CREATE INDEX hearing_share_centre_shared_at_ix
    ON hearing_share (court_centre_id, shared_at, share_id)
    WHERE court_centre_id IS NOT NULL;

COMMENT ON INDEX hearing_share_youth_feed_ix IS
    'Read API pull, dayYouthSeen=notFalse|true (spec 003)';
COMMENT ON INDEX hearing_share_centre_feed_ix IS
    'Read API pull by court centre, exact match (spec 003)';
COMMENT ON INDEX hearing_share_centre_shared_at_ix IS
    'Read API search by court centre over a shared_at range, both forms (spec 003)';
COMMENT ON TRIGGER hearing_share_stored_at_tg ON hearing_share IS
    'stored_at read after stored_seq is taken; pull safety (spec 003)';
```

Firing order: PostgreSQL runs `BEFORE` row triggers in name order. `hearing_share_predecessor_guard_tg`
(V3, `BEFORE INSERT OR UPDATE OF predecessor_share_id`) runs first and does not read `stored_at`; the
order does not matter.

Locking: plain `CREATE INDEX` inside Flyway's transaction blocks intake inserts while it builds. V5
deploys before go-live; the `CONCURRENTLY` fallback is in plan.md *Risks*.

## What V5 does not change

| Item | Status |
|---|---|
| Tables, columns, `CHECK` constraints | unchanged |
| `hearing_share_guard`, `hearing_share_projection_guard` and every other V3 guard | unchanged (spec 004's V6 replaces the share guard, not 003) |
| `hearing_share.stored_at` column default | kept (`DEFAULT clock_timestamp()`); the trigger sets the value |
| The identity sequence behind `stored_seq` | cache 1 (V3 sets no cache option); R4's proof relies on it and `FlywayMigrationIT` asserts it |
| Indexes of V3/V4 | unchanged; `hearing_share_stored_seq_uk`, `hearing_share_pk`, `hearing_share_identity_uk` and `hearing_share_payload_pk` serve the other read queries |

## The share item: columns to JSON

The item is a read view of `hearing_share`. No payload.

| JSON field | Column / derivation | Null when |
|---|---|---|
| `shareId` | `share_id` | never |
| `hearingId` | `hearing_id` | never |
| `hearingDay` | `hearing_day` (`yyyy-MM-dd`) | never |
| `sharedTime` | `shared_at` (six fraction digits, UTC) | never |
| `storedSeq` | `stored_seq` (JSON integer, 64-bit) | never |
| `storedAt` | `stored_at` | never |
| `sharedDayLondon` | `shared_day_london` | never |
| `sharedDayUtc` | `shared_day_utc` | never |
| `keyDetails` | object of the eight columns below | `projection_status = 'FAILED'` (V3 `hearing_share_failed_is_empty_ck` makes them all NULL) |
| `keyDetails.courtCentreId` | `court_centre_id` | not stated in the payload |
| `keyDetails.courtRoomId` | `court_room_id` | not stated |
| `keyDetails.ljaCode` | `lja_code` | not stated |
| `keyDetails.jurisdictionType` | `jurisdiction_type` | not stated |
| `keyDetails.isSjp` | `is_sjp` (true = SJP, false = INT) | not stated |
| `keyDetails.isGroupProceedings` | `is_group_proceedings` | not stated |
| `keyDetails.youthCourtId` | `youth_court_id` | not stated |
| `keyDetails.isReshare` | `is_reshare` | not stated |
| `anySubjectIsYouth` | `any_subject_is_youth` | unknown (also while `FAILED`) |
| `dayYouthSeen` | `day_youth_seen` | unknown for the day |
| `isLatest` | `is_latest` | never |
| `predecessorShareId` | `predecessor_share_id` | first share of the day |
| `arrivedOutOfOrder` | `arrived_out_of_order` | never |
| `enrichmentApplied` | `enrichment_applied` | never |
| `projectionStatus` | `projection_status` (`OK` or `FAILED`) | never |
| `projectionVersion` | `projection_version` | never |
| `projectedAt` | `projected_at` | never |
| `versionNumber` | `(SELECT count(*) FROM hearing_share v WHERE v.hearing_id = s.hearing_id AND v.hearing_day = s.hearing_day AND v.shared_at <= s.shared_at)`; `row_number() OVER (ORDER BY shared_at)` in the day query | never |

Not exposed: `payload_sha256` (the checksum of the arrived text with `_metadata`; never served and never
an `ETag`, because no served body includes `_metadata`), `projection_reason` (an internal bounded code; spec 004's status endpoint shows extraction
detail to support staff), `projection_attempts`, `projection_tried_at`, `expires_at` (always NULL).

## Read queries (shape; the SQL constants live in `JdbcShareQueries`)

All are autocommit, read-only, bound parameters only, never built from input. The variants are fixed
constants chosen by a switch on `DayYouthFilter` and on whether a court is given.

**Pull** (one statement; `:lagSeconds` bound as a number of seconds):

```sql
WITH bound AS (
    SELECT now() - make_interval(secs => :lagSeconds) AS visible_up_to,
           (SELECT b.stored_seq FROM hearing_share b
             WHERE b.stored_at <= now() - make_interval(secs => :lagSeconds)
             ORDER BY b.stored_seq DESC
             LIMIT 1) AS max_seq
)
SELECT bound.visible_up_to, bound.max_seq, page.*
  FROM bound
  LEFT JOIN LATERAL (
        SELECT <item columns>, <versionNumber subquery>
          FROM hearing_share s
         WHERE s.stored_seq > :storedAfterSeq
           AND s.stored_seq <= bound.max_seq
           [AND s.day_youth_seen IS NOT FALSE]                         -- notFalse
           [AND s.day_youth_seen IS NOT FALSE AND s.day_youth_seen]    -- true
           [AND s.court_centre_id = :courtCentreId]                     -- exact; FAILED rows never match
         ORDER BY s.stored_seq
         LIMIT :limitPlusOne) page ON TRUE
```

One row comes back even when the page is empty (the `LEFT JOIN` on the one-row `bound`), carrying
`visible_up_to` and `max_seq`. The service computes `hasMore` (more than `limit` rows),
`nextStoredAfterSeq` (the last item's `storedSeq` when `hasMore`, else `max(storedAfterSeq, max_seq)`,
with a null `max_seq` meaning "stay") and `visibleUpTo`.

**Search** (one statement for both forms; the day form arrives as instants, London midnight to London
midnight, worked out by `SharedDays`):

```sql
SELECT <item columns>, <versionNumber subquery>
  FROM hearing_share s
 WHERE s.court_centre_id = :courtCentreId
   AND s.shared_at >= :sharedFrom
   AND s.shared_at <  :sharedTo
   [AND s.is_latest]
   [AND <dayYouthSeen variant: IS NOT FALSE | IS NOT FALSE AND day_youth_seen | IS FALSE>]
   [AND (s.shared_at, s.share_id) > (:cursorAt, :cursorId)]
 ORDER BY s.shared_at, s.share_id
 LIMIT :limitPlusOne
```

**One share:** `… FROM hearing_share s WHERE s.share_id = :shareId`.

**Day's versions:** `… , row_number() OVER (ORDER BY s.shared_at) FROM hearing_share s WHERE
s.hearing_id = :hearingId AND s.hearing_day = :hearingDay ORDER BY s.shared_at`.

**Payload** (with the arrived text, the only queries that name `hearing_share_payload`). `_metadata` is
removed in the database for the working copy; for the text form the service removes it
(`EnvelopeMetadata`) before hashing:

```sql
SELECT s.share_id, s.hearing_id, s.hearing_day, s.shared_at, s.enrichment_applied,
       CASE WHEN p.payload_json IS NULL THEN p.payload_text
            ELSE (p.payload_json - '_metadata')::text END AS body,
       p.payload_json IS NULL AS arrived_text
  FROM hearing_share s
  JOIN hearing_share_payload p ON p.share_id = s.share_id
 WHERE s.share_id = :shareId
```

**Arrived text (phase D):** the same identity columns with `p.payload_text`; the service removes
`_metadata` and hashes the bytes it serves. `payload_sha256` is not read.

## In-memory read types

| Type | Layer | Holds |
|---|---|---|
| `ShareView` | `domain/` | every column of the item table plus `versionNumber`; `keyDetails` as `KeyDetails` or null when `FAILED` |
| `DayYouthFilter` | `domain/` | `ANY`, `NOT_FALSE`, `TRUE`, `FALSE`; `fromValue` refuses anything else (case-sensitive) |
| `SearchCursor` | `domain/` | `shared_at` (epoch microseconds), `shareId`; `encode()` and a strict `decode()` |
| `StoredPayload` | `domain/` | identity, `enrichmentApplied`, body text as read, `PayloadForm` (`WORKING_COPY`, `ARRIVED_TEXT`) |
| `EnvelopeMetadata` | `domain/` | removes the top-level `_metadata` member from a JSON text (Jackson 3; exact numbers; key order kept) |
| `SharedDays` | `domain/` | + the London day range as a [from, to) `shared_at` range (`Europe/London`) |
| `PullQuery`, `SearchQuery` | `application/` | the validated parameters; `SearchQuery` always holds the instant range |
| `PullPage`, `SearchPage`, `ServedPayload` | `application/` | what the service answers: items and cursor values; bytes, `ETag`, identity, flag, form |

## Invariants the tests hold

1. For every row, `stored_at` is at or after a clock read taken just before its insert
   (`FlywayMigrationIT`).
2. Every row at or below a pull's `max_seq` belongs to a transaction that has ended (`JdbcShareQueriesIT`,
   two connections).
3. `keyDetails` is null exactly when `projectionStatus` is `FAILED` (`ShareViewTest`, `JdbcShareQueriesIT`).
4. No pull, search, share or day query names `hearing_share_payload` (`JdbcShareQueriesTest` on the
   constants; `ReadQueriesPlanIT` on the plans).
5. The `ETag` of `/payload` (and of `/payload/arrived`) is the SHA-256 of exactly the bytes served
   (`ShareReadServiceTest`, `ReadApiIT`), and never `payload_sha256`.
6. No served body has a top-level `_metadata` member (`JdbcShareQueriesIT`, `ShareReadServiceTest`,
   `ReadApiIT`).
7. A court-filtered pull never returns a `FAILED` row (`JdbcShareQueriesIT`).
8. The day form and the time form of search over the same London days return the same rows
   (`JdbcShareQueriesIT`).
