# Feature Specification: Operations API

**Feature Branch**: `004-operations-api`
**Created**: 2026-10-03
**Status**: Draft
**Input**: User description: "Operations API: the support endpoints under /operations that let Second Line Support re-run extraction, see the sweep's state, look up receipts and read a daily reconciliation, without ever seeing a payload"

**Sources**: the Results Store design page (CRA 321061800), sections *Operations API*, *Data model and versioning*, *Reconciliation*, *Security* and *Observability*; the design review of spec 004 and its critique, with the orchestrator's rulings on both (2026-10-03, sections A, C and D), which win where they differ from the design; the fact-finding report on the store's read side (this repository at `c21a901`); specs 001 (*Share intake*), 002 (*Enrichment*) and 003 (*Read API*, as written on branch `003-read-api` at `b74c43b`), whose web edge, error shape, names and migration V5 this spec builds on. Quotes in *italics* are the design page's or the rulings' wording.

### Scope

**In scope.** Four endpoints under `/operations`: a request to re-run extraction for a set of shares, the extraction status, a receipts lookup, and a daily reconciliation. The extraction sweep working rerun requests, which rewrites an `OK` share's key details in place from its stored working copy. Migration V6: the two rerun tables, the sweep's last round per pod, a replaced share guard that lets an `OK` row change only while a pending rerun names it, and the indexes the operations reads need. Four allow rules for "Second Line Support". Metrics. The support contract (`contracts/operations-api.md`). An end-to-end check through the compose stack. Documentation changes, performed as the last task: constitution 2.3.0 (Principle I), design rules, spec 001 forward notes, spec 003's consumer contract on values that change in place, forward notes for the page owner and the consumer teams.

**Out of scope.**

- A replay endpoint. *There is no replay endpoint*: a message on the broker's dead-letter queue is moved back with the broker's tools.
- The nightly reconciliation job and the `reconciliation_finding` table (D-NIGHTLY, pending Sachin). 004 computes the reconciliation on demand and stores nothing.
- A sampled re-extraction check for R2 (D-R2, pending Sachin). 004's R2 is counts only.
- A feed or notification that tells consumers a share's youth subject was raised from `false` to `true` (D-YOUTH-RAISE). Until it exists, such a change is held, not written.
- An operator endpoint to cancel a rerun request (D-RERUN-CANCEL). An item that keeps failing is abandoned after a set number of tries instead.
- Removing a share's defendant rows that a newer extractor no longer finds. Defendant rows stay add-only.
- Retention, purge and erasure, including of the rerun tables (D-RERUN-ERASURE).
- Any change to the read API's endpoints, beyond the contract text that says a rerun can rewrite key details in place.
- Deployment values in `cpp-aks-deploy` and the Azure Monitor alert rules. A separate task.

### Why

*`/operations/**` is for "Second Line Support" only, and never returns a payload.* Support staff need to fix shares the extractor read wrongly, see whether the sweep is running, find out what happened to a message, and check that every message received reached an end state. Today none of that is possible without database access. Spec 001 also left one gap on purpose: *an `OK` row is never re-extracted in 001 (marking rows for a rerun is spec 004)*. When the extractor is fixed, shares already stored `OK` keep the wrong key details until an operator can ask for them to be read again.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Support staff re-run extraction for a set of shares (Priority: P1)

A fix to the extractor ships. Support staff ask the store to read again every share stored in a time range, every share of some hearings, or a list of shares, giving a reason. The store answers at once with a request id and counts. Over the next rounds the sweep reads each share's stored working copy again and rewrites its key details in place. A share never disappears from consumers' searches while it waits, never moves to `FAILED`, and never loses a youth flag that was `true`.

**Why this priority**: This is the one operator action that changes data. Without it an extractor bug stays in every share stored before the fix.

**Independent Test**: Store a share whose key-detail columns differ from what its working copy states (inserted directly, as an old extractor bug would have left it). Post a rerun for that share with a reason: `202`, `matched` 1, `queued` 1. Run a sweep round: the share is still `OK`, its key details are now what the working copy states, `projectionVersion` and `projectedAt` moved, its `storedSeq` did not, and the request is `DONE`.

**Acceptance Scenarios**:

1. **Given** a body with exactly one selector and a valid reason, **When** it is posted, **Then** the answer is `202` with `rerunId`, `status`, `selectorKind`, `matched`, `queued`, `alreadyPending`, `unknownShareIds` and `repeat`, and no share row has changed.
2. **Given** the same selector posted again while the first request is open, **Then** the answer is `202` with the first request's id and counts and `repeat` true, and nothing new is written.
3. **Given** a selector that matches more shares than the limit, **Then** `400 selector_too_wide` and nothing is written.
4. **Given** an `OK` share whose re-read gives other key details, **When** the sweep works its item, **Then** the share is rewritten in place, stays `OK`, keeps its `storedSeq`, and the item's outcome is `REEXTRACTED`.
5. **Given** an `OK` share whose re-read fails, **Then** nothing is written to the share and the outcome is `KEPT`.
6. **Given** an `OK` share whose youth subject is `true` and whose re-read is not `true`, **Then** nothing is written and the outcome is `YOUTH_KEPT`.
7. **Given** an `OK` share whose youth subject is `false` and whose re-read is `true`, **Then** nothing is written, the outcome is `YOUTH_RAISE_HELD`, and the share is listed in the status (pending Sachin).
8. **Given** a share whose stored extraction is newer than the sweep's extractor (a rolling deploy), **Then** nothing is written and the outcome is `NEWER_KEPT`.
9. **Given** an item whose write fails for an operational reason three times, **Then** it is `ABANDONED`, counted, and shown in the status; its request can then close.

