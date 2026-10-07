# Feature Specification: Share intake

**Feature Branch**: `001-share-intake`
**Created**: 2026-10-02
**Status**: Draft
**Input**: User description: "Share intake: the write path from the hearing public event to the store, without progression enrichment"

**Sources**: the Results Store design page (CRA 321061800, v45), sections *Intake*, *Data model and versioning* (incl. *Volumes*), *Write path*, *Observability and reconciliation* (metrics list) and *Glossary*; the approved implementation plan for spec 001, including its "Implementation choices settled" and "Decisions taken with Sachin (2026-10-02)"; the hearing event schema map for field paths. Quotes in *italics* are the page's wording.

### Scope

**In scope.** Receipt-first intake. Identifying the share. The store transaction: hearing-day lock, duplicate drop by unique key, payload text and parsed copy, key details, defendant index, youth flags, latest pointer and predecessor chain. Non-shares recorded and acknowledged. A short capped pause before a redelivery. Extraction failure kept apart from storing, with a sweep that retries. Metrics. Database migrations V2, V3 and V4 (V1 is not changed). An end-to-end check through the compose stack.

**Out of scope.**

- Progression enrichment (*Adding finalised application results*): spec 002. In 001 the payload is stored exactly as it arrived.
- The read API (pull, search, latest, payload fetch) and the search indexes consumers need: spec 003.
  *Amended by spec 003*: the indexes are in migration `V5__read_api.sql` (specs/003-read-api/data-model.md).
- The operations API, including the operator action that marks rows for re-extraction and dead-letter replay tooling: spec 004.
- Retention and purge. `expires_at` exists but is left empty.
- Legacy data migration.
- Push notifications to consumers.
- The reconciliation jobs R1 and R2 themselves. 001 only writes the data they will check.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Store a share end to end (Priority: P1)

Hearing shares a hearing day. The store receives the `hearing-resulted` message, records a receipt, identifies the share, stores the share, its payload, its key details and its defendants, moves the day's latest pointer, marks the receipt `STORED`, and then acknowledges the message.

**Why this priority**: This is the store's reason to exist. Without it nothing downstream has data.

**Independent Test**: Publish one real-shaped message to the broker. Check that one receipt (`STORED`), one share, one payload, the defendant rows and one hearing-day row exist, and that the message is no longer on the subscription.

**Acceptance Scenarios**:

1. **Given** an empty store, **When** a valid share for hearing H, day D, shared at T arrives, **Then** a receipt keyed by the broker's message id moves from `RECEIVED` to `STORED` and points at the new share.
2. **Given** the same arrival, **When** the store transaction commits, **Then** there is one `hearing_share` row with a share id computed from H, D and T, the latest flag set, no predecessor, and the key details filled.
3. **Given** the same arrival, **Then** `hearing_share_payload` holds the text exactly as it arrived, its size in bytes, and a parsed copy; the share's checksum is the SHA-256 of that text.
4. **Given** the payload lists two defendants on one case and one of them again on a second case, **Then** `share_defendant` holds three rows, ids only.
5. **Given** the same arrival, **Then** `hearing_day_head` for (H, D) names this share as latest, has a share count of 1 and holds the day's youth flag.
6. **Given** the store transaction has committed, **Then** the message is acknowledged and is not delivered again.

---

### User Story 2 - Drop a redelivered or re-sent share (Priority: P2)

The broker redelivers a message, or hearing sends the same share twice. The store keeps the first copy, marks the new receipt `DUPLICATE`, raises no error and acknowledges.

**Why this priority**: Redelivery is normal broker behaviour. Without this, every redelivery would make a second share or an error loop.

**Independent Test**: Publish the same share twice (two different broker message ids). Check one share row, one `STORED` receipt and one `DUPLICATE` receipt that points at the existing share.

**Acceptance Scenarios**:

1. **Given** share (H, D, T) is stored, **When** a new message with the same three values arrives, **Then** no share row is added, the first payload stays, the receipt is `DUPLICATE` with the existing share's id, and the message is acknowledged.
2. **Given** a duplicate, **Then** nothing is rolled back, nothing is dead-lettered, and the duplicate metric goes up by one.
3. **Given** the duplicate payload differs from the stored one, **Then** it is still `DUPLICATE`; the store does not compare payloads and records no anomaly.
4. **Given** two copies of one share arrive at the same moment on two consumers, **Then** exactly one is `STORED` and one is `DUPLICATE`.
5. **Given** a receipt that is already `STORED` and the broker delivers the same message id again, **Then** the receipt's attempt count goes up, its status stays `STORED`, and the message is acknowledged with no store work.

