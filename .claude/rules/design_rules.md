# Architecture & Domain Rules

This file is a quick reference to the design page,
[Results Store Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/321061800/Results+Store+Service).
The design page is authoritative for detail; the constitution
(`.specify/memory/constitution.md`) wins where the two disagree. Names of tables and columns
below are the design's; a feature spec may refine them.

## What the store is

One event in, many readers out. The store listens to hearing's
`public.events.hearing.hearing-resulted` on the Artemis `public.event` topic, keeps every share of
every hearing day as an immutable version, and serves what it stored through an internal read API.

**Boundary rule.**
- The store captures and indexes everything and applies **no** business rule to what it stores.
- Where a consumer needs to filter on a fact, the store indexes it **exactly as the payload states
  it**. Example: it records whether any defendant in the share was flagged `isYouth`, and leaves
  the column empty when it cannot read that. What "youth" means is the consumer's decision.
- Read authorisation may scope what a consumer sees. That is access control, not a capture rule.
- Consumers own every rule of their own output.

## Components

| Component | Responsibility |
|---|---|
| Event listener | The shared durable subscription on `public.event`. Hands each share to intake |
| Intake | Records a receipt for every message, records and acknowledges messages that are not shares, adds finalised application results from progression where missing, stores the share in one transaction (a share already stored is dropped by its unique key) |
| Store (PostgreSQL) | One row per share, the payload, a latest pointer per hearing day, header indexes, a defendant index |
| Read API | Internal REST under `/results-store/v1`, keyset paging, "stored since" pull, authorised per action |
| Operations API | `/operations/**` for support staff; never returns payloads |
| Reconciliation | R1: every receipt reached an end state (`STORED`, `DUPLICATE`, `UNREADABLE`, `NO_IDENTITY`). R2: indexed columns still match the payload |
| Purge | Deletes rows past the retention period |

### Package structure (proposed; the first specs settle it)

```
uk.gov.hmcts.cp.resultsstore
├── adapter/
│   ├── publicevents/  the Artemis listener and envelope parsing (exists)
│   └── progression/   the finalised-application-results lookup
├── application/       intake, read and operations services, and the port interfaces
├── domain/            records and enums
├── persistence/       repositories; migrations in src/main/resources/db/migration
├── api/               controllers: inbound adapters only (parse, call one service, map the answer)
├── filters/           ActionHeaderFilter (exists)
└── config/            typed @ConfigurationProperties and wiring (exists)
```

Nothing in `application/` or `domain/` imports a JMS, JDBC or HTTP-client type.

## Data model and versioning

| Table | Holds |
|---|---|
| `hearing_day_head` | One row per hearing day. The write lock point. Latest share, share count, `youth_seen` (becomes true once any share of the day had a youth subject, and stays true) |
| `hearing_share` | One row per share: `share_id` (UUID from `hearingId` + `hearingDay` + `sharedTime`), shared and stored times, key details (court centre, room, LJA, jurisdiction, SJP, group proceedings, youth court), re-share flag, youth facts, chain columns (latest, predecessor, out-of-order), payload checksum (SHA-256 over the stored text), enrichment applied, extraction status, expiry. Unique key on `hearingId` + `hearingDay` + `sharedTime` |
| `hearing_share_payload` | `payload_text`: the exact text received, with the checksum over it. `payload_json`: the working copy (the text parsed, enriched at intake with finalised application results, the three amendment fields removed); permanent, read by the extraction and the sweep, served by the read API |
| `share_defendant` | `defendant_id`, `master_defendant_id`, `case_id` per share. Ids only |
| `event_receipt` | One row per message, keyed by the broker's message id: arrival time, delivery/attempt count, the share's identity when the message had one, status (`RECEIVED`, `STORED`, `DUPLICATE`, `UNREADABLE`, `NO_IDENTITY`), `share_id` once stored, and the reason and message text when it was not a share. The intake log R1 checks |
| `reconciliation_finding` | Reconciliation findings (R1, R2) |

| Question | Rule |
|---|---|
| What is a version? | One share of one hearing day: `hearingId` + `hearingDay` + `sharedTime` |
| Which is latest? | Greatest `shared_at`, worked out under the hearing-day lock. Never arrival order |
| A share arrives late | Stored with `is_latest = false`, `arrived_out_of_order = true`, linked in by `shared_at` |
| What may be updated? | Under the lock: the latest pointer, the predecessor link and the day's youth flag. By the extraction sweep alone: the key-details columns and the `projection_*` columns. Nothing else |
| Deleted results | Kept exactly as sent |
| Multi-day hearings | Each hearing day is its own version chain |

Two clocks, never mixed: `shared_at` (when the share happened; orders versions) and `stored_at` /
`stored_seq` (when the store received it; drives "stored since"). Two calendar days are stored:
`shared_day_london` and `shared_day_utc`, because they differ between 00:00 and 01:00 BST.

## Write path (one share)

1. **Receive** and record a receipt keyed by the broker's message id (status `RECEIVED`, or raise
   the attempt count if it exists), in its own short transaction.
2. **Identify**: read `hearing.id`, `hearingDay`, `sharedTime`. Body not JSON or any missing → mark
   the receipt `UNREADABLE` or `NO_IDENTITY` with the reason and the message text, count it,
   acknowledge and stop. Never dead-lettered.
3. **Enrich**: for each court application with no `judicialResults`, ask progression
   (`GET /applications/{applicationId}`, `application/vnd.progression.query.application-only+json`)
   as a system user; if `FINALISED` with results, copy them in without `amendmentDate`,
   `amendmentReason`, `amendmentReasonId`. Progression unreachable → roll back; never store
   half-enriched. Record whether enrichment was applied.