---

### User Story 2 - Support staff see the state of extraction and of the sweep (Priority: P2)

Support staff open the extraction status. They see how many shares are waiting for a retry, how many have run out of retries, how many rerun items are pending, the most recent rerun requests with their outcome counts, the shares held or abandoned, and when each pod's sweep last ran and what it did.

**Why this priority**: A rerun takes rounds to finish; staff need to see it progress and see a sweep that has stopped. The sweep runs on every pod, in memory, so without a stored record nobody can tell "ran, nothing to do" from "not running".

**Independent Test**: Seed `FAILED` rows of each kind, two rerun requests (one open, one done) with items in several outcomes, and two pods' sweep rounds. Read the status: every count matches the seed, the requests are newest first with their outcome counts, a pod older than a day is gone, and no reason text or operator id appears.

**Acceptance Scenarios**:

1. **Given** `FAILED` rows due a retry, out of retries, and waiting for a new extractor, **Then** the three counts match.
2. **Given** a sweep round on a pod, **Then** that pod's row shows when the round started and finished, its extractor version and its counts; an empty round updates `finishedAt` but not `lastWorkedAt`.
3. **Given** items held or abandoned, **Then** their counts and up to 50 of their share ids each are shown.
4. **Given** any status answer, **Then** it holds no rerun reason and no operator id.

---

### User Story 3 - Support staff find out what happened to a message (Priority: P3)

Support staff look up the receipts of one hearing day, or one receipt by its message id. They see its status, attempts, delivery count, times, bounded reason and the share it stored, never the message text.

**Why this priority**: The first question in every incident is "did the store get it, and what did it do with it?".

**Independent Test**: Store a share, a duplicate and an unreadable message. Look up the share's hearing day: two receipts (`STORED`, `DUPLICATE`) naming the same `shareId`. Look up the unreadable one by message id: `UNREADABLE` with its reason, and no field that holds its text.

**Acceptance Scenarios**:

1. **Given** `hearingId` and `hearingDay`, **Then** that day's receipts come back ordered by first arrival, then message id, at most 200, with `truncated` true when more exist.
2. **Given** `messageId`, **Then** that receipt comes back, or an empty list.
3. **Given** both forms at once, **Then** `400 conflicting_parameters`; given only `hearingId`, **Then** `400 missing_parameter`.
4. **Given** a receipt that holds message text, **Then** no response holds any of it.

---

### User Story 4 - Support staff check one day's intake (Priority: P4)

Support staff ask for the reconciliation of a London calendar day. They see how many messages arrived that day and where each ended, how many shares were stored, how many failed extraction, and the R1 finding: messages still `RECEIVED` long after their last delivery.

**Why this priority**: Constitution VI: *the receipt log lets reconciliation prove nothing was lost*. This makes the proof visible without a nightly job.

**Independent Test**: Seed receipts across a London midnight in summer time, one still `RECEIVED` with its last delivery two hours ago and one still `RECEIVED` a minute ago. Ask for the day: the counts include only that London day, R1 lists the old one and not the recent one.

**Acceptance Scenarios**:

1. **Given** a date, **Then** the window is that day's London midnight to the next, 23 or 25 hours long on clock-change days.
2. **Given** a receipt still `RECEIVED` whose last delivery is older than the give-up window, **Then** it is an R1 finding; one delivered more recently is not.
3. **Given** a date after today in London, **Then** `400 date_in_future`; today is allowed and marked `partial`.

---

### User Story 5 - Only Second Line Support gets in, and nothing leaks (Priority: P5)

Every operations route admits "Second Line Support" only. A "System Users" caller is refused, whatever action name or media type it sends. No response, error body, log line or metric holds a payload, a message text, the rerun reason or the operator's id.

**Why this priority**: *The store holds every defendant's results, including children's.* The rerun is the only endpoint that changes data.

**Independent Test**: With authorisation on and a usersgroups stub: each endpoint serves a "Second Line Support" caller and refuses a "System Users" caller with `403`, also when that caller sends an operations action name in `CPP-ACTION`, `Content-Type` or `Accept`. Seed marker strings in `message_text`, `payload_text`, `payload_json` and a rerun reason: no response body or log line holds one.

**Acceptance Scenarios**:

1. **Given** a "System Users" caller, **Then** every operations route gives `403`.
2. **Given** a caller with no `CJSCPPUID`, **Then** `401`.
3. **Given** `/operations/anything`, **Then** `404 route_not_found` before authentication; `GET` on the rerun route gives `405` with `Allow: POST`.
4. **Given** a rerun with a reason, **Then** the reason is stored, and never appears in a response, a log line or a metric.