---

### User Story 3 - Record a message that is not a share (Priority: P3)

A message arrives whose body is not JSON, or that lacks `hearing.id`, `hearingDay` or `sharedTime`. The store records it with the reason and the message text, counts it, and acknowledges it. It is never redelivered and never dead-lettered.

**Why this priority**: *There is nothing to fix here.* Without this, a bad message would loop until it reached the dead-letter queue and hide real failures.

**Independent Test**: Publish a non-JSON body and a JSON body without `sharedTime`. Check two receipts, `UNREADABLE` and `NO_IDENTITY`, each with reason and text; no share rows; both messages gone from the subscription; both counters up.

**Acceptance Scenarios**:

1. **Given** a body that is not JSON, **When** it arrives, **Then** the receipt is `UNREADABLE` with a reason and the message text, the metric goes up, and the message is acknowledged.
2. **Given** a JSON body with `hearing.id` and `hearingDay` but no `sharedTime`, **When** it arrives, **Then** the receipt is `NO_IDENTITY`, the reason names `sharedTime`, the parts of the identity that were present are recorded, and the message text is kept.
3. **Given** either case, **Then** no row is written to `hearing_share`, `hearing_share_payload`, `share_defendant` or `hearing_day_head`.
4. **Given** either case, **Then** the message is not redelivered.

---

### User Story 4 - A late share and two shares of one day at once (Priority: P4)

Shares of one hearing day can arrive out of order, and on different pods at the same time. *Arrival order never decides which share is latest; `sharedTime` does.*

**Why this priority**: Consumers rely on "latest" and on the chain of versions. A wrong latest would send stale results.

**Independent Test**: Store shares at T1 and T3 for one day, then deliver T2. Check the chain T1 ← T2 ← T3, T3 still latest, T2 flagged out of order. Then run two consumers on the shared subscription with 50 shares of one day delivered in random order.

**Acceptance Scenarios**:

1. **Given** T1 and T3 are stored and T3 is latest, **When** T2 arrives (T1 < T2 < T3), **Then** T2 is stored with latest = false and out-of-order = true, T2's predecessor is T1, T3's predecessor becomes T2, and T3 stays latest.
2. **Given** T1 is latest, **When** T2 (later) arrives, **Then** T1 stops being latest before T2 becomes latest, T2's predecessor is T1, and the day row names T2.
3. **Given** two shares of one day arrive on two pods at the same time, **Then** the second waits for the first to commit and works out "latest" against what is then stored.
4. **Given** 50 shares of one day delivered in random order on two consumers, **Then** all 50 are stored, exactly one is latest (the greatest `sharedTime`), each share's predecessor is the next earlier `sharedTime`, and the share count is 50.

---

### User Story 5 - An extraction failure does not stop a share being stored (Priority: P5)

Reading the key details from the payload fails. The share and its payload are still stored, and the row is marked `projection_status = FAILED` with a reason. A scheduled sweep retries failed rows.

**Why this priority**: *Never refuse to store.* The payload is the source of truth, so a bad field must not lose a share.

**Independent Test**: Publish a share whose `hearing.courtCentre.id` is not a UUID. Check share and payload stored, status `FAILED`, reason given, key-detail columns empty, metric up. Then raise the extractor version (or fix the cause) and run the sweep: status `OK`, columns filled.

**Acceptance Scenarios**:

1. **Given** a share whose key details cannot be read, **When** it arrives, **Then** the share, payload and receipt (`STORED`) are written, `projection_status` is `FAILED`, `projection_reason` says which field failed, and the key-detail columns are empty.
2. **Given** a `FAILED` row, **Then** its reason contains no payload text.
3. **Given** a `FAILED` row whose extraction version is older than the current one, **When** the sweep runs, **Then** it re-reads the stored payload text, fills the columns, sets `OK` and recomputes the day's youth flag under the hearing-day lock.
4. **Given** a `FAILED` row whose reason is an unexpected error, **When** the sweep runs, **Then** it retries until 3 attempts have been made, then leaves the row `FAILED`.
5. **Given** two pods run the sweep at once, **Then** each row is processed at most once per round and the result is the same as with one pod.

