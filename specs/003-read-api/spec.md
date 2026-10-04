# Feature Specification: Read API

**Feature Branch**: `003-read-api`
**Created**: 2026-10-03
**Status**: Implemented (phases A to D; the one contract release `0.2.0` and the bump from the draft follow, outside the tasks)
**Input**: User description: "Read API: the internal REST API under /results-store/v1 that consumers use to pull, search and fetch stored shares and their payloads"

**Sources**: the Results Store design page (CRA 321061800), sections *Read API*, *How the store makes pull safe*, *Security* and *Observability*; the design review of spec 003 and its critique, with the orchestrator's rulings on both (2026-10-03, sections A, B and D), which win where they differ from the design; the decisions taken with Sachin on 2026-10-03 (rulings section E), which win over everything else; the fact-finding reports on the store's read side (this repository at `c21a901`) and on YOT as the first consumer (YOT's payload port, its retry policy, its redesign's needs) with probation's asks S6 to S12 and gate G2; specs 001 (*Share intake*) and 002 (*Enrichment*), in particular 002 FR-040 and FR-041. Quotes in *italics* are the design page's or the rulings' wording.

### Scope

**In scope.** Five read endpoints under `/results-store/v1`: pull, search, one share, one share's payload, and every version of one hearing day. No response carries the message envelope's metadata (`_metadata`). The visibility lag that makes pull safe (90 seconds), with the database trigger it relies on, the shorter intake timeouts that make 90 seconds provable, a server-side statement timeout on every pooled connection, and a counter that shows when the lag's assumption broke. The action filter rewritten so the action always comes from method and path, unknown paths refused, and vendor media types neutralised. One allow rule per action. Bounded problem bodies for every refusal and error. The read indexes (migration V5). Metrics. The consumer contract (`contracts/read-api.md`) that the YOT and probation teams are pointed at for gate G2. An end-to-end check through the compose stack. Documentation changes, performed as the last task of phase C: constitution 2.2.0, design rules, spec 001 pointers, the spec 002 FR-041 amendment note, forward notes for the page owner and the consumer teams.