---

### User Story 6 - Operators can alert on the rerun and the sweep (Priority: P6)

Operators see rerun requests accepted and repeated, shares queued, every rerun item outcome, requests finished, items abandoned or held, operations requests refused and why, and a failed attempt to record a sweep round.

**Why this priority**: Constitution VIII: *a path that drops or fails something moves a counter with a bounded reason*. An abandoned item and a held youth raise each need an alert.

**Independent Test**: Run the scenarios of stories 1 to 5 and read `/actuator/prometheus`. Each counter moved by the expected amount, every tag value is from a fixed list, and every meter exists at start with value 0, also with the subscription off.

**Acceptance Scenarios**:

1. **Given** an item abandoned, **Then** `resultsstore.sweep.rerun.rows{outcome=abandoned}` goes up by one.
2. **Given** a `400` on the rerun route, **Then** `resultsstore.operations.refused{endpoint=rerun,reason=<code>}` goes up by one.
3. **Given** the service started with the subscription off, **Then** every operations and rerun meter is registered at 0.

### Edge Cases

- A share stored while a rerun request is being written: for a hearing or share list it may or may not be included; for a stored range it cannot be, because the range must end before the visibility lag (FR-007).
- The same share named by two open requests: the second does not queue it again (`alreadyPending`). When the first finishes, the share is not re-read for the second.
- An item claimed by a pod that dies mid-item: it stays pending, its claim time pushes it behind the others, and another pod works it later. Its attempts do not grow, because nothing failed that the store saw.
- A request whose every share is already pending elsewhere: `queued` 0; the request is `OPEN` and closes at the next round end.
- A rerun of a `FAILED` share: the existing retry path (`FIXED` or `FAILED_AGAIN`), whatever its attempts, because an operator asked.
- A rolling deploy with two extractor versions: an older pod never overwrites a newer reading (`NEWER_KEPT`); the status's `FAILED` counts follow the version of the pod that answers.
- A re-read finds a key detail absent that was stored (`courtRoomId` was set, now null): written (D-NEVER-BLANK, default allow). A youth subject that was `false` and is now unknown: written too (FR-021).
- A day that moves from unknown youth to `false` through a rerun: the day leaves the `notFalse` pull view with no new `storedSeq` (FR-028).
- Reconciliation for 29 March 2026 (23 hours) and 25 October 2026 (25 hours): the window follows the London clock.
- A receipt received before London midnight and stored after it: counted in the receipts of the first day and in the shares of the second.
- `GET /operations/receipts?messageId=ID:abc&hearingId=…`: `400 conflicting_parameters`.
- A rerun body with `storedFrom` only: `400 range_invalid`.
- A rerun body larger than 64 KiB: `400 body_too_large`.
- A `text/plain` rerun body: `415 unsupported_content_type`.
- A sweep switched off on every pod: requests are accepted and never progress; the status shows a stale `lastFinishedAt`.

## Requirements *(mandatory)*

### Functional Requirements

**Routes and common rules**

- **FR-001**: The service MUST serve exactly these routes under `/operations`: `POST /operations/extraction/rerun`, `GET /operations/extraction/status`, `GET /operations/receipts`, `GET /operations/reconciliation/daily`.
- **FR-002**: Spec 003's common rules MUST apply unchanged: query parameter names are case-sensitive; an unknown name gives `400 unknown_parameter` and a repeated one `400 repeated_parameter`; ids are canonical UUIDs; dates are `yyyy-MM-dd`; every field of every response is always present, with null written as `null`; every time is an ISO-8601 UTC instant with exactly six fraction digits (003 FR-002 to FR-005).
- **FR-003**: Every `2xx` response MUST be `application/json` with `Cache-Control: no-store`, and carry no `ETag` and no `Location`.
- **FR-004**: No operations response MUST hold payload text, the working copy, `event_receipt.message_text`, a rerun reason or an operator id. No operations query MUST name `hearing_share_payload` or `message_text`; the sweep's re-read of the working copy is the one payload read 004 adds, and it never reaches a response.

**Rerun request**