---

### User Story 6 - A database outage in the middle of storing (Priority: P6)

The database fails while a share is being stored. Everything in the store transaction is rolled back. The listener waits a short, capped time and the broker redelivers. When the database is back, exactly one share is stored.

**Why this priority**: Outages happen. A short outage must not lose a share, make two, or use up the broker's redelivery attempts.

**Independent Test**: Make the first store attempt fail (or the first message commit fail), then let it succeed. Check one share, the receipt `STORED` with attempt count 2.

**Acceptance Scenarios**:

1. **Given** the store transaction fails part way, **Then** no share, payload, defendant or day-row change remains, and the receipt is still `RECEIVED`.
2. **Given** a retryable failure on delivery n, **Then** the listener waits min(2^n seconds, 30 seconds) before rolling the message back.
3. **Given** the redelivery succeeds, **Then** there is exactly one share and the receipt is `STORED` with its attempt count showing every delivery.
4. **Given** the store transaction committed but the message acknowledgement failed, **When** the message is redelivered, **Then** it is recognised by the receipt or the unique key and no second share is made.
5. **Given** a failure that keeps happening, **Then** the message rolls back until the broker's attempts run out and the broker moves it to its dead-letter queue; the receipt shows the attempt count.

---

### User Story 7 - Operators can see what happened (Priority: P7)

Support staff see counts of messages received, shares stored, non-shares, duplicates, extraction failures, failed intake attempts and share-to-store lag on the dashboard. Logs carry ids only.

**Why this priority**: The page's alerts and dashboard depend on these metrics. Logs must never leak case data.

**Independent Test**: Run the scenarios of stories 1 to 6 and read the metrics endpoint. Check each counter moved by the expected amount, every tag value comes from a fixed list, and no captured log line contains payload text.

**Acceptance Scenarios**:

1. **Given** one share stored, one duplicate and one unreadable message, **Then** received = 3, stored = 1, duplicate = 1, not-a-share (unreadable) = 1.
2. **Given** a share is stored, **Then** the lag timer records `stored_at − shared_at`.
3. **Given** any intake, **Then** log lines include the message id, `hearingId` and `shareId` where known, and never the payload or message text.
4. **Given** any metric, **Then** no tag holds an id, a date or free text.

### Edge Cases

- A message with no broker message id: it is keyed by `sha256:<checksum of its text>` and counted.
- A payload containing the character `\u0000`: the text is stored; the parsed copy is left empty; a counter goes up.
  *Amended by spec 005*: the parsed copy is stored with the escape removed; nothing is counted (specs/005-payload-simplification FR-001, FR-005).
- A share sent between 00:00 and 01:00 BST: `shared_day_london` and `shared_day_utc` differ, and both are stored. Both clock-change days are covered.
- `sharedTime` written with different numbers of fraction digits for the same instant (for example `…50.706Z` and `…50.7060Z`): see FR-012.
- A defendant with no `isYouth` (for example a company): the share's youth flag is empty (unknown), not false.
- A payload of 2.4 MB (the largest seen in production): stored byte for byte with a matching checksum.
- A hearing-day lock held by another transaction for longer than the configured lock timeout: the store transaction rolls back and the message is redelivered.
- A redelivery of a message whose receipt is already in an end state: acknowledged with no further work.
- Hearing-level `youthCourtDefendantIds` and `youthCourt`: recorded as stated; nothing is derived from them.

## Requirements *(mandatory)*

### Functional Requirements

**Receipt**

- **FR-001**: The store MUST receive `public.events.hearing.hearing-resulted` through a *shared durable subscription* to the `public.event` topic, *filtered by the broker on the event name (`CPPNAME`)*, with no client id. *Each pod handles one message at a time.*
- **FR-002**: *As soon as the message arrives*, before any other work, the store MUST write a receipt *in its own short transaction*, *keyed by the broker's message id*, with status `RECEIVED`, first and last received times, the broker's delivery count and an attempt count of 1.
- **FR-003**: *If the same message is delivered again, the receipt is updated (its attempt count goes up) rather than written twice.* The attempt count MUST go up on every delivery. Status, reason and message text MUST change only while the status is `RECEIVED`. The time it was settled MUST be set when the status moves to an end state (`STORED`, `DUPLICATE`, `UNREADABLE`, `NO_IDENTITY`).
- **FR-004**: When a delivered message's receipt is already in an end state, the store MUST acknowledge it without identifying or storing again.
- **FR-005**: A message with no broker message id MUST be keyed `sha256:<SHA-256 of its text>`, and a counter MUST go up.
- **FR-006**: The listener MUST use a transacted session. *The message is acknowledged only after the store transaction commits*, or after a non-share has been recorded. Any other failure MUST roll the message back to the broker.

