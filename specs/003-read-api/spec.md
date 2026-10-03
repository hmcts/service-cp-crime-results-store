# Feature Specification: Read API

**Feature Branch**: `003-read-api`
**Created**: 2026-10-03
**Status**: Draft
**Input**: User description: "Read API: the internal REST API under /results-store/v1 that consumers use to pull, search and fetch stored shares and their payloads"

**Sources**: the Results Store design page (CRA 321061800), sections *Read API*, *How the store makes pull safe*, *Security* and *Observability*; the design review of spec 003 and its critique, with the orchestrator's rulings on both (2026-10-03, sections A, B and D), which win where they differ from the design; the fact-finding reports on the store's read side (this repository at `c21a901`) and on YOT as the first consumer (YOT's payload port, its retry policy, its redesign's needs) with probation's asks S6 to S12 and gate G2; specs 001 (*Share intake*) and 002 (*Enrichment*), in particular 002 FR-040 and FR-041. Quotes in *italics* are the design page's or the rulings' wording.

### Scope

**In scope.** Five read endpoints under `/results-store/v1`: pull, search, one share, one share's payload, and every version of one hearing day. The visibility lag that makes pull safe, with the database trigger it relies on and a counter that shows when its assumption broke. The action filter rewritten so the action always comes from method and path, unknown paths refused, and vendor media types neutralised. One allow rule per action. Bounded problem bodies for every refusal and error. The read indexes (migration V5). Metrics. The consumer contract (`contracts/read-api.md`) that the YOT and probation teams are pointed at for gate G2. An end-to-end check through the compose stack. Documentation changes, performed as the last task: constitution 2.2.0, design rules, spec 001 pointers, forward notes for the page owner and the consumer teams.

**In scope only if Sachin accepts D-RAW.** A sixth endpoint that serves the text as it arrived (phase D, a separate task that can be dropped without touching the rest).

**Out of scope.**

- The operations API (`/operations/**`, re-extraction, receipts, reconciliation): spec 004. 003 leaves the route table, the filter, the rule file and the OpenAPI document ready for 004 to add to.
- Push notifications to consumers (outbox, Service Bus). Pull is the only feed in 003.
- Retention and purge. `expires_at` stays empty; retention is still open on the design page.
- Views by defendant, courtroom or prosecutor, and the `share_defendant(defendant_id)` index. No 003 endpoint reads them.
- Youth scoping of reads. Constitution VII has no youth scoping; the design appendix's youth-scoped action stays superseded.
- Any consumer's own client code (YOT, probation). 003 publishes the contract they build to.
- Deployment values in `cpp-aks-deploy` (the audit transport, the API gateway route). A separate task.

### Why

*One event in, many readers out.* The store keeps every share. Consumers such as the court register, youth offending team distribution and probation need a way to read it that never misses a share, never shows a half-written one, and never lets a caller pick its own permissions. Today the store has no web layer at all: no controllers, no routes in the OpenAPI document, no allow rules.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A consumer pulls every share stored since its cursor, safely (Priority: P1)

A consumer such as YOT's nightly job or probation's bridge keeps a cursor, `storedAfterSeq`. Each call returns the next shares in stored order, with key details only. The store never returns a share while a share with a lower sequence number could still appear later, so a consumer that only moves its cursor forward never misses one.

**Why this priority**: This is the feed. Every consumer's completeness depends on it.

**Independent Test**: Store shares through intake. Hold one store transaction open at a lower sequence number while a later one commits. Pull: the later share is withheld until the earlier one ends. Pull again with the returned `nextStoredAfterSeq` until `hasMore` is false: every share appears once, in `storedSeq` order.

**Acceptance Scenarios**:

1. **Given** shares stored more than the visibility lag ago, **When** a consumer pulls with `storedAfterSeq=0`, **Then** they come back in ascending `storedSeq`, at most `limit` of them, with `hasMore` true exactly when more remain.
2. **Given** a share stored less than the visibility lag ago, **When** a consumer pulls, **Then** it is not returned, and neither is any share stored after it.
3. **Given** a filter (`dayYouthSeen`, `courtCentreId`) that matches nothing in a range, **When** the page is not full, **Then** `nextStoredAfterSeq` still moves to the highest sequence number the store can vouch for, so the next call does not rescan the range.
4. **Given** any pull, **Then** the response carries `visibleUpTo`, and every share stored at or before it with `storedSeq` up to `nextStoredAfterSeq` has been presented (YOT's 18:00 barrier).
5. **Given** a share presented with `projectionStatus` `FAILED` or `dayYouthSeen` null, **When** the sweep later fills its key details, **Then** the share is not presented again by pull, and the contract tells the consumer to re-read it with `GET /shares/{shareId}`.

---

### User Story 2 - A consumer fetches a share's payload and can prove it is intact (Priority: P2)

A consumer fetches `GET /shares/{shareId}/payload`. It gets the working copy (002 FR-041), with an `ETag` that is the SHA-256 of exactly the bytes it received, and the share's identity and `enrichmentApplied` in headers. A repeat fetch with `If-None-Match` gets a `304`.

**Why this priority**: Every consumer builds its output from the payload. Probation checks the digest on every response (DV-19).

**Independent Test**: Store an enriched share. Fetch its payload. Check the SHA-256 of the body equals the unquoted `ETag`, the `_metadata` block is present, and the identity and enrichment headers match the share. Fetch again with that `ETag` in `If-None-Match`: `304`, no body.

**Acceptance Scenarios**:

1. **Given** a share whose working copy is held, **When** its payload is fetched, **Then** the body is `payload_json` as text, `Results-Store-Payload-Form` is `working-copy`, and the `ETag` is the quoted lower-case SHA-256 hex of the body bytes.
2. **Given** a share whose working copy is empty, **Then** the body is `payload_text` and `Results-Store-Payload-Form` is `arrived-text`.
3. **Given** `If-None-Match` naming the current `ETag` (alone, in a list, weak, or `*`), **Then** the answer is `304` with the `ETag` and no body.
4. **Given** an unknown share id, **Then** `404` with reason `share_not_found`.
5. **Given** any payload response, **Then** it carries `Cache-Control: no-store`, no `Content-Encoding`, and a `Content-Length` equal to the body's byte count.

---

### User Story 3 - A consumer reads one share and every version of its day (Priority: P3)

A consumer that receives a share reads its current key details with `GET /shares/{shareId}`, and all versions of its hearing day, in `sharedTime` order, with `GET /hearings/{hearingId}/days/{hearingDay}/shares`, to find the latest.

**Why this priority**: Each share is a full snapshot of the day, and a share that arrives late may not be the latest. YOT skips non-latest shares; it needs the day's chain to decide.

**Independent Test**: Store three shares of one day, the second arriving last. Read the day: three items in `sharedTime` order, `versionNumber` 1 to 3, one `isLatest`. Read the late share alone: `arrivedOutOfOrder` true, `isLatest` false.

**Acceptance Scenarios**:

1. **Given** a stored share, **When** it is read by id, **Then** every field of the item is present, with null written as null.
2. **Given** a share stored with an earlier `sharedTime` than one already stored, **Then** `versionNumber` of the later shares goes up by one; `shareId` is the key, never `versionNumber`.
3. **Given** a hearing day with no shares, **Then** `404` with reason `hearing_day_not_found`.
4. **Given** a share whose extraction `FAILED`, **Then** `keyDetails` is null and `projectionStatus` is `FAILED`.

---

### User Story 4 - A consumer searches by court and register day (Priority: P4)

A consumer asks for the shares of one court centre between two London calendar days, optionally only the latest of each day and only youth-relevant days, and pages through them with an opaque cursor.

**Why this priority**: YOT's 18:00 cross-check and ad hoc support questions need it. It is a query, not a feed.

**Independent Test**: Store shares for two courts on three days, one at 00:30 BST. Search one court over two days with `latestOnly=true`: only that court's latest shares of those London days, ordered by day, time and id; page with `limit=1` and the cursor without repeats or gaps.

**Acceptance Scenarios**:

1. **Given** `courtCentreId`, `sharedDayFrom` and `sharedDayTo`, **Then** shares whose London shared day falls in the range, both ends included, are returned.
2. **Given** a range longer than 31 days or reversed, **Then** `400` with `day_range_too_long` or `day_range_reversed`.
3. **Given** a page with more rows after it, **Then** `nextCursor` is set; on the last page it is null.
4. **Given** a cursor that has been altered, cut short or is longer than 128 characters, **Then** `400` with `invalid_cursor`.

---

### User Story 5 - Only admitted callers get in, and nobody can choose their own action (Priority: P5)

Every request under the service has its action worked out from its method and path. A caller cannot change it with a `CPP-ACTION` header or a vendor media type in `Content-Type` or `Accept`. A path the service does not serve is refused before authorisation. A caller with no identity gets `401`; a caller in neither admitted group gets `403`.

**Why this priority**: *The store holds every defendant's results, including children's. An endpoint someone forgot to protect must fail closed.* The authorisation library picks the action from a vendor media type first, so the header override alone does not close the gap.

**Independent Test**: With authorisation on and a usersgroups stub: each endpoint serves a "System Users" caller and a "Second Line Support" caller, refuses a caller in neither group with `403`, and refuses a caller with no identity with `401`. A spoofed `CPP-ACTION`, vendor `Content-Type` or vendor `Accept` changes no outcome. `/results-store/v1/anything` gives `404 route_not_found`; `HEAD` and `OPTIONS` on a real route give `405` with `Allow`.

**Acceptance Scenarios**:

1. **Given** a request to a mapped route, **Then** the action the library sees is the route's action whatever the caller sent.
2. **Given** a path under the service that is not a route, **Then** `404 route_not_found`, before authentication, counted.
3. **Given** a known path with another method (including `HEAD` and `OPTIONS`), **Then** `405 method_not_allowed` with an `Allow` header, counted.
4. **Given** a `multipart/*` request on a route, **Then** `415 unsupported_content_type`, counted.
5. **Given** a rule that names the action but a request whose method or path does not match the route, **Then** it is refused.

---

### User Story 6 - Errors are bounded and fit consumers' retry policies (Priority: P6)

Every refusal and error carries the same small body: `type`, `title`, `status` and a `reason` from a fixed list. It never holds a caller's value, an exception message, a request path or a fragment of a payload. A database that cannot answer gives `503 store_unavailable` with `Retry-After`, which YOT's retry policy honours.

**Why this priority**: Consumers branch on status and reason. Principle XI forbids personal data and exception text in anything the service emits.

**Independent Test**: Send each bad parameter, each Spring MVC exception type and a `401`/`403` (also with `Accept: text/html`). Check each body has exactly the four fields and no echoed value. Stop the database: `503 store_unavailable` with `Retry-After`.

**Acceptance Scenarios**:

1. **Given** any `4xx` or `5xx` from this service, **Then** the body holds only `type`, `title`, `status` and `reason`.
2. **Given** an unknown or repeated query parameter, **Then** `400` with `unknown_parameter` or `repeated_parameter`.
3. **Given** a connection failure or a query timeout, **Then** `503 store_unavailable` with a `Retry-After` in seconds.
4. **Given** any other failure, **Then** `500 internal_error`, logged by exception class only.

---

### User Story 7 - Operators see read traffic, refusals and a broken pull assumption (Priority: P7)

Support staff see how many reads each endpoint served and with what outcome, how long they took, how many requests were refused before authorisation and why, how big pages and payloads are, and whether any store transaction ran longer than the visibility lag.

**Why this priority**: Refusals before authorisation are not audited, so a counter is their only record. The overrun counter turns the pull-safety assumption into an alert.

**Independent Test**: Run the scenarios of stories 1 to 6 and read `/actuator/prometheus`. Check each counter moved by the expected amount and every tag value is from a fixed list. Make a store transaction outlast a small lag: the overrun counter moves once.

**Acceptance Scenarios**:

1. **Given** a pull that returns three items, **Then** `resultsstore.read.requests{endpoint=pull,outcome=ok}` goes up by one and `resultsstore.read.page.items` records 3.
2. **Given** a `404 route_not_found`, **Then** `resultsstore.read.refused{reason=route_not_found}` goes up by one.
3. **Given** a store transaction whose time from sending the share insert to its commit returning is at or above the lag, **Then** `resultsstore.intake.visibility.overrun` goes up by one.

---

### User Story 8 - A consumer fetches the text exactly as it arrived (Priority: P8, phase D, only if D-RAW is accepted)

Probation (S10) builds today's EXT view from the raw event. It fetches `GET /shares/{shareId}/payload/arrived` and gets `payload_text` exactly as hearing sent it, with `ETag` equal to the stored checksum.

**Why this priority**: It serves one consumer's ask and needs a constitution wording change. It is a separate phase so it can be dropped.

**Independent Test**: Store an enriched share. Fetch the arrived text: the body is byte-identical to the published message, `ETag` is `payload_sha256` quoted, `Results-Store-Payload-Form` is `arrived-text`.

**Acceptance Scenarios**:

1. **Given** a stored share, **Then** the body is `payload_text` and the SHA-256 of the body equals `payload_sha256`.
2. **Given** a caller in neither admitted group, **Then** `403`; the endpoint has its own action and rule.

### Edge Cases

- A share stored at 00:30 BST belongs to the previous UTC day but the current London day: search filters on the London day; both days are in the item.
- Two shares of one day with the same `sharedTime` cannot exist (unique key). Search's keyset still breaks ties on `shareId` across days and courts.
- `storedSeq` has gaps (rolled-back transactions and duplicates take numbers). Gaps mean nothing.
- A share arrives with an earlier `sharedTime` than the day's latest: it is presented by pull when stored (higher `storedSeq`), with `isLatest` false and `arrivedOutOfOrder` true; the earlier presented shares' `versionNumber` values move.
- A share whose day later becomes youth-relevant through a new share: the new share is presented (higher `storedSeq`); the old one is not re-presented.
- A day flag that moves from unknown to `true` or `false` through the sweep, or through a spec-004 rerun: no new `storedSeq`; the consumer learns it only by re-reading (FR-031).
- `limit=0`, `limit=501`, `limit=abc`: `400 limit_out_of_range`.
- `storedAfterseq` (a typo): `400 unknown_parameter`, so a pull never silently becomes a search.
- A trailing slash, a `;param`, a double slash or upper-case letters in the path: matched exactly as Spring MVC routes it, so the filter and the controller never disagree.
- A payload of 2.4 MB: served whole; the response is buffered (the audit filter copies it), so memory is about three times the body.
- The database is upgraded to a new PostgreSQL major version: the same content may come back with other bytes and another `ETag` (FR-040).

## Requirements *(mandatory)*

### Functional Requirements

**Routes and common rules**

- **FR-001**: The service MUST serve exactly these routes under `/results-store/v1`, all `GET`: `/shares` (pull or search), `/shares/{shareId}`, `/shares/{shareId}/payload`, `/hearings/{hearingId}/days/{hearingDay}/shares`; and, only if D-RAW is accepted, `/shares/{shareId}/payload/arrived`.
- **FR-002**: Query parameters MUST be case-sensitive. An unknown parameter MUST give `400 unknown_parameter`; a repeated one `400 repeated_parameter`.
- **FR-003**: Ids MUST be canonical UUIDs (8-4-4-4-12) and dates ISO `yyyy-MM-dd`; anything else MUST give `400` with the parameter's own reason.
- **FR-004**: Every field of every response item MUST always be present; a missing value MUST be written as JSON `null`, never omitted.
- **FR-005**: Every time MUST be an ISO-8601 UTC instant with exactly six fraction digits (for example `2026-10-03T09:15:00.120000Z`).
- **FR-006**: The share item MUST hold: `shareId`, `hearingId`, `hearingDay`, `sharedTime`, `storedSeq`, `storedAt`, `sharedDayLondon`, `sharedDayUtc`, `keyDetails` (`courtCentreId`, `courtRoomId`, `ljaCode`, `jurisdictionType`, `isSjp`, `isGroupProceedings`, `youthCourtId`, `isReshare`; the whole object null exactly when `projectionStatus` is `FAILED`), `anySubjectIsYouth`, `dayYouthSeen`, `isLatest`, `predecessorShareId`, `arrivedOutOfOrder`, `enrichmentApplied`, `projectionStatus`, `projectionVersion`, `projectedAt`, `versionNumber`. `sharedTime` MUST be the stored `shared_at`. There MUST be no `eventType` field: `keyDetails.isSjp` is the source (true = SJP, false = INT, null = unknown while `FAILED`).
- **FR-007**: `versionNumber` MUST be the share's 1-based position within its hearing day by `sharedTime`, computed when read and never stored.
- **FR-008**: The mutable values (`isLatest`, `predecessorShareId`, `dayYouthSeen`, `keyDetails`, `anySubjectIsYouth`, `projectionStatus`, `projectionVersion`, `projectedAt`, `versionNumber`) MUST be the values at the time of the read.

**Pull**

- **FR-009**: `GET /shares` with `storedAfterSeq` (an integer ≥ 0) MUST be a pull. It MAY add `limit`, `dayYouthSeen` (`notFalse` or `true`) and `courtCentreId`. `sharedDayFrom`, `sharedDayTo`, `latestOnly` or `cursor` with it MUST give `400 conflicting_parameters`.
- **FR-010**: Pull MUST return shares with `storedSeq` greater than `storedAfterSeq`, in ascending `storedSeq`, at most `limit` (default 100, 1 to 500, else `400 limit_out_of_range`), with no payload.
- **FR-011**: `dayYouthSeen=notFalse` MUST select days whose flag is not `false` (unknown stays visible); `true` MUST select days whose flag is `true`; absent MUST mean any day. Any other value MUST give `400 invalid_day_youth_seen`.
- **FR-012**: `courtCentreId` on pull MUST select shares of that court centre AND shares whose extraction is `FAILED` (court unknown) (D-COURT-FAILED, pending Sachin).
- **FR-013**: The pull response MUST be `{ items, nextStoredAfterSeq, hasMore, visibleUpTo }`. `hasMore` MUST be exact (the store reads one row more than `limit`).
- **FR-014**: When `hasMore` is true, `nextStoredAfterSeq` MUST be the last item's `storedSeq`. When it is false, `nextStoredAfterSeq` MUST be the greater of the request's `storedAfterSeq` and the highest `storedSeq` of every visible share, filter or no filter, worked out in the same database statement as the page.
- **FR-015**: `visibleUpTo` MUST be the database's current time minus the visibility lag, from the same statement. The contract MUST state: every share stored at or before `visibleUpTo` with `storedSeq` at or below `nextStoredAfterSeq` has been presented (if it matched the filters at the time of the read).

**Pull safety**

- **FR-016**: Pull MUST return only shares whose `stored_at` is at or before the database's current time minus the visibility lag. Both times MUST come from the database clock.
- **FR-017**: The default visibility lag MUST be the store transaction timeout plus twice the statement timeout plus the idle-in-transaction timeout: 110 seconds at the intake defaults (D-LAG-VALUE, pending Sachin). The bound MUST rest on limits PostgreSQL enforces, not on the JDBC driver's client-side cancel.
- **FR-018**: The service MUST refuse to start when the lag is below that sum or above 10 minutes. When the lag is not set and the derived default is above 10 minutes, the error MUST name `resultsstore.intake.store.transaction-timeout`.
- **FR-019**: A database trigger MUST set `stored_at` from the clock when each share row is inserted, after its `stored_seq` is assigned, so a share's `stored_at` is never earlier than the moment its sequence number was taken.
- **FR-020**: Intake MUST count `resultsstore.intake.visibility.overrun` when a store transaction's time from sending the share insert to its commit returning is at or above the lag (D-OVERRUN, pending Sachin; it changes spec 001 code).
- **FR-021**: The configuration contract MUST document that the lag is checked against this pod's own intake settings only, that every pod must share `resultsstore.intake.store.*`, and the rollout order: raise the lag before raising any intake timeout; lower the timeouts before lowering the lag.

**Read-time semantics (normative contract text)**

- **FR-022**: The contract MUST state that filters are evaluated at read time; that a share behind the cursor is never presented again; that its day's later share, which has a higher `storedSeq`, is presented when it is stored; and that each share is a full snapshot of its day.
- **FR-023**: The contract MUST state the unknown-row obligation: *a share presented with `projectionStatus` `FAILED` or `dayYouthSeen` null is not final in its key details; re-read `GET /shares/{shareId}` until `projectionStatus` is `OK` (or the day's successor arrives) before deciding it is not yours.*
- **FR-024**: The contract MUST state that `dayYouthSeen=notFalse` is the complete feed for youth-relevant days, and that `dayYouthSeen=true` can miss a share whose day became `true` with no new share (kept, D-PULL-TRUE).
- **FR-025**: The contract MUST state that `isLatest` can be false for a share that arrived out of order and that the consumer reads the day's versions to find the latest; that `versionNumber` can change; that `keyDetails`, `dayYouthSeen` and `anySubjectIsYouth` can be rewritten in place, by the sweep or by a spec-004 rerun, with no new `storedSeq`, and that `projectionVersion` and `projectedAt` show when; that re-pulling from an older cursor is safe and is the way to reconcile; and that consumers keep their own idempotency guard on `shareId`.

**Search**

- **FR-026**: `GET /shares` without `storedAfterSeq` MUST be a search. `courtCentreId`, `sharedDayFrom` and `sharedDayTo` MUST be present (`400 missing_parameter`). It MAY add `dayYouthSeen` (`notFalse`, `true` or `false`), `latestOnly` (`true` or `false`, default false), `limit` (default 100, 1 to 500) and `cursor`.
- **FR-027**: Search MUST filter on the London shared day, both ends included, over at most 31 days (`400 day_range_too_long`; `400 day_range_reversed` when from is after to).
- **FR-028**: Search MUST order by London shared day, `sharedTime`, then `shareId`, ascending, and page by keyset on those three values. The response MUST be `{ items, nextCursor }`, `nextCursor` null on the last page.
- **FR-029**: The cursor MUST be opaque base64url text of at most 128 characters, decoded strictly; anything that does not decode to a valid position MUST give `400 invalid_cursor`.
- **FR-030**: No visibility lag MUST apply to search, one share or the day's versions. The contract MUST say search is a query, not a feed: shares stored while paging and `FAILED` shares are not guaranteed to appear.

**One share and the day's versions**

- **FR-031**: `GET /shares/{shareId}` MUST return the item, or `404 share_not_found`.
- **FR-032**: `GET /hearings/{hearingId}/days/{hearingDay}/shares` MUST return `{ items }` in `sharedTime` order, unpaged, or `404 hearing_day_not_found` when the day has no share.

**Payload**

- **FR-033**: `GET /shares/{shareId}/payload` MUST return the exact UTF-8 bytes of the working copy as the database writes it as text, or of `payload_text` when the working copy is empty (002 FR-041), as `application/json`, with `_metadata` kept.
- **FR-034**: The `ETag` MUST be strong and quoted: the lower-case SHA-256 hex of exactly the bytes in the body. `payload_sha256` MUST never be offered as this endpoint's `ETag`.
- **FR-035**: The response MUST carry `Results-Store-Share-Id`, `Results-Store-Hearing-Id`, `Results-Store-Hearing-Day`, `Results-Store-Shared-Time`, `Results-Store-Enrichment-Applied` (`true` or `false`), `Results-Store-Payload-Form` (`working-copy` or `arrived-text`) and `Cache-Control: no-store`.
- **FR-036**: `If-None-Match` matching the current `ETag` (weak comparison; a list or `*` accepted) MUST give `304` with exactly one `ETag` header and no body.
- **FR-037**: The response MUST have no `Content-Encoding`, a `Content-Length` equal to the body's byte count and no chunked transfer encoding, also when the audit filter is on.
- **FR-038**: The payload query MUST be the only read query that touches `hearing_share_payload` (Principle III).
- **FR-039**: The sharer's user id is not a separate field: consumers read `_metadata.context.user` from the body, which is kept (D-S12, pending Sachin).
- **FR-040**: The contract MUST promise that a share's body and `ETag` are stable while the database's PostgreSQL major version is unchanged, that an upgrade may change the bytes and the `ETag` but never the content, and that a consumer verifies each response against its own `ETag` (D-JSONB-PROMISE, pending Sachin).

**Arrived text (phase D, only if D-RAW is accepted)**

- **FR-041**: `GET /shares/{shareId}/payload/arrived` MUST return `payload_text` exactly as stored, with its own action and allow rule, `ETag` the quoted `payload_sha256`, which MUST equal the SHA-256 of the served bytes, and the same headers as FR-035 with `Results-Store-Payload-Form: arrived-text`.

**Errors**

- **FR-042**: Every `4xx` and `5xx` body MUST be `{"type":"about:blank","title":<the HTTP reason phrase>,"status":<n>,"reason":<code>}` and nothing else, with `reason` from the fixed list in `contracts/read-api.md`. No body MUST ever hold a caller's value, a request path, an exception message or payload content.
- **FR-043**: Spring MVC's own exceptions MUST be rendered through the same four fields; `spring.mvc.problemdetails.enabled` MUST stay false; the `/error` page MUST use bounded attributes, also for `Accept: text/html`, with the white-label page off.
- **FR-044**: A connection failure or a query timeout MUST give `503 store_unavailable` with `Retry-After` in delta-seconds. Any other failure MUST give `500 internal_error`, logged by exception class with `shareId` in the logging context when known, never the message.
- **FR-045**: Each read query MUST have a statement timeout (default 5 seconds) below the driver's socket timeout.

**Authorisation**

- **FR-046**: The action MUST be derived from method and path for every request under the service. Pull and search MUST be separate actions, told apart by the presence of `storedAfterSeq`, looked up only after the method and path match. Actions: `results-store.pull-shares`, `results-store.search-shares`, `results-store.get-share`, `results-store.get-share-payload`, `results-store.list-hearing-day-shares`, and (D-RAW) `results-store.get-share-arrived-payload`.
- **FR-047**: On a mapped route the caller's `CPP-ACTION` MUST be overwritten, and `Content-Type` and `Accept` MUST answer `application/json` wherever they name a vendor media type.
- **FR-048**: A path under the service that matches no route MUST give `404 route_not_found` before authorisation. A known path with another method, `HEAD` and `OPTIONS` included, MUST give `405 method_not_allowed` with `Allow`. `/actuator/**` and `/error` MUST pass with `CPP-ACTION` removed and their media types untouched. `multipart/*` on a route MUST give `415 unsupported_content_type` before the audit filter.
- **FR-049**: Each action MUST have one allow rule admitting "System Users" and "Second Line Support", which also matches the request's method and path, so a spoofed action name alone never passes. No rule admits everything; `deny-when-no-rules` stays true.
- **FR-050**: The service MUST refuse to start with `authz.http.enabled` false unless the `test` profile is active (D-AUTHZ-REQUIRED, pending Sachin).

**Audit**

- **FR-051**: Every request that reaches an endpoint MUST be audited by `cp-audit-filter-springboot`. Refusals before authorisation (`404`, `405`, `415` from this service's filters; `401` and `403` from the authorisation library) are not audited; they MUST be counted (D-REFUSALS-UNAUDITED, D-VII-AUDIT-WORDING, pending Sachin).
- **FR-052**: The payload endpoints' audit response event MUST carry the fixed marker `{"payloadOmitted":true}` in place of the body (D-AUDIT option 4, the default, pending Sachin). If Sachin chooses option 1 instead, the event carries the body and the DPIA records it. Either way a test MUST pin the behaviour, and list pages are not replaced.
- **FR-053**: Every path template MUST be in `results-store-openapi.yaml` with its path parameters declared, and every described route MUST be served (checked both ways by a test).

**Schema**

- **FR-054**: Migration V5 MUST add the stored-at trigger (FR-019), a partial index on `stored_seq` for days whose flag is not `false`, and a partial index on court centre, London shared day, `shared_at` and `share_id` for rows with a court. V1 to V4 MUST NOT be edited. No table or column is added.

**Metrics**

- **FR-055**: The service MUST publish `resultsstore.read.requests{endpoint,outcome}`, `resultsstore.read.refused{reason}`, `resultsstore.read.duration{endpoint}`, `resultsstore.read.page.items`, `resultsstore.read.payload.bytes` and `resultsstore.intake.visibility.overrun`, with every tag value from a fixed list, registered at start.

**Configuration**

- **FR-056**: The read settings MUST be typed and checked at start: `resultsstore.read.pull.visibility-lag` (FR-017, FR-018) and `resultsstore.read.statement-timeout` (FR-045). The read beans MUST be wired whatever `resultsstore.publicevents.enabled` says.

**Documentation (performed by the last task, not now)**

- **FR-057**: Constitution 2.1.0 MUST become 2.2.0 (MINOR) with Principle VII reworded: the action derived from method and path for every request; caller `CPP-ACTION` and vendor media types overridden; an unmapped path refused; read rules admitting "System Users" and "Second Line Support"; *every request that reaches an endpoint is audited; refusals before authorisation are counted*; and, under option 4, the payload endpoints' response body replaced by a fixed marker. If D-RAW is accepted, Principle II gains the arrived-text endpoint.
- **FR-058**: The design rules file (`design_rules.md`, the only file of that name in the repository) MUST replace the "lowest open write" pull-safety sentence with the visibility lag, and its security bullets MUST match FR-049 and FR-051. Spec 001's forward references to "indexes in 003" MUST point at V5.
- **FR-059**: Forward notes for the design page owner and for the YOT and probation teams MUST be written in `page-notes.md`; the page itself is not edited.

**End to end**

- **FR-060**: The container smoke check MUST call the API over HTTP after a share is stored: pull lists it after the lag; one share `200`; payload `200` with the SHA-256 of the body equal to the `ETag`; `If-None-Match` `304`; day versions `200`; no identity `401`; a caller in neither group `403`; an unmapped path `404 route_not_found` with no path echoed; a vendor `Accept` on pull still `200`; and the read meters present.

### Changes to spec 001 and spec 002

003 amends or touches these parts. Spec 001 is updated at the end of 003 (FR-058); until then this section is the record.

- **001 forward references** (`spec.md` *Out of scope*, *Assumptions* "Consumer search indexes are left to spec 003"; `data-model.md` "spec 003 adds them with the read API"): delivered as V5's two indexes. The defendant-id index is still not built.
- **001 `stored_at`**: still `DEFAULT clock_timestamp()`, but the value is now set by V5's `BEFORE INSERT` trigger, after the identity value is taken. Its meaning (when the store received the share) is unchanged.
- **001 intake code** (D-OVERRUN): `IntakeObserver` gains one method; `StoreResult.Stored` gains the insert-to-commit time; `IntakeService` compares it with the lag. Intake behaviour is otherwise unchanged.
- **001 `contracts/metrics.md` "Not in 001"**: points at 003's metrics delta for the read meters and the overrun counter.
- **001 `research.md`** ("A pull cursor must use `stored_seq`, never `xmin`"; "The read side will filter `IS NOT FALSE`"): honoured as written.
- **002 FR-040 and FR-041**, `contracts/schema.md` rule 5 and `page-notes.md` §3: honoured as written. 003 serves `payload_json` as text, or `payload_text` when it is empty, with the `ETag` over the bytes served.

### Key Entities *(include if feature involves data)*

- **Share item (read view of `hearing_share`)**: the fields of FR-006. Ids, flags, times and key details only; no payload. It still tells a reader which hearings concern a youth, so access is controlled and audited.
- **Pull page**: items, `nextStoredAfterSeq`, `hasMore`, `visibleUpTo`.
- **Search page**: items, `nextCursor`.
- **Served payload**: the bytes, their `ETag`, the share's identity, `enrichmentApplied` and the payload form. Special-category and youth personal data.
- **Visibility lag**: a duration, the same on every pod; the pull withholds every share stored more recently than that.
- **No new table or column.** V5 adds one trigger and two indexes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the two-connection race test, 0 shares are returned past a lower sequence number whose transaction is still open, with the lag above the open transaction's age; with lag 0 the race is shown, so the test can fail.
- **SC-002**: Paging a pull from 0 until `hasMore` is false returns every stored share exactly once, in ascending `storedSeq`, in 100 % of the integration runs, including with filters that match nothing in a range (the cursor still advances).
- **SC-003**: For 100 % of payload responses in the tests, the SHA-256 of the body equals the unquoted `ETag`, and exactly one `ETag` header is sent on `200` and `304`.
- **SC-004**: Each endpoint serves "System Users" and "Second Line Support" callers, and refuses a caller in neither group (`403`) and one with no identity (`401`): 100 % of the route × caller cases pass.
- **SC-005**: 0 of the spoofing cases (route × `CPP-ACTION`, vendor `Content-Type`, vendor `Accept`, `Accept` list) change an outcome.
- **SC-006**: 0 error bodies in the tests hold a field other than `type`, `title`, `status` and `reason`, or a caller value, path, exception text or payload marker.
- **SC-007**: The V5 plan tests show each pull and search variant served by its intended index, the search with no sort step, and no pull, search, share or day query touching `hearing_share_payload`.
- **SC-008**: The service fails to start in 100 % of the start-up tests with a lag below the derived bound, a lag above 10 minutes, a read statement timeout at or above the socket timeout, or authorisation off outside the test profile.
- **SC-009**: The overrun counter moves exactly once for a store transaction made to outlast a 1-second test lag, and 0 times for one inside it.
- **SC-010**: 0 meter tags in the tests hold an id, a date or a value outside the fixed lists; every read meter exists at start with value 0.
- **SC-011**: The audit test pins the chosen D-AUDIT behaviour: under option 4 the payload response event holds `{"payloadOmitted":true}` and no body byte; a `304` publishes no response event; a `415` publishes nothing.
- **SC-012**: The container smoke check passes every FR-060 case, with the 001 and 002 cases still passing.
- **SC-013**: The build gate passes: line coverage at least 0.88, branch coverage at least 0.85, PMD clean on main and test.
- **SC-014** (phase D only): the arrived-text body is byte-identical to the published message in 100 % of the cases, and its `ETag` equals `payload_sha256`.

## Decisions pending Sachin

Each is applied with its default in every 003 document and marked "pending Sachin" where it shows. Changing one changes the named requirement and task only.

| Id | Question | Default applied | Alternatives |
|---|---|---|---|
| D-AUDIT | The audit library 1.0.5 copies whole response bodies into audit events and has no switch. What happens to payload bodies? | Option 4 now, option 2 in parallel, recorded in the DPIA: replace the library's `AuditPayloadGenerationService` bean so the payload endpoints' response event holds `{"payloadOmitted":true}`; list pages keep their bodies (FR-052, T011) | 1: accept and record in the DPIA (T011's alternative branch). 2: ask the library owners for an exclusion switch (needs an interim). 3: replace the `AuditFilter` bean and publish a body-free event ourselves. 5: serve from a path the filter skips (rejected: unaudited) |
| D-RAW | Offer the arrived text on its own endpoint (probation S10)? | Yes, as separable phase D (T013), own action and rule, `ETag` = `payload_sha256`; constitution II wording in 2.2.0 | A `?variant=arrived` query on `/payload` (one action; the filter would read the query); or no, and offer a list of enriched application ids |
| D-LAG-VALUE | Lag bound | 110 s: transaction + 2 × statement + idle-in-transaction, all enforced by PostgreSQL (FR-017) | 91 s, relying on the JDBC client cancel arriving within 1 s |
| D-OVERRUN | Add the intake-side overrun counter, touching spec 001 code? | Yes (FR-020, T008) | No counter; the lag assumption stays unmonitored |
| D-COURT-FAILED | Should a court-filtered pull also return `FAILED` shares (court unknown)? | Yes, with the unknown-row obligation (FR-012, FR-023) | No: a court-filtered consumer can then miss a share the sweep fixes later |
| D-JSONB-PROMISE | What the contract promises about payload bytes across database upgrades | Stable within a PostgreSQL major version; verify each response against its own `ETag` (FR-040); probation DV-19 to be told | Promise byte stability for ever (would need a stored served copy, a later migration) |
| D-S12 | Probation's user-id flag | No flag: the consumer reads `_metadata.context.user` from the payload body (FR-039) | A header or field beside `enrichmentApplied` |
| D-READONLY-PODS | Will any deployment run read-only pods, or pods with differing intake timeouts? | No; rollout order documented (FR-021) | Writers publish their effective bound in a row that readers check |
| D-PG-VERSION / HA | Production PostgreSQL version and synchronous replication | Unknown; not blocking. Noted in research R4 | PostgreSQL 17 or later: add `transaction_timeout` and use it in the bound |
| D-AUTHZ-REQUIRED | Refuse to start with authorisation off outside the test profile? | Yes (FR-050) | Allow it (an environment could then serve unprotected) |
| D-VII-AUDIT-WORDING | Constitution VII audit wording | *Every request that reaches an endpoint is audited; refusals before authorisation are counted* (FR-051, FR-057) | Keep "every request is audited" and audit refusals some other way |
| D-REFUSALS-UNAUDITED | Accept that refusals before authorisation are counted, not audited | Yes (FR-051) | Audit them (needs a filter of our own before authorisation) |
| D-YOUTH-RAISE (spec 004) | 004's rerun: `FALSE`→`TRUE` held; `NULL`→`FALSE`/`TRUE` written | 003's contract states that a rerun can rewrite `dayYouthSeen` and key details in place with no new `storedSeq` (FR-025) | Decided in 004; 003's wording follows |

## Assumptions

Settled choices from the rulings and the design, stated so they are visible:

- Action names are kebab verb-noun with the `results-store.` prefix; spec 004 uses `results-store-operations.`.
- A known path with the wrong method gives `405` (D-METHOD); an empty hearing day gives `404` (D-DAY-404), which YOT already treats as "not held".
- `dayYouthSeen=true` stays on pull, with the warning that `notFalse` is the complete feed (D-PULL-TRUE).
- Search requires a court and a day range of at most 31 days; limits are 100 by default and 500 at most on both pull and search; over the maximum is refused, not clamped.
- The read beans are wired unconditionally, so `AuthzIT` and `ActuatorIntegrationTest` move onto the Testcontainers database and the `ApplicationContextRunner` tests get a stub data source.
- V5 uses plain `CREATE INDEX` because it deploys before go-live; `CONCURRENTLY` is noted as the fallback in the risks.
- The production database version is unknown; nothing in 003 depends on it.
- Spec 004 is authored from the tip of this branch and adds to the same route table, filter, rule file and OpenAPI document.
- Consumers ignore fields they do not know; new fields may be added to `/v1` items without a new version.