- **FR-005**: `POST /operations/extraction/rerun` MUST take a JSON object of at most 64 KiB with the fields `reason`, `storedFrom`, `storedTo`, `hearingIds` and `shareIds`. Any other field MUST give `400 unknown_field`; a body that is empty, not JSON, not an object, or has a field of the wrong JSON type MUST give `400 unreadable_body`; a larger body MUST give `400 body_too_large`; a `Content-Type` other than JSON MUST give `415 unsupported_content_type`. Query parameters MUST give `400 unknown_parameter`.
- **FR-006**: The body MUST name exactly one selector: a stored range (`storedFrom` and `storedTo`), `hearingIds`, or `shareIds`. None, or more than one, MUST give `400 selector_not_exactly_one`.
- **FR-007**: A stored range MUST be two RFC 3339 instants with an offset and at most six fraction digits, read as the half-open range [`storedFrom`, `storedTo`) of `storedAt`, with `storedFrom` before `storedTo`, and `storedTo` at or before the database's current time minus the read API's visibility lag (003 FR-017). Any breach, or only one of the two fields, MUST give `400 range_invalid`. A span longer than `resultsstore.operations.rerun.max-range` (default 31 days) MUST give `400 range_too_long`. The contract MUST say why: a share stored inside the lag may still be committing, and a range that ended before it can no longer gain a share.
- **FR-008**: `hearingIds` MUST hold 1 to `max-hearing-ids` (default 200) canonical UUIDs, duplicates removed, and select every share of every day of those hearings. An empty or longer list MUST give `400 hearing_ids_out_of_range`; a bad id `400 invalid_hearing_id`.
- **FR-009**: `shareIds` MUST hold 1 to `max-share-ids` (default 1,000) canonical UUIDs, duplicates removed. An empty or longer list MUST give `400 share_ids_out_of_range`; a bad id `400 invalid_share_id`. Ids the store does not hold MUST be counted in `unknownShareIds`, not refused.
- **FR-010**: `reason` MUST be present and, trimmed, 10 to 500 characters with no control characters; otherwise `400 invalid_reason`. It MUST be stored with the request and MUST never be logged, returned, or used in a metric.
- **FR-011**: The operator MUST be the request's `CJSCPPUID`, a canonical UUID. The rerun endpoint MUST itself refuse a missing or malformed `CJSCPPUID` with `401 unauthenticated`, so it never stores a request with no operator, even where authorisation is off. The operator id MUST never be logged or returned.
- **FR-012**: A selector that matches more than `max-matched` shares (default 200,000) MUST give `400 selector_too_wide`, with nothing written.
- **FR-013**: An accepted request MUST answer `202` with `{ rerunId, status, selectorKind, matched, queued, alreadyPending, unknownShareIds, repeat }`. `queued` MUST equal `matched` minus `alreadyPending`. A share already pending under another open request MUST NOT be queued again. A request that matches no share MUST be stored `DONE` at once and answer `status` `DONE`.
- **FR-014**: Writing a request MUST touch only the rerun tables, all or nothing, in one transaction with its own checked timeouts. `matched` and `queued` MUST be counted by the same statements that insert the items, in chunks of `chunk-size` (default 5,000) shares in `shareId` order.
- **FR-015**: The selector MUST be put in a canonical form (ids lower-case, sorted, duplicates removed; instants in UTC with six fraction digits) and hashed with SHA-256. While a request with the same hash is `OPEN`, a new post MUST answer `202` with that request's id and stored counts and `repeat` true, writing nothing; its reason MUST NOT be stored. Once the request is `DONE`, the same selector MUST create a new request. If the open request closes between the store's insert and its look-up, the store MUST try the insert once more.
- **FR-016**: Two identical requests posted at the same moment MUST leave exactly one open request.

**The sweep works rerun items**

- **FR-017**: Each sweep round MUST first work `FAILED` rows as today (spec 001), then claim up to `resultsstore.sweep.rerun-batch-size` (default 200) pending items, never-tried first, then oldest claim, then oldest queued. Claiming MUST stamp the claim time and skip items another pod holds, so two pods never work the same items in one round.
- **FR-018**: For each item the sweep MUST read the share's working copy (002 FR-033) and extract outside any transaction, then write in one transaction under the hearing-day lock, then the item's lock, then the share's lock, ending with exactly one of these outcomes:

  | Outcome | When | Share written |
  |---|---|---|
  | `SKIPPED` | the item is no longer pending (another pod worked it) | no |
  | `NEWER_KEPT` | the share's stored extractor version is newer than the sweep's | no |
  | `FIXED` | the share is `FAILED` and the re-read succeeds | yes, as the existing retry |
  | `FAILED_AGAIN` | the share is `FAILED` and the re-read fails | yes, as the existing retry |
  | `KEPT` | the share is `OK` and the re-read fails | no |
  | `YOUTH_KEPT` | the share is `OK`, its youth subject is `true`, the re-read's is not | no |
  | `YOUTH_RAISE_HELD` | the share is `OK`, its youth subject is `false`, the re-read's is `true` | no |
  | `UNCHANGED` | the share is `OK` and the re-read matches its key details, youth subject and defendant rows | only the version, attempts and time, and only when the stored version is older |
  | `REEXTRACTED` | the share is `OK` and the re-read differs | yes, in place |

  Every outcome but `SKIPPED` MUST mark the item done with its outcome in the same transaction.
