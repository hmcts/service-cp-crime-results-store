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
| Intake | Admits each share once, adds finalised application results from progression where missing, stores the share in one transaction |
| Store (PostgreSQL) | One row per share, the payload, a latest pointer per hearing day, header indexes, a defendant index |
| Read API | Internal REST under `/results-store/v1`, keyset paging, "stored since" pull, authorised per action |
| Operations API | `/operations/**` for support staff; never returns payloads |
| Reconciliation | R1: every event received was stored or dead-lettered. R2: indexed columns still match the payload |
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
| `hearing_share` | One row per share: `share_id` (UUID from `hearingId` + `hearingDay` + `sharedTime`), shared and stored times, key details (court centre, room, LJA, jurisdiction, SJP, group proceedings, youth court), re-share flag, youth facts, chain columns (latest, predecessor, out-of-order), payload digest, enrichment applied, expiry |
| `hearing_share_payload` | The exact text received (returned unchanged, checksum reproducible) and a parsed `jsonb` copy for rebuilding columns and R2, not for consumers |
| `share_defendant` | `defendant_id`, `master_defendant_id`, `case_id` per share. Ids only |
| `processed_request`, `event_receipt`, `reconciliation_finding` | Intake log, receipt log (R1), reconciliation findings |

| Question | Rule |
|---|---|
| What is a version? | One share of one hearing day: `hearingId` + `hearingDay` + `sharedTime` |
| Which is latest? | Greatest `shared_at`, worked out under the hearing-day lock. Never arrival order |
| A share arrives late | Stored with `is_latest = false`, `arrived_out_of_order = true`, linked in by `shared_at` |
| What may be updated? | Only the latest pointer, the predecessor link and the day's youth flag, under the lock |
| Deleted results | Kept exactly as sent |
| Multi-day hearings | Each hearing day is its own version chain |

Two clocks, never mixed: `shared_at` (when the share happened; orders versions) and `stored_at` /
`stored_seq` (when the store received it; drives "stored since"). Two calendar days are stored:
`shared_day_london` and `shared_day_utc`, because they differ between 00:00 and 01:00 BST.

## Write path (one share)

1. **Receive** and record a receipt, in its own short transaction.
2. **Identify**: read `hearing.id`, `hearingDay`, `sharedTime`. Any missing → dead-letter with a
   reason, and alert.
3. **Admit once**: a share already stored is acknowledged and dropped.
4. **Enrich**: for each court application with no `judicialResults`, ask progression
   (`GET /applications/{applicationId}`, `application/vnd.progression.query.application-only+json`)
   as a system user; if `FINALISED` with results, copy them in without `amendmentDate`,
   `amendmentReason`, `amendmentReasonId`. Progression unreachable → roll back; never store
   half-enriched. Record whether enrichment was applied.
5. **Store**, one transaction: lock the hearing day (insert the head row if first), insert the
   share (same identity + same digest = no-op; different digest = anomaly, alerted), insert the
   payload, extract key details and defendant index, update `youth_seen`, move the latest pointer
   if this share is the greatest, mark the request complete.
6. **Acknowledge** after commit.

Extraction failure stores the share anyway with `projection_status = FAILED`; a scheduled sweep
retries. Retries are the broker's job, not a loop in the store. SIT's broker redelivers at once,
10 times, then to the shared `jms.queue.DLQ`: wait briefly before rolling back a lookup failure.

## Read API (phase 1, `/results-store/v1`)

| Endpoint | Purpose |
|---|---|
| `GET /shares?storedAfterSeq=&limit=&dayYouthSeen=notFalse&courtCentreId=` | Pull: shares stored after a sequence number, in stored order, key details only |
| `GET /shares?courtCentreId=&sharedDayFrom=&sharedDayTo=&dayYouthSeen=&latestOnly=` | Search |
| `GET /shares/{shareId}` | One share's key details, version, latest, predecessor, anomaly, youth facts |
| `GET /shares/{shareId}/payload` | The payload exactly as received; `ETag` = digest |
| `GET /hearings/{hearingId}/days/{hearingDay}/shares` | Every version of one day, in `sharedTime` order |

**Pull safety:** never return a row while a lower-numbered row is still being written. Each pull
asks PostgreSQL for the lowest sequence number an open write holds and returns only rows below it.
Push (store-then-notify through an outbox) is designed for but not in phase 1.

## Operations API (phase 1, `/operations/**`)

`POST /operations/extraction/rerun`, `GET /operations/extraction/status`,
`GET /operations/receipts?hearingId=&hearingDay=`, `GET /operations/reconciliation/daily?date=`.
"Second Line Support" only (group still to be confirmed), audited, never returns a payload. There
is no replay endpoint: dead-lettered events are moved back with the broker's tools, and replay is
safe because the share identity makes it idempotent.

## Security

- `CJSCPPUID` identifies the caller; groups come from usersgroups. The gateway strips and sets it;
  Istio and network policy let only the gateway reach the API (outside this repo).
- `ActionHeaderFilter` derives the action from path and method for **every** request and refuses
  a path it cannot map. A test checks every route against the mapping.
- One drools rule per action; `deny-when-no-rules`. Service callers are admitted through
  "System Users". The youth-scoped read returns only days with `youth_seen IS NOT FALSE`.
- Audit events carry no bodies.
- The payload table holds special-category and youth personal data; index tables hold ids and
  flags only. Retention placeholder 13 months (`expires_at`, nightly purge), pending the DPIA.

## Observability

Azure Monitor metrics, dashboard and alerts; **no exception report**. Metrics include shares
received and stored, enrichment applied and failed, out-of-order arrivals, extraction failures,
failed intake attempts, share-to-store lag, subscription health, dead letters, R1/R2 findings.
`shareId` and `hearingId` go in the logging context.

## Out of Scope — do not build here

- Any consumer's business rules (register content, youth selection, recipients, documents).
- Publishing on Artemis.
- Changing or deleting a stored share (redaction and purge rules wait for the DPIA).
- Per-result rows, server-side diffs, and views by defendant, prosecutor or courtroom (later
  phases).
- Push notifications (designed, not phase 1).
