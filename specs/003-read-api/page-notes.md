# Design page: forward notes from spec 003

**For**: the owner of the Results Store design page (CRA 321061800), and the YOT and probation teams who
build to the read API.
**From**: spec 003 (read API); constitution 2.2.0 once T012 lands (Principle VII, and Principle II if
D-RAW is accepted).
**Status**: notes only. The page has not been edited. Each note gives the section, the wording on the
page today where this repository records it, and paste-ready wording. Points marked *pending Sachin*
carry the default of spec.md *Decisions pending Sachin*. T012 reconciles these notes with what was built.

In one line: the read API is five `GET` endpoints under `/results-store/v1` (six if D-RAW is accepted).
Pull is made safe by a visibility lag, not by asking PostgreSQL for the lowest open write. Every action
is derived from method and path and admits "System Users" and "Second Line Support". The full consumer
contract is `specs/003-read-api/contracts/read-api.md`.

---

## 1. *How the store makes pull safe*

**Today** (as summarised in this repository's design rules): "never return a row while a lower-numbered
row is still being written. Each pull asks PostgreSQL for the lowest sequence number an open write holds
and returns only rows below it." The page already allowed a lag as the fallback.

**Replace with**:

> **How the store makes pull safe.** A share is numbered (`stored_seq`) inside the store's write
> transaction, which writes more before it commits, so number 101 can still be open while 102 has
> committed. The store therefore returns only shares numbered at or below the highest number stored
> more than the **visibility lag** ago. Every store transaction ends within the lag: its whole-transaction
> timeout, plus twice the statement timeout, plus the idle-in-transaction timeout, all enforced by
> PostgreSQL (110 seconds at the defaults; pending Sachin). The service refuses to start with a lag below
> that sum. A database trigger reads `stored_at` from the clock after the number is taken, which the
> rule relies on. Consumers wait about two minutes for each share. A counter alerts if any write ever
> takes longer than the lag; a crash or failover in the middle of a commit is the one case no reader-side
> rule can close, and consumer reconciliation (re-pulling from an older cursor) covers it.
>
> *Why not ask PostgreSQL for the lowest open write?* An advisory-lock watermark can only be taken after
> the number, so a reader can miss it; a snapshot (`xmin`) watermark does not follow number order; a
> `pg_stat_activity` watermark is plausible but unproven and is held back by any long transaction in the
> database. None is both proven and simpler than the lag.

---

## 2. *Read API*: the endpoint table

**Replace the rows with** (the payload row keeps spec 002's wording, page-notes §3 there):

> | Endpoint | Purpose |
> |---|---|
> | `GET /shares?storedAfterSeq=&limit=&dayYouthSeen=notFalse\|true&courtCentreId=` | **Pull**: shares stored after a sequence number, in stored order, key details only. Returns `nextStoredAfterSeq` (moves over ranges the filters skip), `hasMore` and `visibleUpTo` (every share stored before it has been presented). Filters are evaluated at read time; a share behind the cursor is not presented again; the day's next share is. A court-filtered pull also returns shares whose court is not yet known (pending Sachin) |
> | `GET /shares?courtCentreId=&sharedDayFrom=&sharedDayTo=&dayYouthSeen=&latestOnly=&cursor=` | **Search**: one court's shares over London register days (inclusive, at most 31), keyset paged. A query, not a feed |
> | `GET /shares/{shareId}` | One share's current key details, version number, latest flag, predecessor and youth facts |
> | `GET /shares/{shareId}/payload` | The working copy (the text as it arrived when there is none), `_metadata` kept. `ETag`: SHA-256 over the exact bytes returned. Identity and `enrichmentApplied` in `Results-Store-*` headers. `If-None-Match` gives `304` |
> | `GET /hearings/{hearingId}/days/{hearingDay}/shares` | Every version of one day, in `sharedTime` order; `404` when the day has none |
> | `GET /shares/{shareId}/payload/arrived` | **Only if D-RAW is accepted.** The text exactly as hearing sent it; `ETag` = the stored checksum |

Add under the table:

> Version numbers are worked out when read and can change when an earlier share arrives late, so the
> key is `shareId`. Key details and youth flags can be filled in later (the sweep, or a support-staff
> re-extraction) with no new number: a consumer re-reads a share presented with unknown key details
> before deciding it is not theirs.

---

## 3. *Security*

**Today** (as summarised in this repository's design rules): "`ActionHeaderFilter` derives the action
from path and method for **every** request and refuses a path it cannot map … Every read-API rule admits
"System Users" … Audit by `cp-audit-filter-springboot` with the library's default settings."

**Replace with**:

> - The action is derived from method and path for every request. A `CPP-ACTION` header or a vendor
>   media type sent by the caller is overridden (the authorisation library would otherwise take the
>   action from a vendor media type first). A path under the service that is not a route gets `404`
>   before authentication; a wrong method gets `405`.
> - One allow rule per action, `deny-when-no-rules`. Every read-API rule admits "System Users" and
>   "Second Line Support", and also matches the route's method and path.
> - Every request that reaches an endpoint is audited by `cp-audit-filter-springboot`; refusals before
>   authorisation are counted (pending Sachin). The payload endpoints' audit record holds a fixed marker
>   instead of the payload (D-AUDIT, pending Sachin).

---

## 4. Appendix: audit and the youth-scoped action

**Today** (as the design review records it): "Audit events carry no request or response bodies
(include-payload-body: false)."

**Correct to**: *`cp-audit-filter-springboot` 1.0.5 has no body switch: it copies the response body into
the audit event. The store replaces the payload endpoints' body with a fixed marker in the audit event
(D-AUDIT option 4) and asks the library owners for an exclusion switch; list pages are audited with
their bodies (ids and flags). Pending Sachin; the DPIA records the outcome.*

The youth-scoped read action in the appendix stays superseded: constitution VII has no youth scoping.

---

## 5. Open questions to add to the page

Until Sachin rules: D-AUDIT, D-RAW, D-LAG-VALUE, D-OVERRUN, D-COURT-FAILED, D-JSONB-PROMISE, D-S12,
D-READONLY-PODS, D-PG-VERSION / HA, D-AUTHZ-REQUIRED, D-VII-AUDIT-WORDING, D-REFUSALS-UNAUDITED (spec.md
*Decisions pending Sachin* has each question, default and alternatives).

---

## 6. For the YOT team (redesign assumptions to update)

- **Request id.** `shareId` is the store's UUID v5 over `hearingId|hearingDay|sharedTime` (namespace
  `3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60`), not a v3 `nameUUIDFromBytes`. Take it from the store; never
  derive it.
- **Watermark.** `visibleUpTo` is back: pull until `hasMore` is false and `visibleUpTo` is at or after
  18:00. No overlap re-read is needed for safety; re-pulling from an older cursor is the way to
  reconcile.
- **INT or SJP.** No `eventType` parameter or field: `keyDetails.isSjp` (`true` SJP, `false` INT, `null`
  unknown while `FAILED`).
- **Latest.** Pull has no `latestOnly`; items carry `isLatest` and `arrivedOutOfOrder`. Before skipping a
  non-latest share, read the day's versions.
- **Youth.** Use `dayYouthSeen=notFalse`, not `true`. Re-read a share presented with `dayYouthSeen` null
  or `projectionStatus` `FAILED`.
- **Payload.** The body is the JsonEnvelope with `_metadata` kept; top-level `hearing` and `sharedTime`
  are there as YOT reads them today. Check SHA-256 of the body against the `ETag`.
- **Errors.** `404` is "not held"; `503 store_unavailable` carries `Retry-After`, which YOT's retry
  policy honours; other `4xx` are permanent.
- **Indexes.** The redesign's `ix_share_youth_feed` and `ix_share_centre_day` are delivered as
  `hearing_share_youth_feed_ix` and `hearing_share_centre_day_ix`; `ix_share_room` and `ix_sdef_mdef`
  are not built.
- **Access.** The youth-scoped raw action (`resultsstore.youth.raw.read`) does not exist; YOT's system
  user needs "System Users".

## 7. For the probation team (asks S6 to S12, gate G2)

- **G2.** Review `specs/003-read-api/contracts/read-api.md`; the OpenAPI document follows it.
- **S7.** The groups are "System Users" and "Second Line Support"; probation's system user needs "System
  Users".
- **S8.** `ETag` = `"<SHA-256 hex over the exact bytes served>"`, strong and quoted: DV-19 can compare
  directly. `enrichmentApplied` is the `Results-Store-Enrichment-Applied` header. `shareId` is UUID v5
  (above); the wire key is `shareId` from the store.
- **S10.** The arrived text on `GET /shares/{shareId}/payload/arrived` if D-RAW is accepted (the
  default; pending Sachin).
- **S12.** No flag: read `_metadata.context.user` from the `/payload` body; an absent key means no user
  (pending Sachin).
- **S6.** Retention is still open (the page's open question 1).
- **Bridge.** `limit=200` every 30 s is within the limits (maximum 500). "Everything returned is final"
  holds for `storedSeq` order; mutable values (key details, flags, latest) are re-read from
  `GET /shares/{shareId}`.
- **Bytes across upgrades.** The body and `ETag` are stable within a PostgreSQL major version; an upgrade
  may change bytes but not content (D-JSONB-PROMISE, pending Sachin).