- **FR-019**: An `OK` share MUST stay `OK`. A re-read that fails MUST leave its key details as they were.
- **FR-020**: A share's extraction MUST never go back: the stored extractor version never goes down, the attempts go up by one on every write of the projection columns, and the extraction time never goes back.
- **FR-021**: A youth subject that is `true` MUST never be lowered. A move from `false` to `true` MUST be held: nothing written, outcome `YOUTH_RAISE_HELD`, counted and listed in the status, until a later spec adds a youth-raised feed (D-YOUTH-RAISE, pending Sachin). A move from unknown to `false` or `true` MUST be written. A move from `false` to unknown MUST be written (D-NEVER-BLANK default, pending Sachin).
- **FR-022**: Any other key detail MAY move from a value to null when the re-read no longer finds it (D-NEVER-BLANK, default allow, pending Sachin). The claim the store makes is "never moved to `FAILED`", not "never blank".
- **FR-023**: Defendant rows MUST be add-only: rows the re-read finds and the share lacks are added; none is removed or changed.
- **FR-024**: After a write that changes the youth subject, the day's youth flag MUST be recomputed and copied to every share of the day, as on the `FAILED` path.
- **FR-025**: An item whose write or payload read fails for an operational reason MUST stay pending with its attempt count raised by one. When the count reaches `resultsstore.sweep.rerun-max-attempts` (default 3), the item MUST be marked done with outcome `ABANDONED`, counted, and shown in the status (D-RERUN-CANCEL, pending Sachin).
- **FR-026**: At the end of every round the sweep MUST mark `DONE` each open request that has no pending item.
- **FR-027**: A stopping sweep MUST leave unstarted items pending (`CANCELLED` in the metrics, nothing stored).
- **FR-028**: A rerun MUST NOT give a share a new `storedSeq`. Its changes MUST reach consumers only through a re-read; spec 003's contract MUST say so, including that a day may leave or join the `notFalse` pull view this way, and that `projectionVersion` and `projectedAt` show when key details were last written.

**Database backstop**

- **FR-029**: Migration V6 MUST replace the share guard function. Its fixed-columns check MUST be V3's, unchanged. When any key-detail or `projection_*` column changes, four further checks MUST run in this order, each raising `restrict_violation` with its own name: `hearing_share_projection_version_guard` (version not lowered, attempts raised, time not earlier); and, for an `OK` row only, `hearing_share_projection_guard` (it stays `OK`), `hearing_share_youth_guard` (a `true` youth subject stays `true`), `hearing_share_rerun_guard` (a pending rerun item names the row). The documents MUST state honestly what this proves: that a pending item names the row, not that an operator asked (D-RERUN-GUARD, pending Sachin).
- **FR-030**: The rerun tables MUST refuse changes to a request's fixed columns, any change to a finished request, any change to an item's key, and any change to a done item. They MUST NOT refuse a delete, so a later retention or erasure spec can remove them (D-RERUN-ERASURE, pending Sachin).

**Extraction status**

- **FR-031**: `GET /operations/extraction/status` MUST take no parameters and return: the serving pod's extractor version and retry limit; counts of `FAILED` rows due a retry, out of retries, and waiting for a new extractor, worked out with the sweep's own rule and the serving pod's version; pending items, open requests, abandoned items and held items, the last two with up to 50 share ids each; the 20 most recent requests, any status, with their counts, pending items and outcome counts; and each pod's last sweep round. Lists over their cap MUST say `truncated`.
- **FR-032**: Every pod's sweep MUST record its last round, one row per pod, after every round, empty rounds included, with the round's times read from the database clock and the pod's extractor version. A pod's row MUST be dropped once it is older than `resultsstore.operations.status.pod-recent` (default 1 day). A failure to record MUST be counted and MUST NOT fail the round.
- **FR-033**: The status MUST show pods whose last round finished within `pod-recent`, most recent first, at most 20.

**Receipts**

- **FR-034**: `GET /operations/receipts` MUST take exactly one form: `hearingId` and `hearingDay`, or `messageId` (1 to 256 printable ASCII characters, no spaces, matched exactly). Both forms MUST give `400 conflicting_parameters`; neither, or half of the first, `400 missing_parameter`; a bad value `400 invalid_hearing_id`, `400 invalid_hearing_day` or `400 invalid_message_id`.
- **FR-035**: The answer MUST be `200 { receipts, truncated }`, each receipt with `messageId`, `status`, `hearingId`, `hearingDay`, `sharedTime`, `attempts`, `deliveryCount`, `firstReceivedAt`, `lastReceivedAt`, `settledAt`, `reason` and `shareId`, ordered by first arrival then message id, at most `resultsstore.operations.receipts.max-rows` (default 200). No match MUST give `200` with an empty list.
- **FR-036**: The receipts query MUST name its columns; a test MUST hold it to that exact list and refuse `*`.

**Daily reconciliation**

- **FR-037**: `GET /operations/reconciliation/daily` MUST take `date` (`yyyy-MM-dd`, required). A missing date MUST give `400 missing_parameter`, a bad one `400 invalid_date`, a date after today in London `400 date_in_future`.
- **FR-038**: The window MUST be [the date at 00:00 Europe/London, the next date at 00:00 Europe/London), as instants (D-RECON-CLOCK, pending Sachin). The answer MUST hold the date, the clock, the window, `partial` (true while the window has not ended), counts of receipts first received in the window by their current status, counts of shares stored in the window (all, out of order, extraction `FAILED`, `FAILED` and out of retries, read by an older extractor), the R1 finding and the R2 counts, and `computedAt`.
- **FR-039**: R1 MUST be the receipts first received in the window that are still `RECEIVED` and whose last delivery is older than `resultsstore.operations.reconciliation.received-give-up` (default 1 hour; D-R1-WINDOW, pending Sachin), with their count and up to 50 message ids, oldest first.
- **FR-040**: R2 MUST be counts only (`extractionFailed`, `staleVersion`), marked `sampled: false` (D-R2, pending Sachin).
- **FR-041**: Nothing MUST be stored by the reconciliation. The contract MUST say that today is partial and that shares inside the visibility lag are still being stored.