**Identify**

- **FR-007**: The store MUST read `hearing.id`, `hearingDay` and `sharedTime` from the message body (the envelope JSON, with the payload keys beside `_metadata`). *Nothing else in the payload is validated: the payload is stored as sent.*
- **FR-008**: *If the body is not JSON*, the store MUST set the receipt to `UNREADABLE` with a reason and the message text, count it, acknowledge it, and write nothing to the share tables. One exception: a text containing a raw NUL character (U+0000) cannot be stored by the database, so its receipt carries the reason `NUL_CHARACTER` and no text.
- **FR-009**: *If … any of the three is missing*, or is not in the form the event schema states (`hearing.id` a UUID, `hearingDay` `yyyy-MM-dd`, `sharedTime` a date-time), the store MUST set the receipt to `NO_IDENTITY` with a reason naming the field, the message text and whichever identity parts were present; count it; acknowledge it; and write nothing to the share tables.
- **FR-010**: A non-share MUST NOT be retried or dead-lettered (*not retried or dead-lettered; it is recorded and acknowledged*).
- **FR-011**: The share id MUST be a UUID computed from `hearingId`, `hearingDay` and `sharedTime`, *so the same share always gets the same id*. The computation MUST use a fixed namespace and MUST be proven with fixed known input/output pairs.
- **FR-012**: The share id MUST be computed from the three identity strings exactly as they appear in the message. The store MUST treat two messages whose parsed `hearingId`, `hearingDay` and `sharedTime` instant are equal as the same share, whatever the spelling of `sharedTime` (for example `2026-10-02T14:19:50.706Z` and `2026-10-02T14:19:50.7060Z`): the second is a duplicate of the first (FR-014), and its receipt MUST point at the share id already stored, not at a freshly computed one. Hearing writes `sharedTime` as UTC with millisecond precision, so differing spellings are not expected; the rule exists so that a different spelling can never create a second version.

**Store**