4. **Store**, one transaction: lock the hearing day (insert the head row if first), insert the
   share with `ON CONFLICT DO NOTHING` (nothing inserted → already stored: mark the receipt
   `DUPLICATE`, commit, acknowledge; no error, no rollback), insert the payload, extract key
   details and defendant index, update `youth_seen`, move the latest pointer if this share is the
   greatest, mark the receipt `STORED` with the `shareId`.
5. **Acknowledge** after commit.

Extraction failure stores the share anyway with `projection_status = FAILED`; a scheduled sweep
retries. Retries are the broker's job, not a loop in the store. A retryable failure (database or
progression unreachable) is thrown after a short capped pause (`min(2^deliveryCount s, 30 s)`), so
the broker's immediate redeliveries are not used up during a brief outage. A failure that keeps
happening ends on the broker's dead-letter queue, after its own redelivery attempts.

## Read API (phase 1, `/results-store/v1`)

| Endpoint | Purpose |
|---|---|
| `GET /shares?storedAfterSeq=&limit=&dayYouthSeen=notFalse&courtCentreId=` | Pull: shares stored after a sequence number, in stored order, key details only |
| `GET /shares?courtCentreId=&sharedDayFrom=&sharedDayTo=` or `…&sharedFrom=&sharedTo=` (+ `dayYouthSeen`, `latestOnly`, `limit`, `cursor`) | Search: one court over London days (day form) or a `sharedTime` range (time form), keyset paged |
| `GET /shares/{shareId}` | One share's key details, version, latest, predecessor, youth facts |
| `GET /shares/{shareId}/payload` | The working copy (`payload_json`) without `_metadata`, or `payload_text` without `_metadata` when the working copy is empty; `ETag` over the exact bytes served, never `payload_sha256` |
| `GET /shares/{shareId}/payload/arrived` | The text as it arrived (`payload_text`), before enrichment, without `_metadata` (spec 003 phase D) |
| `GET /hearings/{hearingId}/days/{hearingDay}/shares` | Every version of one day, in `sharedTime` order |

No response carries the message envelope's metadata (`_metadata`) or a value taken from it.

**Pull safety: the visibility lag.** A pull returns only shares at or below the visibility bound:
the highest `stored_seq` among shares stored at least the lag ago, both times from the database
clock, worked out in the same statement as the page. Every store transaction ends within the lag
(transaction + 2 × statement + idle-in-transaction timeouts, all enforced by PostgreSQL or by Spring
before a statement), so every share at or below the bound has committed or gone. The default lag is
**90 seconds** (60 + 2 × 10 + 10 at the intake defaults); the service refuses to start with a lag
below that sum or above 10 minutes, and intake counts `resultsstore.intake.visibility.overrun` when
a store transaction outlives it (specs/003-read-api research R4, R6, R7). The pull's
`nextStoredAfterSeq` moves over ranges its filters skip, and `visibleUpTo` tells a consumer what
has been presented. Push (store-then-notify through an outbox) is designed for but not in phase 1.

## Operations API (phase 1, `/operations/**`)

`POST /operations/extraction/rerun`, `GET /operations/extraction/status`,
`GET /operations/receipts?hearingId=&hearingDay=`, `GET /operations/reconciliation/daily?date=`.
"Second Line Support" only, audited, never returns a payload. There is no replay endpoint: a
message on the broker's dead-letter queue, after its own redelivery attempts, is moved back with
the broker's tools, and replay is safe because the unique key on the share identity drops a share
already stored. Messages that are not shares never go there.

## Security

- `CJSCPPUID` identifies the caller; groups come from usersgroups. The gateway strips and sets it;
  Istio and network policy let only the gateway reach the API (outside this repo).
- `ActionHeaderFilter` derives the action from method and path for **every** request: a caller's
  `CPP-ACTION` and any vendor media type in `Content-Type` or `Accept` are overridden, an unmapped
  path is refused `404 route_not_found` and a served path with another method `405`. A test checks
  every route against the mapping.
- One drools rule per action; `deny-when-no-rules`. Every read-API rule admits "System Users" and
  "Second Line Support" and matches its route's method and path; finer-grained rules only when a
  need appears.
- Every request that reaches an endpoint is audited by `cp-audit-filter-springboot`; the payload
  endpoints' response body is replaced in the audit event by `{"payloadOmitted":true}`. A request
  refused before it reaches the audit filter is counted in `resultsstore.read.refused` with a
  bounded reason: `route_not_found`, `method_not_allowed` (our filters, before authorisation),
  `unsupported_content_type` (after it), `unauthenticated`, `forbidden` (authorisation) and
  `connector_rejected` (the HTTP connector, before the service sees the request).
- Every refusal and error is the four-field problem body (`type`, `title`, `status`, `reason`);
  no body ever echoes a caller's value, a path or an exception message.
- The payload table holds special-category and youth personal data; index tables hold ids and
  flags only. Retention placeholder 13 months (`expires_at`, nightly purge), pending the DPIA.

## Observability

Azure Monitor metrics, dashboard and alerts; **no exception report**. Metrics include shares
received, stored, and recorded as not shares (unreadable, no identity), duplicates dropped,
enrichment applied and failed, out-of-order arrivals, extraction failures, failed intake attempts,
share-to-store lag, subscription health, the subscription's dead-letter count, R1/R2 findings.
`shareId` and `hearingId` go in the logging context.

## Out of Scope — do not build here

- Any consumer's business rules (register content, youth selection, recipients, documents).
- Publishing on Artemis.
- Changing or deleting a stored share (redaction and purge rules wait for the DPIA).
- Per-result rows, server-side diffs, and views by defendant, prosecutor or courtroom (later
  phases).
- Push notifications (designed, not phase 1).