**Authorisation and audit**

- **FR-042**: The actions MUST be `results-store-operations.rerun-extraction`, `results-store-operations.get-extraction-status`, `results-store-operations.list-receipts` and `results-store-operations.get-daily-reconciliation`, derived from method and path by spec 003's action filter.
- **FR-043**: Each action MUST have one allow rule admitting "Second Line Support" only, which also matches the route's method and path. "System Users" MUST be refused on every operations route. Every read-API rule MUST keep admitting both groups (constitution VII: support staff *read payloads through the read API under its own rules*).
- **FR-044**: Spec 003's edge MUST apply to `/operations`: an unmapped path `404 route_not_found` before authorisation; a known path with another method, `HEAD` and `OPTIONS` included, `405 method_not_allowed` with `Allow`; `multipart/*` `415 unsupported_content_type`; a caller's `CPP-ACTION` overwritten and vendor media types answered as `application/json`; the service refusing to start with authorisation off outside the test profile (003 FR-050; D-AUTHZ-REQUIRED, pending Sachin).
- **FR-045**: Every operations request that reaches an endpoint MUST be audited by `cp-audit-filter-springboot`; refusals before the audit filter MUST be counted, not audited (D-REFUSALS-UNAUDITED, D-VII-AUDIT-WORDING, pending Sachin). Whether the library records the rerun request's body (and so the reason) has not been verified; a test MUST pin what it does, and the OpenAPI description MUST tell operators the reason must hold no personal data.

**Errors**

- **FR-046**: Every `4xx` and `5xx` MUST be spec 003's four-field body (003 FR-042) with `reason` from the list in `contracts/operations-api.md`; a database that cannot answer MUST give `503 store_unavailable` with `Retry-After`; any other failure `500 internal_error`, logged by exception class only.
- **FR-047**: Every operations read MUST run with a query timeout (`resultsstore.operations.statement-timeout`, default 10 seconds) below the driver's socket timeout.

**Schema**

- **FR-048**: Migration V6 MUST add the tables `extraction_rerun`, `extraction_rerun_item` and `sweep_round`, their checks, guards and indexes, the replaced share guard (FR-029), and three indexes for the operations reads: receipts by first arrival, receipts still `RECEIVED` by last delivery, and shares by `stored_at`. V1 to V5 MUST NOT be edited.

**Metrics**

- **FR-049**: The service MUST publish `resultsstore.operations.rerun.requests{selector,result}`, `resultsstore.operations.rerun.shares.queued{selector}`, `resultsstore.operations.refused{endpoint,reason}`, `resultsstore.sweep.rerun.rows{outcome}`, `resultsstore.sweep.rerun.requests.finished` and `resultsstore.sweep.round.record.failed`, with every tag value from a fixed list, all registered at start whatever `resultsstore.publicevents.enabled` says.

**Configuration**

- **FR-050**: The settings MUST be typed and checked at start (`contracts/configuration.md`): `resultsstore.operations.*` and three new `resultsstore.sweep.*` settings (`rerun-batch-size`, `rerun-max-attempts`, `pod-name`). The operations beans MUST be wired whatever `resultsstore.publicevents.enabled` says.

**Documentation (performed by the last task, not now)**

- **FR-051**: The constitution MUST move from 2.2.0 to 2.3.0 (MINOR; D-PRINCIPLE-I-BUMP, pending Sachin), with Principle I saying that the sweep re-reads an `OK` share only while a pending rerun item names it, that the share stays `OK`, that its extraction never goes back, and that a `true` youth subject stays `true`.
- **FR-052**: The design rules, spec 001's forward references ("`OK` is final in 001", "marking rows for a rerun is spec 004"), spec 003's consumer contract (values that change in place) and the page notes MUST be brought in line; the design page itself is not edited.

**End to end**

- **FR-053**: The container smoke check MUST, as a "Second Line Support" caller, read the status, look up the stored share's receipts, read today's reconciliation, post a rerun for the stored share and see a repeat, then wait for the item to be done with the share still `OK`; and MUST see `403` for a "System Users" caller, `401` with no identity and `404` for an unmapped operations path.

### Changes to spec 001, spec 002 and spec 003

004 amends or touches these parts. They are updated at the end of 004 (FR-051, FR-052); until then this section is the record.