- **FR-013**: Everything that makes a share stored MUST happen in one transaction: *lock the hearing day (insert the `hearing_day_head` row if it is the first share of that day)*; insert the share; insert the payload; write the key details and defendant rows; update the youth flags; move the latest pointer; mark the receipt `STORED` with the share id. *All or nothing.*
- **FR-014**: The share MUST be inserted with a unique key on `hearingId`, `hearingDay` and `sharedTime` and *ON CONFLICT DO NOTHING*. If nothing was inserted, the store MUST mark the receipt `DUPLICATE` with the **existing** share's id (looked up by the three values), commit and acknowledge. *There is no separate idempotency table.* No payload comparison is made and no anomaly is recorded.
- **FR-015**: The payload MUST be stored as the exact text that arrived, with its size in bytes, and a parsed copy. If the text contains `\u0000`, the parsed copy MUST be left empty and a counter MUST go up. Nothing in 001 reads the parsed copy.
  *Amended by spec 003*: the read API serves the working copy (the parsed copy as spec 002 made it) without `_metadata`, and the text, likewise without it, when the working copy is empty (specs/003-read-api FR-033).
  - *Amended by spec 002* (see [spec 002, *Changes to spec 001*](../002-enrichment/spec.md#changes-to-spec-001)): the parsed copy is no longer unread. It holds the enriched working copy, is permanent, and is the copy key details are read from. The text stays exactly as it arrived.
  - *Amended by spec 005* (specs/005-payload-simplification FR-001 to FR-005): the `\u0000` escape and unpaired surrogate escapes are removed from the parsed copy, so it is never left empty; the counter is withdrawn. A text the database still cannot hold as `jsonb` fails like any database error.
- **FR-016**: The share MUST hold `payload_sha256`: the SHA-256 of the stored text in UTF-8, as 64 hex characters.
  - *Amended by spec 002* (see [spec 002, *Changes to spec 001*](../002-enrichment/spec.md#changes-to-spec-001)): unchanged in meaning; `payload_sha256` is over the arrived text, never the enriched working copy.
- **FR-017**: The share MUST hold `shared_at` (from `sharedTime`), `shared_day_london` (its date by the UK clock), `shared_day_utc` (its UTC date), `stored_at` (the database clock when the row is inserted) and `stored_seq` (*given by the database when the share is inserted*; it only goes up and may have gaps).
- **FR-018**: The key details MUST be read from the payload into nullable columns: court centre id (`hearing.courtCentre.id`), courtroom id (`hearing.courtCentre.roomId`), LJA code (`hearing.courtCentre.lja.ljaCode`), jurisdiction (`hearing.jurisdictionType`), SJP (`hearing.isSJPHearing`), group proceedings (`hearing.isGroupProceedings`), youth court id (`hearing.youthCourt.youthCourtId`) and re-share (`isReshare`). A missing optional field MUST leave its column empty with status `OK`.
- **FR-019**: The defendant index MUST hold, per share, `case_id`, `defendant_id` and `master_defendant_id`, one row per (case, defendant), from `hearing.prosecutionCases[].id` and `.defendants[].id` / `.masterDefendantId`. Repeats in one payload MUST be merged. The same defendant on two cases MUST give two rows. *Ids only, no names or dates of birth.* Defendants that appear only as court-application parties (`courtApplications[].applicant/respondents/subject/thirdParties[].masterDefendant`) are NOT indexed in this feature; court applications are the subject of the enrichment feature (spec 002), which decides how they are indexed. A share whose hearing has no prosecution cases therefore has no defendant rows, and that is not an extraction failure.
- **FR-020**: The store transaction MUST be bounded by configurable lock-wait, statement and idle-in-transaction timeouts that apply to that transaction only. Exceeding one MUST roll back the whole transaction and the message MUST be redelivered.
- **FR-021**: Key-detail extraction MUST run before the store transaction opens, so a bad field never holds the lock and never fails the share.

**Chain**

- **FR-022**: *Which version is latest? The one with the greatest `shared_at`, worked out under the hearing-day lock. Never by arrival order.* At most one share per hearing day MUST be latest, enforced by the database.
- **FR-023**: When a new share has the greatest `shared_at` of its day, the old latest MUST be cleared before the new one is set; the new share's predecessor MUST be the old latest; the day row MUST name the new share.
- **FR-024**: *A share arrives late: stored with `is_latest = false` and `arrived_out_of_order = true`, and linked into its place by `shared_at`. The latest share does not change.* Its predecessor MUST be the share with the next earlier `shared_at`, and the share with the next later `shared_at` MUST have its predecessor changed to the late share.
- **FR-025**: The day row's share count MUST go up by one per stored share. Version numbers MUST NOT be stored.

**Youth**

- **FR-026**: Each share's `any_subject_is_youth` MUST be TRUE if any `hearing.prosecutionCases[].defendants[].isYouth` is true; FALSE only if every defendant states false; empty (unknown) if any defendant does not state it or extraction failed.
- **FR-027**: The day's `youth_seen` MUST be recomputed under the lock from the day's shares: TRUE once any share is TRUE (and it stays TRUE); else empty if any share is empty; else FALSE.
- **FR-028**: When the day's flag changes, `day_youth_seen` MUST be set to the new value on every share of that day, under the lock.
- **FR-029**: The youth court id MUST be recorded as stated in the payload. The store MUST NOT derive anything from `youthCourtDefendantIds` or `hearing.youthCourt`.

**Projection failure**

- **FR-030**: *If extracting the key details fails, the share and its payload are still stored and the row is marked `projection_status = FAILED`; the key-detail columns stay empty.* A field that is present but of the wrong type, or an id that is not a valid UUID, MUST count as an extraction failure.
- **FR-031**: `projection_status` MUST be `OK` or `FAILED` only. A `FAILED` row MUST have a short reason naming the field or saying the error was unexpected; the reason MUST NOT contain payload text. The row MUST record the extraction version and the number of attempts.
- **FR-032**: An extraction failure MUST be counted.

**Sweep**

- **FR-033**: *A scheduled sweep retries FAILED rows automatically.* It MUST select `FAILED` rows only.
- **FR-034**: For each row, the sweep MUST take the hearing-day lock, then lock the share row and check it is still `FAILED` before working on it.
- **FR-035**: The sweep MUST retry a row when its extraction version is older than the current one, or when its reason is an unexpected error and fewer than 3 attempts have been made.
- **FR-036**: The sweep MUST read the stored payload text, never the parsed copy. On success it MUST fill the key-detail columns and defendant rows, set `OK`, and recompute the youth flags under the lock (FR-026 to FR-028).
  *Amended by spec 003*: a share the sweep fixes keeps its `storedSeq`; a pull behind it does not present it again, and a consumer re-reads the share (specs/003-read-api FR-022, FR-023).
  - *Amended by spec 002* (see [spec 002, *Changes to spec 001*](../002-enrichment/spec.md#changes-to-spec-001)): the sweep reads the parsed copy (`payload_json`), and the text only when the parsed copy is empty.
- **FR-037**: A failure on one row MUST be counted and MUST NOT stop the sweep. The sweep MUST run on its own scheduler. It MUST be correct when several pods run it at once, without a distributed lock.

**Observability**

- **FR-038**: The store MUST publish counters for: messages received; shares stored; messages recorded as not shares, by reason (`UNREADABLE`, `NO_IDENTITY`); duplicates dropped; extraction failures; failed intake attempts; messages with no broker message id; payloads whose parsed copy was skipped; and sweep outcomes.
- **FR-039**: The store MUST record the share-to-store lag, `stored_at − shared_at`, for every stored share.
- **FR-040**: Every metric tag MUST come from a fixed, small list of values. A tag MUST NOT hold an id, a date or free text. Metrics MUST be fired only after the transaction they describe has committed.
- **FR-041**: *`shareId` and `hearingId` go in the logging context*, with the message id. The logging context MUST be cleared after each message. A log line MUST NOT contain the payload or message text.

**Schema**

- **FR-042**: Migration V1 MUST NOT be changed. V2 MUST reshape `event_receipt` (message id as key, nullable identity, status limited to the five values, attempts, delivery count, first and last received, settled time, a bounded reason, message text, share id), and MUST refuse to run if V1's table holds any rows.
- **FR-043**: V3 MUST create `hearing_day_head`, `hearing_share`, `hearing_share_payload` and `share_defendant` with these rules enforced by the database: one share per (`hearingId`, `hearingDay`, `sharedTime`); one latest share per day; checksum is 64 hex characters; message text only on non-share receipts; a `FAILED` row has a reason; `stored_seq` cannot be set by the caller; `expires_at` stays empty.
- **FR-044**: Only these columns MAY change after insert: on the share, under the lock, `is_latest`, the predecessor and `day_youth_seen`; and, by the sweep only, the key-detail columns (including `any_subject_is_youth`), the `projection_*` columns and `projection_tried_at` (stamped on every sweep attempt, whatever the outcome). On the day row, the latest share, the share count and `youth_seen`. *Nothing else is ever updated.*

**Configuration**

- **FR-045**: On a retryable failure the listener MUST wait min(2^deliveryCount seconds, 30 seconds), using the broker's delivery count, before rolling back. The pause MUST be configurable and switchable off; it is off in tests except the one that proves it.
- **FR-046**: The store-transaction timeouts (FR-020), the sweep schedule and batch size, and the sweep retry limit (3) MUST be configurable through typed settings that are checked when the service starts. A bad value MUST stop the service starting.

**End-to-end**

- **FR-047**: The container smoke script MUST publish a real-shaped `hearing-resulted` message (identifiers only, synthetic values) to the compose broker, then the same message again, then an unreadable one, and check the expected rows: receipts `STORED`, `DUPLICATE` and `UNREADABLE`; one share; its payload; its defendant rows; its day row.

### Key Entities *(include if feature involves data)*

> *Amended by spec 002* (see [spec 002, *Changes to spec 001*](../002-enrichment/spec.md#changes-to-spec-001)): *Payload* and *Share* below describe 001. From 002, `payload_json` is the working copy (the arrived text parsed, with finalised application results added at intake, without the three amendment fields), permanent, read by key details and the sweep; `enrichment_applied` is true when at least one application received results. FR-019 (application-party defendants) stays deferred.

- **Receipt (`event_receipt`)**: one row per message received. Identity: the broker's message id. Holds arrival times, delivery and attempt counts, the share's identity when present, status, settled time, reason, message text (non-shares only), and the share id once stored or found as a duplicate. Updatable: attempt count, last received and delivery count on every delivery; status, reason, text and share id only while `RECEIVED`.
- **Hearing day (`hearing_day_head`)**: one row per (`hearingId`, `hearingDay`). The lock point for writes. Holds the latest share, the share count and `youth_seen`. All three change under the lock.
- **Share (`hearing_share`)**: one row per share. Identity: `share_id`, computed from `hearingId`, `hearingDay`, `sharedTime`, with a unique key on the three. Fixed at insert: identity, `shared_at`, both shared days, `stored_at`, `stored_seq`, checksum, `arrived_out_of_order`, `enrichment_applied`. Updatable under the lock: `is_latest`, predecessor, `day_youth_seen`. Updatable by the sweep only, while the row is `FAILED`: key-detail columns (including re-share, `isReshare`, which is read with them), `any_subject_is_youth`, `projection_status`, `projection_reason`, `projection_version`, extraction attempts, `projected_at`. Stamped by the sweep only, after every attempt whatever the outcome or status: `projection_tried_at`. `expires_at` is present and empty.
- **Payload (`hearing_share_payload`)**: one row per share. The exact text, its size in bytes, and a parsed copy that may be empty. Never updated.
- **Defendant index (`share_defendant`)**: rows keyed by (share, case, defendant), with the master defendant id. Ids only. Written at store time, or by the sweep for a row that failed extraction (which has none). Insert-only: never updated or deleted.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the compose stack, a published share is stored and acknowledged within 1 second of being published.
- **SC-002**: 0 shares lost across 50 out-of-order deliveries of one hearing day on two consumers; exactly 1 latest share; every predecessor link matches `sharedTime` order; share count 50.
- **SC-003**: The same share delivered twice, one after the other and at the same moment, leaves exactly 1 share row, 1 `STORED` receipt and 1 `DUPLICATE` receipt, with 0 errors raised.
- **SC-004**: 100% of non-share messages in the tests end `UNREADABLE` or `NO_IDENTITY` with a reason and the text, and 0 of them are redelivered.
- **SC-005**: After a database failure on the first attempt, exactly 1 share is stored and its receipt shows 2 attempts.
- **SC-006**: 100% of shares whose extraction fails are still stored with their payload; after the cause is fixed, 1 sweep run sets them `OK`.
- **SC-007**: Every receipt written in the integration tests ends in an end state (`STORED`, `DUPLICATE`, `UNREADABLE`, `NO_IDENTITY`).
- **SC-008**: A 2.4 MB payload is stored byte for byte and its stored checksum matches a fresh checksum of the text.
- **SC-009**: With the hearing-day lock held elsewhere, the store transaction gives up within the configured lock timeout plus 1 second, leaves no rows and does not change the next transaction's timeouts.
- **SC-010**: 0 log lines captured in the tests contain payload or message text; 0 metric tags hold an id.
- **SC-011**: The build gate passes: line coverage at least 0.88, branch coverage at least 0.85 (JaCoCo), with PMD clean.
- **SC-012**: The container smoke check passes in CI with the three published messages and the expected rows.

## Assumptions

Settled implementation choices from the approved plan, stated so they are visible:

- Extraction runs before the store transaction opens; a bad field never fails the share.
- `stored_at` defaults to the database clock at the moment of insert (`clock_timestamp()`), so lag includes any lock wait.
- The store transaction is capped with transaction-local database timeouts; a whole-transaction timeout is used only if the production database is PostgreSQL 17 or later (checked in research.md).
- `projection_status` has two values, `OK` and `FAILED` (no pending state); the sweep is version-gated with 3 retries and never depends on a distributed lock.
- The parsed payload copy is nullable, with a pre-check for `\u0000`; nothing in 001 reads it.
- A `DUPLICATE` receipt points at the existing share's id, looked up by identity.
- Metrics go through an intake observer port; the outcome-to-tag mapping lives outside the config package so coverage measures it.
- The constitution is at 2.0.0 (the first commit on this branch) before implementation starts.
- Consumer search indexes are left to spec 003. *Amended by spec 003*: added in `V5__read_api.sql`.