**Phase D (D-RAW accepted, E2).** A sixth endpoint that serves the text as it arrived, without the envelope metadata. It is built as its own phase after phases A to C, so the rest never waits on it.

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
2. **Given** a share whose store transaction is still open, **When** a consumer pulls, **Then** neither it nor any share with a higher `storedSeq` is returned until it has committed or rolled back.
3. **Given** a filter (`dayYouthSeen`, `courtCentreId`) that matches nothing in a range, **When** the page is not full, **Then** `nextStoredAfterSeq` still moves to the highest sequence number the store can vouch for, so the next call does not rescan the range.
4. **Given** any pull, **Then** the response carries `visibleUpTo`, and every share stored at or before it with `storedSeq` up to `nextStoredAfterSeq` has been presented (YOT's 18:00 barrier).
5. **Given** a share presented with `projectionStatus` `FAILED` or `dayYouthSeen` null, **When** the sweep later fills its key details, **Then** the share is not presented again by pull, and the contract tells the consumer to re-read it with `GET /shares/{shareId}`.
6. **Given** a pull with `courtCentreId`, **Then** only shares whose court is exactly that court are returned. A `FAILED` share (court unknown) is not returned, and is not presented later when the sweep fills in its court, because its `storedSeq` is behind the cursor.

---

### User Story 2 - A consumer fetches a share's payload and can prove it is intact (Priority: P2)

A consumer fetches `GET /shares/{shareId}/payload`. It gets the working copy (002 FR-041, as amended by 003) without the message envelope's metadata (`_metadata`), with an `ETag` that is the SHA-256 of exactly the bytes it received, and the share's identity and `enrichmentApplied` in headers. A repeat fetch with `If-None-Match` gets a `304`.

**Why this priority**: Every consumer builds its output from the payload. Probation checks the digest on every response (DV-19). No legacy CP query service returns the message envelope, so the store does not either (E8).

**Independent Test**: Store an enriched share. Fetch its payload. Check the SHA-256 of the body equals the unquoted `ETag`, the body has no `_metadata` key and otherwise holds the working copy's content, and the identity and enrichment headers match the share. Fetch again with that `ETag` in `If-None-Match`: `304`, no body.

**Acceptance Scenarios**:

1. **Given** a share whose working copy is held, **When** its payload is fetched, **Then** the body is `payload_json` without its `_metadata` member, as the database writes it as text; `Results-Store-Payload-Form` is `working-copy`; and the `ETag` is the quoted lower-case SHA-256 hex of the body bytes.
2. **Given** a share whose working copy is empty, **Then** the body is `payload_text` parsed by the service, with `_metadata` removed, written back as JSON text; `Results-Store-Payload-Form` is `arrived-text`; and the `ETag` is over those bytes.
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

### User Story 4 - A consumer searches by court and by register days or a time range (Priority: P4)

A consumer asks for the shares of one court centre either between two London calendar days or between two instants of `sharedTime`, optionally only the latest of each day and only youth-relevant days, and pages through them with an opaque cursor.

**Why this priority**: YOT's 18:00 cross-check needs the shares shared up to a moment, not up to a whole day; ad hoc support questions need the day form. It is a query, not a feed.

**Independent Test**: Store shares for two courts on three days, one at 00:30 BST. Search one court over two days with `latestOnly=true`: only that court's latest shares of those London days, ordered by `sharedTime` and id. Search the same court from one instant to another: only shares with `sharedTime` at or after the first and before the second. Page each with `limit=1` and the cursor without repeats or gaps.

**Acceptance Scenarios**:

1. **Given** `courtCentreId`, `sharedDayFrom` and `sharedDayTo`, **Then** shares whose London shared day falls in the range, both ends included, are returned.
2. **Given** `courtCentreId`, `sharedFrom` and `sharedTo`, **Then** shares whose `sharedTime` is at or after `sharedFrom` and before `sharedTo` are returned.
3. **Given** a day range longer than 31 days or reversed, **Then** `400` with `day_range_too_long` or `day_range_reversed`; **given** a time range longer than 31 days, or whose `sharedTo` is not after `sharedFrom`, **Then** `400` with `time_range_too_long` or `time_range_reversed`.
4. **Given** a day parameter and a time parameter in one call, **Then** `400 conflicting_parameters`.
5. **Given** a page with more rows after it, **Then** `nextCursor` is set; on the last page it is null.
6. **Given** a cursor that has been altered so that it no longer decodes to a valid position, cut short or is longer than 128 characters, **Then** `400` with `invalid_cursor` (FR-029; the cursor is not signed).

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

**Independent Test**: Send each bad parameter, each Spring MVC exception type and a `401`/`403` (also with `Accept: text/html`, which still gets the four-field JSON body). Check each body has exactly the four fields and no echoed value. Stop the database: `503 store_unavailable` with `Retry-After`.

**Acceptance Scenarios**:

1. **Given** any `4xx` or `5xx` from this service, **Then** the body holds only `type`, `title`, `status` and `reason`.
2. **Given** an unknown or repeated query parameter, **Then** `400` with `unknown_parameter` or `repeated_parameter`.
3. **Given** a connection failure or a query timeout, **Then** `503 store_unavailable` with a `Retry-After` in seconds.
4. **Given** any other failure, **Then** `500 internal_error`, logged by exception class only.

---

### User Story 7 - Operators see read traffic, refusals and a broken pull assumption (Priority: P7)

Support staff see how many reads each endpoint served and with what outcome, how long they took, how many requests were refused, by this service's filters or by authorisation, and why, how big pages and payloads are, and whether any store transaction ran longer than the visibility lag.

**Why this priority**: Refused requests never reach the audit filter, so a counter is their only record. The overrun counter turns the pull-safety assumption into an alert.

**Independent Test**: Run the scenarios of stories 1 to 6 and read `/actuator/prometheus`. Check each counter moved by the expected amount and every tag value is from a fixed list. Make a store transaction outlast a small lag: the overrun counter moves once.

**Acceptance Scenarios**:

1. **Given** a pull that returns three items, **Then** `resultsstore.read.requests{endpoint=pull,outcome=ok}` goes up by one and `resultsstore.read.page.items` records 3.
2. **Given** a `404 route_not_found`, **Then** `resultsstore.read.refused{reason=route_not_found}` goes up by one.
3. **Given** a store transaction whose time from sending the share insert to its commit returning is at or above the lag, **Then** `resultsstore.intake.visibility.overrun` goes up by one.
4. **Given** a request with no identity, or from a caller in neither admitted group, **Then** `resultsstore.read.refused{reason=unauthenticated}` or `{reason=forbidden}` goes up by one.

---

### User Story 8 - A consumer fetches the text as it arrived, without the envelope metadata (Priority: P8, phase D)

Probation (S10) builds today's EXT view from the raw event, before enrichment. It fetches `GET /shares/{shareId}/payload/arrived` and gets the text hearing sent, with `_metadata` removed, and an `ETag` that is the SHA-256 of exactly the bytes it received.

**Why this priority**: It serves one consumer's ask. D-RAW is accepted (E2); it is its own phase so phases A to C never wait on it.

**Independent Test**: Store an enriched share. Fetch the arrived text: parsed, the body equals the published message with its `_metadata` member removed (no application results added); the SHA-256 of the body equals the unquoted `ETag`; `Results-Store-Payload-Form` is `arrived-text`.

**Acceptance Scenarios**:

1. **Given** a stored share, **Then** the body is `payload_text` parsed by the service, with `_metadata` removed, written back as JSON text, and the `ETag` is the quoted SHA-256 of those bytes. The `ETag` is not, in general, `payload_sha256` (that is over the text as it arrived); the two coincide only for a compact message that held no `_metadata`.
2. **Given** a caller in neither admitted group, **Then** `403`; the endpoint has its own action and rule.

### Edge Cases

- A share stored at 00:30 BST belongs to the previous UTC day but the current London day: the day form of search filters on the London day; both days are in the item.
- A day range that spans a clock change: the London days are turned into instants at London midnight, so a 23-hour or 25-hour day is covered exactly.
- A time range given with an offset other than `Z`, or with more than six fraction digits: `400 invalid_shared_from` or `invalid_shared_to`.
- A consumer that needs envelope facts (`_metadata`, for example the sharer's user id): the store does not serve them.
- Two shares of one day with the same `sharedTime` cannot exist (unique key). Search's keyset still breaks ties on `shareId`.
- `storedSeq` has gaps (rolled-back transactions and duplicates take numbers). Gaps mean nothing.
- A share arrives with an earlier `sharedTime` than the day's latest: it is presented by pull when stored (higher `storedSeq`), with `isLatest` false and `arrivedOutOfOrder` true; the earlier presented shares' `versionNumber` values move.
- A share whose day later becomes youth-relevant through a new share: the new share is presented (higher `storedSeq`); the old one is not re-presented.
- A day flag that moves from unknown to `true` or `false` through the sweep, or through a spec-004 rerun: no new `storedSeq`; the consumer learns it only by re-reading (FR-031).
- `limit=0`, `limit=501`, `limit=abc`: `400 limit_out_of_range`.
- `storedAfterseq` (a typo): `400 unknown_parameter`, so a pull never silently becomes a search.
- A trailing slash, a `;param`, a double slash or upper-case letters in the path: matched exactly as Spring MVC routes it, so the filter and the controller never disagree.
- A payload of 2.4 MB: served whole; the response is buffered (the audit filter copies it), so memory is about three times the body.
- The database is upgraded to a new PostgreSQL major version: the same content may come back with other bytes and another `ETag` (FR-040). The same holds for a text the service writes itself (the `arrived-text` form and the arrived endpoint) if its JSON library changes how it writes.

## Requirements *(mandatory)*

### Functional Requirements

**Routes and common rules**

- **FR-001**: The service MUST serve exactly these routes under `/results-store/v1`, all `GET`: `/shares` (pull or search), `/shares/{shareId}`, `/shares/{shareId}/payload`, `/hearings/{hearingId}/days/{hearingDay}/shares`; and, from phase D (D-RAW accepted, E2), `/shares/{shareId}/payload/arrived`.
- **FR-002**: Query parameters MUST be case-sensitive. An unknown parameter MUST give `400 unknown_parameter`; a repeated one `400 repeated_parameter`.
- **FR-003**: Ids MUST be canonical UUIDs (8-4-4-4-12) and dates ISO `yyyy-MM-dd`; anything else MUST give `400` with the parameter's own reason.
- **FR-004**: Every field of every response item MUST always be present; a missing value MUST be written as JSON `null`, never omitted.
- **FR-005**: Every time MUST be an ISO-8601 UTC instant with exactly six fraction digits (for example `2026-10-03T09:15:00.120000Z`).
- **FR-006**: The share item MUST hold: `shareId`, `hearingId`, `hearingDay`, `sharedTime`, `storedSeq`, `storedAt`, `sharedDayLondon`, `sharedDayUtc`, `keyDetails` (`courtCentreId`, `courtRoomId`, `ljaCode`, `jurisdictionType`, `isSjp`, `isGroupProceedings`, `youthCourtId`, `isReshare`; the whole object null exactly when `projectionStatus` is `FAILED`), `anySubjectIsYouth`, `dayYouthSeen`, `isLatest`, `predecessorShareId`, `arrivedOutOfOrder`, `enrichmentApplied`, `projectionStatus`, `projectionVersion`, `projectedAt`, `versionNumber`. `sharedTime` MUST be the stored `shared_at`. There MUST be no `eventType` field: `keyDetails.isSjp` is the source (true = SJP, false = INT, null = unknown while `FAILED`).
- **FR-007**: `versionNumber` MUST be the share's 1-based position within its hearing day by `sharedTime`, computed when read and never stored.
- **FR-008**: The mutable values (`isLatest`, `predecessorShareId`, `dayYouthSeen`, `keyDetails`, `anySubjectIsYouth`, `projectionStatus`, `projectionVersion`, `projectedAt`, `versionNumber`) MUST be the values at the time of the read.

**Pull**

- **FR-009**: `GET /shares` with `storedAfterSeq` (an integer ≥ 0) MUST be a pull. It MAY add `limit`, `dayYouthSeen` (`notFalse` or `true`) and `courtCentreId`. `sharedDayFrom`, `sharedDayTo`, `sharedFrom`, `sharedTo`, `latestOnly` or `cursor` with it MUST give `400 conflicting_parameters`.
- **FR-010**: Pull MUST return shares with `storedSeq` greater than `storedAfterSeq`, in ascending `storedSeq`, at most `limit` (default 100, 1 to 500, else `400 limit_out_of_range`), with no payload.
- **FR-011**: `dayYouthSeen=notFalse` MUST select days whose flag is not `false` (unknown stays visible); `true` MUST select days whose flag is `true`; absent MUST mean any day. Any other value MUST give `400 invalid_day_youth_seen`.
- **FR-012**: `courtCentreId` on pull MUST select only shares whose court centre is exactly that court. Shares whose extraction is `FAILED` (court unknown) MUST NOT be returned (D-COURT-FAILED = no, E5). The contract MUST state the consequence: a share whose court the sweep fills in later is not presented to a court-filtered pull, because its `storedSeq` is already behind the cursor; a consumer that needs completeness uses the unfiltered pull or `dayYouthSeen=notFalse` and filters by court itself.
- **FR-013**: The pull response MUST be `{ items, nextStoredAfterSeq, hasMore, visibleUpTo }`. `hasMore` MUST be exact (the store reads one row more than `limit`).
- **FR-014**: When `hasMore` is true, `nextStoredAfterSeq` MUST be the last item's `storedSeq`. When it is false, `nextStoredAfterSeq` MUST be the greater of the request's `storedAfterSeq` and the visibility bound of FR-016 (the highest `storedSeq` the store can vouch for, filter or no filter), worked out in the same database statement as the page.
- **FR-015**: `visibleUpTo` MUST be the database's current time minus the visibility lag, from the same statement. The contract MUST state: every share stored at or before `visibleUpTo` with `storedSeq` at or below `nextStoredAfterSeq` has been presented (if it matched the filters at the time of the read).

**Pull safety**

- **FR-016**: Pull MUST return only shares whose `storedSeq` is at or below the visibility bound: the highest `storedSeq` among shares whose `stored_at` is at or before the database's current time minus the visibility lag. Both times MUST come from the database clock. A share below the bound is returned even if its own `stored_at` is a moment later than the cut-off, because its transaction has ended (research R4).
- **FR-017**: The default visibility lag MUST be the store transaction timeout plus twice the statement timeout plus the idle-in-transaction timeout: 60 + 2 × 10 + 10 = **90 seconds** at the intake defaults of FR-061 (D-LAG-VALUE, E3). The bound MUST rest on limits PostgreSQL enforces (the per-transaction settings of the store transaction) and on the Spring transaction deadline checked before each statement. Client-side timeouts (the JDBC query timeout, the driver's cancel) MUST never be part of the bound.
- **FR-018**: The service MUST refuse to start when the lag is below that sum or above 10 minutes. When the lag is not set and the derived default is above 10 minutes, the error MUST name `resultsstore.intake.store.transaction-timeout`.
- **FR-019**: A database trigger MUST set `stored_at` from the clock when each share row is inserted, after its `stored_seq` is assigned, so a share's `stored_at` is never earlier than the moment its sequence number was taken.
- **FR-020**: Intake MUST count `resultsstore.intake.visibility.overrun` when a store transaction's time from sending the share insert to its commit returning is at or above the lag (D-OVERRUN = yes, E4; it changes spec 001 code).
- **FR-021**: The configuration contract MUST document that the lag is checked against this pod's own intake settings only, that every pod runs the same `resultsstore.intake.store.*` values (no read-only pods or pods with other timeouts: D-READONLY-PODS = no, E10), the rule lag ≥ transaction + 2 × statement + idle-in-transaction and lock ≤ statement, and the rollout order: raise the lag before raising any intake timeout; lower the timeouts before lowering the lag.

**Read-time semantics (normative contract text)**

- **FR-022**: The contract MUST state that filters are evaluated at read time; that a share behind the cursor is never presented again; that its day's later share, which has a higher `storedSeq`, is presented when it is stored; and that each share is a full snapshot of its day.
- **FR-023**: The contract MUST state the unknown-row obligation: *a share presented with `projectionStatus` `FAILED` or `dayYouthSeen` null is not final in its key details; re-read `GET /shares/{shareId}` until `projectionStatus` is `OK` (or the day's successor arrives) before deciding it is not yours.* Such shares are presented by the unfiltered pull and by `dayYouthSeen=notFalse`; a court-filtered pull never presents a `FAILED` share (FR-012).
- **FR-024**: The contract MUST state that `dayYouthSeen=notFalse` is the complete feed for youth-relevant days, and that `dayYouthSeen=true` can miss a share whose day became `true` with no new share (kept, D-PULL-TRUE).
- **FR-025**: The contract MUST state each of these:
  - (a) `isLatest` can be false for a share that arrived out of order; the consumer reads the day's versions to find the latest.
  - (b) `versionNumber` can change.
  - (c) `keyDetails`, `dayYouthSeen` and `anySubjectIsYouth` can be rewritten in place, by the sweep or by a spec-004 rerun, with no new `storedSeq`.
  - (d) `projectionVersion` and `projectedAt` show when the key details were last written.
  - (e) Re-pulling from an older cursor is safe, and is the way to reconcile.
  - (f) Consumers keep their own idempotency guard on `shareId`.

**Search**

- **FR-026**: `GET /shares` without `storedAfterSeq` MUST be a search. `courtCentreId` MUST be present, with exactly one of two range forms (E6): the **day form**, `sharedDayFrom` and `sharedDayTo` (London register days), or the **time form**, `sharedFrom` and `sharedTo` (instants on `sharedTime`). A missing court, or neither form complete, MUST give `400 missing_parameter`; a parameter of each form in one call MUST give `400 conflicting_parameters`. It MAY add `dayYouthSeen` (`notFalse`, `true` or `false`), `latestOnly` (`true` or `false`, default false), `limit` (default 100, 1 to 500) and `cursor`.
- **FR-027**: The day form MUST filter on the London shared day, both ends included, over at most 31 days (`400 day_range_too_long`; `400 day_range_reversed` when from is after to; `400 invalid_shared_day` for a bad date). The time form MUST filter `sharedTime` on the half-open range [`sharedFrom`, `sharedTo`), at most 31 days long (`400 time_range_too_long`; `400 time_range_reversed` when `sharedTo` is not after `sharedFrom`). Each instant MUST be ISO-8601 UTC with `Z` and at most six fraction digits (`400 invalid_shared_from`, `400 invalid_shared_to`). The day form MUST be served as the time range from London midnight at the start of `sharedDayFrom` to London midnight at the end of `sharedDayTo`, which selects exactly the same shares.
- **FR-028**: Search MUST order by `sharedTime`, then `shareId`, ascending, in both forms, and page by keyset on those two values. The response MUST be `{ items, nextCursor }`, `nextCursor` null on the last page.
- **FR-029**: The cursor MUST be opaque base64url text of at most 128 characters, decoded strictly; anything that does not decode to a valid position MUST give `400 invalid_cursor`.
- **FR-030**: The visibility lag MUST NOT apply to search, one share or the day's versions. The contract MUST say search is a query, not a feed: shares stored while paging and `FAILED` shares are not guaranteed to appear.

**One share and the day's versions**

- **FR-031**: `GET /shares/{shareId}` MUST return the item, or `404 share_not_found`.
- **FR-032**: `GET /hearings/{hearingId}/days/{hearingDay}/shares` MUST return `{ items }` in `sharedTime` order, unpaged, or `404 hearing_day_not_found` when the day has no share.

**Payload**

- **FR-033**: `GET /shares/{shareId}/payload` MUST return, as `application/json`, the exact UTF-8 bytes of the working copy **without its `_metadata` member**, as the database writes it as text (E8). When the working copy is empty it MUST return `payload_text` parsed by the service, with `_metadata` removed, written back as JSON text (002 FR-041, as amended by 003). If that text ever fails to parse, the answer MUST be `500 internal_error`, never the text with `_metadata`.
- **FR-034**: The `ETag` MUST be strong and quoted: the lower-case SHA-256 hex of exactly the bytes in the body. `payload_sha256` MUST never be offered as this endpoint's `ETag`.
- **FR-035**: The response MUST carry `Results-Store-Share-Id`, `Results-Store-Hearing-Id`, `Results-Store-Hearing-Day`, `Results-Store-Shared-Time`, `Results-Store-Enrichment-Applied` (`true` or `false`), `Results-Store-Payload-Form` (`working-copy` or `arrived-text`) and `Cache-Control: no-store`.
- **FR-036**: `If-None-Match` matching the current `ETag` (weak comparison; a list or `*` accepted) MUST give `304` with exactly one `ETag` header and no body.
- **FR-037**: The response MUST have no `Content-Encoding`, a `Content-Length` equal to the body's byte count and no chunked transfer encoding, also when the audit filter is on.
- **FR-038**: The payload queries (the payload, and in phase D the arrived text) MUST be the only read queries that touch `hearing_share_payload` (Principle III).
- **FR-039**: No response of the read API MUST carry the message envelope's metadata (`_metadata`) or any value taken from it (E8). The contract MUST say why: no legacy CP query service returns the message envelope, and the store follows the estate convention; a consumer that needs envelope facts does not get them from the store. The `Results-Store-*` headers of FR-035 are the store's own facts and stay.
- **FR-040**: The contract MUST promise that a share's body and `ETag` are stable while the database's PostgreSQL major version is unchanged, that an upgrade may change the bytes and the `ETag` but never the content, and that a consumer verifies each response against its own `ETag` (D-JSONB-PROMISE, E7). For the texts the service writes itself (the `arrived-text` form and the arrived endpoint) it MUST say the same of an upgrade of the service's JSON library.

**Arrived text (phase D; D-RAW accepted, E2)**

- **FR-041**: `GET /shares/{shareId}/payload/arrived` MUST return `payload_text` parsed by the service, with `_metadata` removed, written back as JSON text: the text as it arrived, without the envelope metadata. It MUST have its own action and allow rule. Its `ETag` MUST be the quoted lower-case SHA-256 hex of exactly the bytes served; It is not offered as, nor to be compared with, `payload_sha256` (that checksum is over the text as it arrived); the two coincide only for a compact message that held no `_metadata`. It MUST carry the same headers as FR-035 with `Results-Store-Payload-Form: arrived-text`, and FR-036 and FR-037 apply to it.

**Errors**

- **FR-042**: Every `4xx` and `5xx` body MUST be `{"type":"about:blank","title":<the HTTP reason phrase>,"status":<n>,"reason":<code>}` and nothing else, with `reason` from the fixed list in `contracts/read-api.md`. No body MUST ever hold a caller's value, a request path, an exception message or payload content.
- **FR-043**: Spring MVC's own exceptions MUST be rendered through the same four fields, including `405 method_not_allowed` and `406 not_acceptable`; `spring.mvc.problemdetails.enabled` MUST stay false. The `/error` page (where the authorisation library's `401` and `403` land) MUST be served by the service's own error controller, which answers every `Accept`, `text/html` included, with the four-field body, as `application/json` for `401` and `403` and as `application/problem+json` for any other status (as amended: contracts/read-api.md §6); the white-label page stays off.
- **FR-044**: A connection failure or a query timeout MUST give `503 store_unavailable` with `Retry-After` in delta-seconds. Any other failure MUST give `500 internal_error`, logged by exception class with `shareId` in the logging context when known, never the message.
- **FR-045**: Each read query MUST have a timeout (default 5 seconds) below the driver's socket timeout. This is the JDBC query timeout: the driver cancels the query from the client side, and the 30-second socket timeout backs it up. It is not PostgreSQL's server-side `statement_timeout`, which intake sets per transaction (FR-017). A client-side cancel is enough for reads: a read holds no lock that blocks intake, and a late cancel only delays one answer, it never breaks pull safety. The pool-wide server-side `statement_timeout` of FR-062 also applies to every read, as a backstop above the 5 seconds.

**Authorisation**

- **FR-046**: The action MUST be derived from method and path for every request under the service. Pull and search MUST be separate actions, told apart by the presence of `storedAfterSeq`, looked up only after the method and path match. Actions: `results-store.pull-shares`, `results-store.search-shares`, `results-store.get-share`, `results-store.get-share-payload`, `results-store.list-hearing-day-shares`, and (phase D) `results-store.get-share-arrived-payload`.
- **FR-047**: On a mapped route the caller's `CPP-ACTION` MUST be overwritten, and `Content-Type` and `Accept` MUST answer `application/json` wherever they name a vendor media type.
- **FR-048**: The service's filters MUST refuse or pass requests as follows:
  - (a) A path under the service that matches no route MUST give `404 route_not_found`, before authorisation.
  - (b) A known path with another method, `HEAD` and `OPTIONS` included, MUST give `405 method_not_allowed` with `Allow`, before authorisation.
  - (c) `/actuator/**` and `/error` MUST pass with `CPP-ACTION` removed and their media types untouched; `TRACE` on them gives `405 method_not_allowed` with `Allow: GET` (as amended in gate round 1: no servlet may echo a request's headers).
  - (d) `multipart/*` on a route MUST give `415 unsupported_content_type`, after authorisation and before the audit filter.
- **FR-049**: Each action MUST have one allow rule admitting "System Users" and "Second Line Support", which also matches the request's method and path, so a spoofed action name alone never passes. No rule admits everything; `deny-when-no-rules` stays true.
- **FR-050**: The service MUST refuse to start with `authz.http.enabled` false unless the `test` profile is active (D-AUTHZ-REQUIRED = yes, E12).

**Audit**

- **FR-051**: Every request that reaches an endpoint MUST be audited by `cp-audit-filter-springboot`. A request refused by this service's filters (`404` and `405` before authorisation, `415` after it), by the authorisation library (`401`, `403`) or by the HTTP connector before the service sees it (a malformed request target answered `400 bad_request` by the host's error report) never reaches the audit filter, so it is not audited; every such refusal MUST be counted in `resultsstore.read.refused` with its reason, a connector refusal as `connector_rejected`, once its body has been written (D-REFUSALS-UNAUDITED and D-VII-AUDIT-WORDING accepted, E13).
- **FR-052**: The payload endpoints' audit response event MUST carry the fixed marker `{"payloadOmitted":true}` in place of the body (D-AUDIT option 4, E1): the service replaces the library's `AuditPayloadGenerationService` bean. In parallel the library owners are asked for a body-exclusion switch (option 2); the DPIA records both. List pages keep their bodies. A test MUST pin the behaviour.
- **FR-053**: Every path template MUST be in `results-store-openapi.yaml` with its path parameters declared, and every described route MUST be served (checked both ways by a test).

**Published contract**

- **FR-063**: The read API's contract MUST be published from `hmcts/api-cp-crime-results-store` as `uk.gov.hmcts.cp:api-cp-crime-results-store` (generated `SharesApi` and models, the spec at `openapi/openapi-spec.yml`), and the service MUST take it as its `apiSpec` dependency and implement the generated `SharesApi`. The service's own `results-store-openapi.yaml` (kept for the audit filter) MUST NOT drift from the jar's spec: a build-time test MUST compare paths, parameters, responses, headers, schemas and tags, ignoring only `info` and `servers`. Every contract change MUST be made in the api repository first; a release of the service MUST NOT depend on a draft contract version (`validateApiSpecVersions`). Research R23.

**Schema**

- **FR-054**: Migration V5 MUST add the stored-at trigger (FR-019) and three partial indexes: on `stored_seq` for days whose flag is not `false` (youth pull); on court centre and `stored_seq` for rows with a court (court pull, now an exact match, E5); and on court centre, `shared_at` and `share_id` for rows with a court (both search forms, E6). V1 to V4 MUST NOT be edited. No table or column is added.

**Metrics**

- **FR-055**: The service MUST publish `resultsstore.read.requests{endpoint,outcome}`, `resultsstore.read.refused{reason}`, `resultsstore.read.duration{endpoint}`, `resultsstore.read.page.items`, `resultsstore.read.payload.bytes` and `resultsstore.intake.visibility.overrun`, with every tag value from a fixed list, registered at start.

**Configuration**

- **FR-056**: The read settings MUST be typed and checked at start: `resultsstore.read.pull.visibility-lag` (FR-017, FR-018) and `resultsstore.read.statement-timeout` (FR-045). The read beans MUST be wired whatever `resultsstore.publicevents.enabled` says.

**Documentation (performed by the last task, not now)**

- **FR-057**: Constitution 2.1.0 MUST become 2.2.0 (MINOR) with Principle VII reworded: the action derived from method and path for every request; caller `CPP-ACTION` and vendor media types overridden; an unmapped path refused; read rules admitting "System Users" and "Second Line Support"; *every request that reaches an endpoint is audited; a request refused by a filter, by the connector or by authorisation is counted* (E13; the connector named as FR-051 counts it); and the payload endpoints' response body replaced by a fixed marker in the audit event (E1). Principle II's read-API sentence MUST become: *The read API serves the working copy without the message envelope's metadata (`_metadata`), and the text, likewise without it, when the working copy is empty* (E8), and gains the arrived-text endpoint: *and, on its own endpoint, the text as it arrived, likewise without the envelope metadata* (E2). Both Principle II changes are written in 2.2.0 by T012; T013 touches no constitution.
- **FR-058**: The design rules file (`.claude/rules/design_rules.md`) MUST replace the "lowest open write" pull-safety sentence with the visibility lag (90 seconds), and its security bullets MUST match FR-049 and FR-051. Spec 001's forward references to "indexes in 003" MUST point at V5, and its intake timeout defaults MUST carry an *Amended by spec 003* note (FR-061, FR-062). Spec 002's FR-041 MUST carry an *Amended by spec 003* note: the served bytes are the working copy without `_metadata`.
- **FR-059**: Forward notes for the design page owner and for the YOT and probation teams MUST be written in `page-notes.md`; the page itself is not edited.

**End to end**

- **FR-060**: The container smoke check MUST call the API over HTTP after a share is stored: pull lists it after the lag; one share `200`; payload `200` with the SHA-256 of the body equal to the `ETag` and no `_metadata` key in the body; `If-None-Match` `304`; day versions `200`; no identity `401`; a caller in neither group `403`; an unmapped path `404 route_not_found` with no path echoed; a vendor `Accept` on pull still `200`; and the read meters present.

**Intake timeouts and the pool backstop (change spec 001 code; E3)**

- **FR-061**: The intake store timeout defaults MUST change so the 90-second lag holds: `resultsstore.intake.store.statement-timeout` 20 s → **10 s** and `resultsstore.intake.store.lock-timeout` 10 s → **5 s**, in `application.yaml` and in `IntakeProperties`; `transaction-timeout` (60 s) and `idle-in-transaction-timeout` (10 s) stay. The existing rules stay: lock ≤ statement ≤ transaction, idle-in-transaction ≤ transaction, statement below the socket timeout.
- **FR-062**: Every connection the pool opens MUST start with a server-side `statement_timeout` equal to `resultsstore.intake.store.statement-timeout`, set through Hikari's connection-init SQL and derived from that same property, so the two cannot differ; a test MUST prove they agree, at the default and at a custom value. It is a backstop for the read API and for any statement that forgets its own limit. The store transaction's own per-transaction settings stay the source of truth for the lag's proof.

### Changes to spec 001 and spec 002

003 amends or touches these parts. Specs 001 and 002 are updated at the end of phase C (FR-058); until then this section is the record.

- **001 forward references** (`spec.md` *Out of scope*, *Assumptions* "Consumer search indexes are left to spec 003"; `data-model.md` "spec 003 adds them with the read API"): delivered as V5's three indexes. The defendant-id index is still not built.
- **001 intake timeouts** (`application.yaml`, `IntakeProperties`, 001 `contracts/configuration.md`): statement timeout 20 s → 10 s, lock timeout 10 s → 5 s (FR-061). Every pooled connection now starts with `statement_timeout` set to the intake statement timeout (FR-062).
- **001 `stored_at`**: still `DEFAULT clock_timestamp()`, but the value is now set by V5's `BEFORE INSERT` trigger, after the identity value is taken. Its meaning (when the store received the share) is unchanged.
- **001 intake code** (D-OVERRUN): `IntakeObserver` gains one method; `StoreResult.Stored` gains the insert-to-commit time; `IntakeService` compares it with the lag. Intake behaviour is otherwise unchanged.
- **001 `contracts/metrics.md` "Not in 001"**: points at 003's metrics delta for the read meters and the overrun counter.
- **001 `research.md`** ("A pull cursor must use `stored_seq`, never `xmin`"; "The read side will filter `IS NOT FALSE`"): honoured as written.
- **002 FR-040**: honoured as written.
- **002 FR-041 amended** (also `contracts/schema.md` rule 5 and `page-notes.md` §3): the served bytes are the working copy **minus `_metadata`** (`(payload_json - '_metadata')::text`), or, when the working copy is empty, `payload_text` parsed with `_metadata` removed and written back as JSON text. The `ETag` is over the bytes served, as before (E8).

### Key Entities *(include if feature involves data)*

- **Share item (read view of `hearing_share`)**: the fields of FR-006. Ids, flags, times and key details only; no payload. It still tells a reader which hearings concern a youth, so access is controlled and audited.
- **Pull page**: items, `nextStoredAfterSeq`, `hasMore`, `visibleUpTo`.
- **Search page**: items, `nextCursor`.
- **Served payload**: the bytes (without `_metadata`), their `ETag`, the share's identity, `enrichmentApplied` and the payload form. Special-category and youth personal data.
- **Visibility lag**: a duration (90 seconds by default), the same on every pod; the pull withholds every share stored more recently than that.
- **No new table or column.** V5 adds one trigger and three indexes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the two-connection race test, 0 shares are returned past a lower sequence number whose transaction is still open, with the lag above the open transaction's age; with lag 0 the race is shown, so the test can fail.
- **SC-002**: Paging a pull from 0 until `hasMore` is false returns every stored share exactly once, in ascending `storedSeq`, in 100 % of the integration runs, including with filters that match nothing in a range (the cursor still advances).
- **SC-003**: For 100 % of payload responses in the tests, the SHA-256 of the body equals the unquoted `ETag`, the body has no `_metadata` key, and exactly one `ETag` header is sent on `200` and `304`.
- **SC-004**: Each endpoint serves "System Users" and "Second Line Support" callers, and refuses a caller in neither group (`403`) and one with no identity (`401`): 100 % of the route × caller cases pass.
- **SC-005**: 0 of the spoofing cases (route × `CPP-ACTION`, vendor `Content-Type`, vendor `Accept`, `Accept` list) change an outcome.
- **SC-006**: 0 error bodies in the tests hold a field other than `type`, `title`, `status` and `reason`, or a caller value, path, exception text or payload marker.
- **SC-007**: The V5 plan tests show each pull and search variant (both search forms) served by its intended index, the court pull and the search with no sort step, and no pull, search, share or day query touching `hearing_share_payload`.
- **SC-008**: The service fails to start in 100 % of the start-up tests with a lag below the derived bound, a lag above 10 minutes, a read statement timeout at or above the socket timeout, or authorisation off outside the test profile.
- **SC-009**: The overrun counter moves exactly once for a store transaction made to outlast a 1-second test lag, and 0 times for one inside it.
- **SC-010**: 0 meter tags in the tests hold an id, a date or a value outside the fixed lists; every read meter exists at start with value 0.
- **SC-011**: The audit test pins the D-AUDIT behaviour (option 4): the payload response event holds `{"payloadOmitted":true}` and no body byte; a `304` publishes no response event; a `415` publishes nothing.
- **SC-012**: The container smoke check passes every FR-060 case, with the 001 and 002 cases still passing.
- **SC-013**: The build gate passes: line coverage at least 0.88, branch coverage at least 0.85, PMD clean on main and test.
- **SC-014** (phase D): in 100 % of the cases the arrived-text body, parsed, equals the published message with its `_metadata` member removed; the body has no `_metadata` key; and the SHA-256 of the body equals the unquoted `ETag`, which is not, in general, `payload_sha256` (the two coincide only for a compact message that held no `_metadata`).
- **SC-015**: In 100 % of the start-up cases (the default and a custom intake statement timeout), a pooled connection reports a `statement_timeout` equal to `resultsstore.intake.store.statement-timeout`, and the intake defaults are statement 10 s, lock 5 s, transaction 60 s and idle-in-transaction 10 s, giving a derived lag of 90 s.

## Decisions taken with Sachin (2026-10-03)

Every decision below is taken; nothing in 003 is pending. The only open item is a risk, not a decision: the production PostgreSQL version and whether it uses synchronous replication (D-PG-VERSION / HA, research R4, plan *Risks*). D-YOUTH-RAISE stays with spec 004 (E14); 003's contract only states that a rerun can rewrite `dayYouthSeen` and key details in place with no new `storedSeq` (FR-025).

| Id | Question | Decision | Where it shows |
|---|---|---|---|
| E1 D-AUDIT | The audit library 1.0.5 copies whole response bodies into audit events and has no switch. What happens to payload bodies? | Option 4: replace the library's `AuditPayloadGenerationService` bean so the payload routes' response event holds `{"payloadOmitted":true}`. Option 2 in parallel: ask the library owners for a body-exclusion switch. Recorded in the DPIA. List responses keep their bodies | FR-052, FR-057; SC-011; T011, T012, T013 |
| E2 D-RAW | Offer the arrived text on its own endpoint (probation S10)? | Yes, built as phase D, with its own action and rule. It serves the arrived text **without `_metadata`**, so its `ETag` is the SHA-256 over the bytes served, not, in general, `payload_sha256` (they coincide only for a compact message that held no `_metadata`) | FR-001, FR-041, FR-046, FR-057; US8; SC-014; T012 (constitution II), T013 |
| E3 D-LAG-VALUE | The lag bound | **90 s** = transaction 60 + 2 × statement 10 + idle-in-transaction 10. Made provable by lowering the intake statement timeout 20 s → 10 s and the lock timeout 10 s → 5 s. Plus a pool-wide server-side `statement_timeout` from the same property (Hikari connection-init SQL). Client-side timeouts are never part of the bound | FR-017, FR-018, FR-021, FR-045, FR-061, FR-062; SC-015; T008, T009 |
| E4 D-OVERRUN | Add the intake-side overrun counter, touching spec 001 code? | Yes: `resultsstore.intake.visibility.overrun` | FR-020; SC-009; T008 |
| E5 D-COURT-FAILED | Should a court-filtered pull also return `FAILED` shares (court unknown)? | **No**: exact court matches only. The contract states that a share whose court is filled in later is not re-presented to a court-filtered pull; completeness needs the unfiltered pull or `dayYouthSeen=notFalse` | FR-012, FR-023, FR-054; T004, T006, T011 |
| E6 Search by shared time | How does a consumer ask for shares up to a moment? | Pull stays on `storedSeq`. Search takes the day form or a half-open instant range on `sharedTime` (at most 31 days), both ordered by `sharedTime` then `shareId`, keyset on (`shared_at`, `share_id`); one index (`court_centre_id`, `shared_at`, `share_id`) serves both | FR-026–FR-029, FR-054; US4; T004, T005, T006, T007, T010 |
| E7 D-JSONB-PROMISE | What the contract promises about payload bytes across database upgrades | Stable within a PostgreSQL major version; verify each response against its own `ETag`; probation DV-19 to be told | FR-040; T012 |
| E8 No `_metadata` | Does any response carry the message envelope's metadata? | **No.** The payload endpoint serves the working copy without `_metadata`; the text fallback and the arrived endpoint likewise. `ETag` over the bytes served. The `Results-Store-*` headers stay. Constitution II reworded; 002 FR-041 amended | FR-033, FR-039, FR-041, FR-057, FR-058; US2; SC-003; T006, T007, T010, T011, T012, T013 |
| E9 | Probation's user-id flag (S12) | Not addressed in 003: a consumer concern, dealt with when the probation design is finalised. The store exposes no envelope metadata | *Assumptions*; page-notes §7 |
| E10 D-READONLY-PODS | Will any deployment run read-only pods, or pods with other intake timeouts? | No: every pod runs the same settings; rollout order documented | FR-021; T009, T012 |
| E11 D-PG-VERSION / HA | Production PostgreSQL version and synchronous replication | Unknown: a recorded risk, not a decision. PostgreSQL 17 `transaction_timeout` is a later tightening | research R4; plan *Risks*; T012 *Deferred* |
| E12 D-AUTHZ-REQUIRED | Refuse to start with authorisation off outside the test profile? | Yes | FR-050; SC-008; T003 |
| E13 D-VII-AUDIT-WORDING, D-REFUSALS-UNAUDITED | Constitution VII audit wording; refused requests counted, not audited | Accepted: *every request that reaches an endpoint is audited; a request refused by a filter or by authorisation is counted* | FR-051, FR-057; T003, T011, T012 |

## Assumptions

Settled choices from the rulings and the design, stated so they are visible:

- Action names are kebab verb-noun with the `results-store.` prefix; spec 004 uses `results-store-operations.`.
- A known path with the wrong method gives `405` (D-METHOD); an empty hearing day gives `404` (D-DAY-404), which YOT already treats as "not held".
- `dayYouthSeen=true` stays on pull, with the warning that `notFalse` is the complete feed (D-PULL-TRUE).
- Search requires a court and one range of at most 31 days (London days or instants); limits are 100 by default and 500 at most on both pull and search; over the maximum is refused, not clamped.
- The store exposes no envelope metadata; whether a message carried a user id is not served (E9: a consumer concern, dealt with when the probation design is finalised).
- The read beans are wired unconditionally, so `AuthzIT` and `ActuatorIntegrationTest` move onto the Testcontainers database and the `ApplicationContextRunner` tests get a stub data source.
- V5 uses plain `CREATE INDEX` because it deploys before go-live; `CONCURRENTLY` is noted as the fallback in the risks.
- The production database version is unknown (E11); nothing in 003 depends on it. It is a recorded risk.
- Spec 004 is authored from the tip of this branch and adds to the same route table, filter, rule file and OpenAPI document.
- Consumers ignore fields they do not know; new fields may be added to `/v1` items without a new version.