- **001 `data-model.md`** (*Projection (extraction) status*: "`OK` (final in 001)"; "An `OK` row is never re-extracted in 001 (marking rows for a rerun is spec 004)"): an *Amended by spec 004* note. The history is not rewritten. V3's comment "OK is final in 001" is never edited; V6's comment supersedes it.
- **001 `spec.md`** *Out of scope* ("The operations API … spec 004"): a forward pointer to `specs/004-operations-api`. The dead-letter replay tooling stays out: there is no replay endpoint.
- **001 code**: `ShareStore`, `JdbcShareStore` and `ExtractionSweep` gain the rerun path; `SweepProperties` gains three settings. The `FAILED` path is unchanged, except that V6's version guard now refuses a write that would lower a row's extractor version (it can only happen in a rolling deploy).
- **001 `research.md`** ("ShedLock not used; spec 004 can add it if its status endpoint needs a 'last run' row"): honoured without ShedLock; each pod records its own last round.
- **002**: no change. The sweep's re-read uses `payloadForExtraction` as built (002 FR-033).
- **003 `contracts/read-api.md` §5.5** (*Values that change in place*): gains the rerun's rules (held `false` to `true`; unknown to `false` or `true` written; a day may leave or join the `notFalse` view; `projectionVersion` and `projectedAt`).
- **003 code**: `ApiRoute` gains four routes and its endpoint tag widens to cover operations endpoints; `ProblemReason` gains the operations reasons; the advice maps an unsupported request media type to `415 unsupported_content_type`; `ReadMetricsInterceptor` ignores operations routes. 003's read endpoints behave exactly as before.

### Key Entities *(include if feature involves data)*

- **Rerun request (`extraction_rerun`)**: one operator request: the canonical selector and its hash, the reason, the operator's id, when it was made, its counts (`matched`, `alreadyPending`, `unknownShareIds`), and its state (`OPEN`, then `DONE`). The reason and operator id are staff-entered or identifying data.
- **Rerun item (`extraction_rerun_item`)**: one share named by one request: pending or done, its outcome, its operational attempts, when it was last claimed and when it was done. At most one pending item per share.
- **Sweep round (`sweep_round`)**: one row per pod: its last round's times, extractor version and counts, and the last round that worked any row.
- **Receipt view**: the bounded columns of `event_receipt`, never its message text.
- **Daily reconciliation**: counts and findings worked out on demand for one London day; nothing stored.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the rerun integration tests, 100 % of `OK` shares named by a request stay `OK`, keep their `storedSeq`, and are returned by a court search throughout, before, during and after their item is worked.
- **SC-002**: 0 database updates of an `OK` share's key details or `projection_*` columns succeed without a pending item naming it, and 0 succeed that move it to `FAILED`, lower its extractor version, or lower a `true` youth subject (each guard proved by its own named case).
- **SC-003**: With two sweeps running against one queue of 1,000 items, each item is written at most once and every item is done within the rounds its batch sizes allow (no item worked by both).
- **SC-004**: Repeating a request while it is open writes 0 rows and returns the same `rerunId` in 100 % of cases, including two identical requests sent at once (one request results).
- **SC-005**: A request at the maximum size (200,000 matched shares) is written within its transaction timeout in the integration test, and one share over the maximum writes nothing.
- **SC-006**: The status, receipts and reconciliation answers match seeded data exactly in 100 % of the integration cases, including both 2026 clock-change days.
- **SC-007**: 0 operations response bodies, error bodies or log lines in the tests hold a seeded marker from `message_text`, `payload_text`, `payload_json`, a rerun reason or an operator id.
- **SC-008**: Each operations route serves a "Second Line Support" caller and refuses a "System Users" caller (`403`) and a caller with no identity (`401`): 100 % of the route × caller cases pass, also with a spoofed `CPP-ACTION`, `Content-Type` or `Accept`.
- **SC-009**: 0 meter tags in the tests hold an id, a date or a value outside the fixed lists; every operations and rerun meter exists at start with value 0 with the subscription on and off.
- **SC-010**: The service fails to start in 100 % of the start-up tests with any operations or new sweep setting outside its bounds.
- **SC-011**: The container smoke check passes every FR-053 case, with the 001, 002 and 003 cases still passing.
- **SC-012**: The build gate passes: line coverage at least 0.88, branch coverage at least 0.85, PMD clean on main and test.

## Decisions pending Sachin

Each is applied with its default in every 004 document and marked "pending Sachin" where it shows. Changing one changes the named requirement and task only.

| Id | Question | Default applied | Alternatives |
|---|---|---|---|
| D-RERUN-GUARD | How does the database allow an `OK` row to be rewritten? | A: the guard needs a pending rerun item naming the row; worded honestly as "a pending item names the row", not "an operator asked" (FR-029) | B: a transaction-local flag the guard reads (any session can set it, so it proves less). C: drop the `FAILED`-only rule and leave it to the code (no database backstop) |
| D-NEVER-BLANK | May a rerun move a key detail from a value to null? | Allow; the claim is "never moved to `FAILED`" (FR-022), and a youth subject may move from `false` to unknown (FR-021) | Forbid any value-to-null move in the guard (an extractor fix that correctly drops a value then cannot land) |
| D-YOUTH-RAISE | May a rerun move a youth subject in place? | `true` never lowered (guard); `false` to `true` held, listed and counted until a youth-raised feed exists; unknown to `false` or `true` written and stated in 003's contract (FR-021, FR-028). `false` to unknown is written under D-NEVER-BLANK; it makes the day unknown again with no new `storedSeq` | Write `false` to `true` now (a consumer's cursor never sees the day become youth-relevant); add the youth-raised feed in 004; hold `false` to unknown as well |
| D-RERUN-CANCEL | How does a stuck item end? | An attempt limit (3) and outcome `ABANDONED`, counted and shown (FR-025); no cancel endpoint | An operator cancel endpoint, alone or as well (a later spec) |
| D-RERUN-BOUNDS | Request and pacing limits | Range ≤ 31 days; `hearingIds` 1 to 200; `shareIds` 1 to 1,000; matched ≤ 200,000; reason 10 to 500 characters; 200 items per round; chunks of 5,000; 3 attempts; one sweep row per pod; pods shown for 1 day (FR-007 to FR-012, FR-017, FR-025, FR-032) | Other values; all but the reason length are settings |
| D-RECON-CLOCK | Which day does the reconciliation use? | The London day, as `shared_day_london` and support staff do (FR-038) | The UTC day |
| D-R1-WINDOW | When is a receipt still `RECEIVED` a finding? | 1 hour after its last delivery, a setting, until the broker's redelivery give-up time is confirmed with the platform team (FR-039) | The broker's own give-up time once known |
| D-R2 | What does R2 check on demand? | Counts only (FR-040) | Re-extract a sample of the day's payloads and compare (a `GET` would then parse payloads; better in a nightly job) |
| D-NIGHTLY | Build the nightly reconciliation job and the `reconciliation_finding` table now? | Defer to a later spec; the Azure alert "reconciliation does not run" waits for it | Build them in 004 |
| D-SWEEP-ROUND | Shape of the sweep's last-round record | One row per pod, upserted, rows older than a day dropped (FR-032) | One row per round (a history, about 288 rows a day per pod, with no purge until retention exists) |
| D-RERUN-ERASURE | Can the rerun tables be erased? | No delete guard; erasure is deferred to the retention spec. The reason may hold staff-typed text; the operator id identifies a member of staff (FR-030) | Delete-guard them for ever (a later erasure must drop the triggers) |
| D-PRINCIPLE-I-BUMP | Is rewriting an `OK` share in place a MINOR amendment? | Yes: 2.2.0 to 2.3.0, a new freedom stated in Principle I (FR-051) | A PATCH clarification (rejected by the critique: it changes what a stored version means to consumers) |
| D-AUTHZ-REQUIRED | Refuse to start with authorisation off outside the test profile? | Yes (spec 003 FR-050); the rerun endpoint also refuses a missing operator itself (FR-011) | Allow it |
| D-VII-AUDIT-WORDING | Constitution VII audit wording | Spec 003's 2.2.0 wording: *every request that reaches an endpoint is audited; a request refused by a filter or by authorisation is counted* (FR-045) | Keep "every request is audited" and audit refusals another way |
| D-REFUSALS-UNAUDITED | Accept that refused operations requests are counted, not audited | Yes (FR-045) | Audit them with a filter of our own before authorisation |
| D-LAG-VALUE | The visibility lag (spec 003) | 110 s; a stored range must end before it (FR-007) | 91 s |
| D-PG-VERSION / HA | Production PostgreSQL version and table sizes at deploy | Unknown; V6 builds three indexes without `CONCURRENTLY`, which is fine before volume builds up (plan.md *Risks*) | Build them `CONCURRENTLY` outside Flyway's transaction |

## Assumptions

Settled choices from the rulings, spec 003 and the design, stated so they are visible:

- Spec 003 lands first. 004 reuses its action filter, route table, `415` guard, bounded error body and `/error` page, `ProblemReason`, `BadParameterException`, the instant format, `OpenApiContractTest`, the Testcontainers and WireMock test pattern, and its effective visibility lag. Its names are used as 003's documents give them.
- Action names are kebab verb-noun with the `results-store-operations.` prefix; a known path with the wrong method gives `405` (D-METHOD, settled in 003).
- Reason codes follow 003's style (`unknown_parameter`, `invalid_hearing_id`), not the design review's draft codes.
- A rerun is a request the sweep works, never a write to shares at request time (ruling C1). Only the sweep writes key details, as Principle I already requires.
- Repeats are recognised by the canonical selector while a request is open; no idempotency key header is needed.
- The receipts lookup with no match is an empty list, not `404`: it is a query, not a resource.
- The status lists the 20 most recent requests whatever their state, so a finished request's outcomes stay visible.
- The operator id is stored as a UUID, as every `CJSCPPUID` on the estate is one.
- Operations response bodies hold ids, counts, times and bounded codes only, so spec 003's D-AUDIT override (payload routes only) does not touch them.
- Every query is a fixed constant with bound parameters; nothing is built from input.
- V6 uses plain `CREATE INDEX` because it deploys before go-live.
