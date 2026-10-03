# Research: Read API

**Feature**: `003-read-api` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

Each entry gives the decision, why, and what else was looked at. R1 to R3 settle the web edge (layering,
routes, rules); R4 to R8 the pull and what a consumer may rely on; R9 to R12 search, the payload and its
headers; R13 and R14 errors and audit; R15 to R18 the schema, times, metrics and settings; R19 the
arrived text; R20 the constitution; R21 and R22 the day's versions and the build traps. Every open point
is settled here, or is a row of spec.md *Decisions pending Sachin* with its default applied.

Sources: the design review of spec 003 and its critique; the orchestrator's rulings on both
(2026-10-03), which win where they differ; the fact-finding reports on the store's read side (this
repository at `c21a901`, including `javap` of `cp-auth-rules-filter` 1.0.7 and
`cp-audit-filter-springboot` 1.0.5) and on YOT as the first consumer, with probation's asks; specs 001
and 002; and this repository's code as read for this document (`V3__create_share_store.sql`,
`V4__projection_tried_at.sql`, `JdbcShareStore`, `IntakeConfig`, `IntakeProperties`, `Rules`,
`IntakeObserver`, `application.yaml`, `application-test.yaml`).

---

## R1. Layering and wiring

**Decision.** As `design_rules.md` sets out:

- `api/` holds hand-written controllers that parse parameters, call `ShareReadService` once and map the
  answer to `*Response` records or a bounded problem body; the exception advice; the bounded `/error`
  attributes; the instant format.
- `application/` holds `ShareReadService` and the ports `ShareQueries`, `ReadObserver` and
  `RefusalObserver`. Nothing there imports JDBC or HTTP types.
- `domain/` holds the read types (`ShareView`, `DayYouthFilter`, `SearchCursor`, `StoredPayload`,
  `PayloadForm`) and the bounded tag enums (`ReadEndpoint`, `ReadOutcome`, `RouteRefusal`).
- `persistence/` gains `JdbcShareQueries`, read-only, separate from `JdbcShareStore`, autocommit, over
  its own `JdbcTemplate` with a query timeout.
- `filters/` holds the route table `ApiRoute`, the rewritten `ActionHeaderFilter`, `ActionRequestWrapper`,
  `UnsupportedContentTypeFilter`, `RefusalWriter`, and (D-AUDIT option 4) the audit payload override.
- `config/` holds `ReadApiProperties`, `ReadApiConfig`, `ApiWebConfig`, `MicrometerReadObserver` and
  `MicrometerRefusalObserver`.

The read beans are wired **unconditionally**, not behind `resultsstore.publicevents.enabled`
(`IntakeConfig`'s intake beans are all conditional on it). A read-only pod is a valid shape.

**Consequence.** Every Spring context now needs a data source. Of the twelve `@SpringBootTest` classes,
ten already register `support/PostgresTestSupport`; `integration/AuthzIT` and
`integration/ActuatorIntegrationTest` do not, and move onto it (T003). The three
`ApplicationContextRunner` tests (`ConfigurationValidationTest`, `IntakeConfigTest`,
`SweepSchedulingConfigTest`) get a stub `DataSource` bean where they load `ReadApiConfig` (T009). The
`test` profile's header comment ("no database … so context-load tests run without Docker") becomes
false and is corrected (T003). `./gradlew test` already needs Docker.

**Alternatives considered.** An `ObjectProvider` or `@ConditionalOnBean(DataSource)` seam keeping the
two tests database-free: rejected, the read API would silently vanish from a context with no data
source instead of failing to start.

---

## R2. The route table and how the action is derived

**Decision.** `filters/ApiRoute` is an enum with one constant per (method, path template, action,
`ReadEndpoint` tag). Templates are compiled with Spring's `PathPatternParser.defaultInstance`, and the
filter matches `RequestPath.parse(requestURI, contextPath).pathWithinApplication()` against them. That
is Spring MVC's own matcher: it decodes per segment, strips `;params` and does not match a trailing
slash, so the filter can never name one route while MVC serves another.

Pull and search share `GET /results-store/v1/shares`. They are told apart by whether `storedAfterSeq`
is present, and that parameter is looked up only after the method and path match (so the filter never
reads a form body of another method).

`ActionHeaderFilter`, rewritten:

1. Mapped (method, template): the request is wrapped (`ActionRequestWrapper`). `getHeader`,
   `getHeaders` and `getHeaderNames` always answer the derived `CPP-ACTION`. `getContentType`,
   `Content-Type` and `Accept` answer `application/json` wherever the vendor pattern
   `(?i)\bapplication/vnd\.([a-z0-9][a-z0-9._-]*)(?:\+[^\s;,]+)?\b` matches; the whole value is
   replaced. Reason: `cp-auth-rules-filter` 1.0.7 resolves the action from a vendor token in
   `Content-Type`, then in `Accept`, then `CPP-ACTION`, then `"<METHOD> <path>"` (facts, `javap`), so
   overwriting `CPP-ACTION` alone lets a caller pick an action with `Accept: application/vnd.<x>+json`.
2. Path known, method not (`HEAD`, `OPTIONS`, `POST` …): `405 method_not_allowed` with `Allow`.
   Refusing `OPTIONS` matters: the authorisation library always lets `OPTIONS` through.
3. No template matches, and the path is not `/actuator/**` or `/error`: `404 route_not_found`, the
   chain is not called, the refusal is counted.
4. `/actuator/**` and `/error`: passed through with `CPP-ACTION` removed and media types untouched, so
   actuator's own content negotiation still works.

The filter stops being a `@Component`; `ApiWebConfig` registers it at `HIGHEST_PRECEDENCE`. Order:
action filter (`HIGHEST_PRECEDENCE`), `HttpAuthzFilter` (`+30`, the library's), `UnsupportedContentTypeFilter`
(`+40`), the library's `AuditFilter` (no order declared; `FilterOrderIT` asserts where Boot puts it).

**Consequence.** An unknown path gets `404` before authentication, not `401`. Acceptable: the route
list is public in the OpenAPI document.

**Alternatives considered.** The exact-URI map of today's filter (no templates, unmapped paths pass with
the caller's header; it contradicts `design_rules.md`, "refuses a path it cannot map"). YOT's fixed map
plus one regex: works, but two matchers that can disagree with MVC.

---

## R3. Allow rules

**Decision.** `acl/results-store-rules.drl`: one allow rule per action, no package, the existing imports
and global, no deny rules. Each rule admits **"System Users" and "Second Line Support"** (constitution
VII: support staff *read payloads through the read API under its own rules*). Each rule also matches the
`Action`'s method and path attributes against its route (the library hands Drools
`Action(name, {method, path})`, facts), so a spoofed action name that slipped past the wrapper still
fails. The exact Drools form of the attribute match is fixed in T001 against the library's `Action`
class. `ResultsStoreRulesTest` proves: each read action allowed for each group; refused for a caller in
neither; refused with the right name but the wrong method or path; an unknown action refused; exactly
one rule per action.

**Alternatives considered.** "System Users" only (the design review's draft): leaves support staff with
no way to read a payload, against VII's wording. A youth-scoped action: superseded by constitution VII.

---

## R4. Pull safety: the visibility bound

**Decision.** A pull returns only shares with `storedSeq` at or below the **visibility bound** `S`: the
highest `stored_seq` among shares whose `stored_at` is at or before `now() − lag`, both from the
database clock, in the same statement as the page. The default lag is 110 seconds (D-LAG-VALUE, pending
Sachin). A V5 `BEFORE INSERT` trigger sets `stored_at := clock_timestamp()`.

**The race.** `stored_seq` is taken inside the store transaction (`INSERT_SHARE`,
`JdbcShareStore.java:78-90`), and that transaction goes on to write the payload, defendants, chain and
youth flags before it commits. So seq 101 can be taken, then 102 taken and committed first; a reader
that returned 102 and moved its cursor past 101 would never see 101.

**Why the trigger.** In V3, `stored_at` (`DEFAULT clock_timestamp()`) is declared before `stored_seq`
(identity), so within one insert the clock can be read *before* the number is taken. A `BEFORE INSERT`
trigger runs after the row's defaults and identity value are set, so with it a share's `stored_at` is
never earlier than the moment its number was taken. The proof needs exactly that ordering.
`FlywayMigrationIT` proves `stored_at` is at or after a clock read taken just before the insert.

**The bound chain.** Every store transaction ends within this time of its start (all limits enforced by
PostgreSQL or by Spring before a statement, none by the driver's client-side cancel):

| Segment | Bound (default) | Enforced by |
|---|---|---|
| Every statement starts before the Spring deadline | `transaction-timeout` (60 s) from the start | the `TransactionTemplate` timeout (`IntakeConfig.java:91-92`); `StoreTimeoutIT` proves the give-up |
| The last statement, started just before the deadline | `statement-timeout` (20 s) | PostgreSQL `statement_timeout`, set per transaction (`JdbcShareStore.SET_TIMEOUTS`) |
| The JVM's gap before `COMMIT` | `idle-in-transaction-timeout` (10 s) | PostgreSQL ends the session and aborts the transaction |
| `COMMIT`, with the deferred constraint triggers | `statement-timeout` (20 s) | PostgreSQL applies `statement_timeout` to `COMMIT` (design review; not re-checked against the PostgreSQL source) |

Sum: 60 + 20 + 10 + 20 = **110 s**. The design review's 91 s replaced the last-statement row with "1 s
past the deadline", which is the JDBC query timeout that pgjdbc enforces on the client by sending a
cancel over a new connection. A GC pause, a failed cancel connection or a pooler that does not route
cancel keys lets the statement run to the server's 20 s, so 91 s is not a bound PostgreSQL guarantees.

**Proof sketch.** Let `S` be the highest `stored_seq` with `stored_at_S ≤ now() − lag`. Take any row
`W` with `stored_seq_W ≤ S`. Identity values are handed out in time order (the identity sequence has
cache 1, the default; V3 sets no cache, and `FlywayMigrationIT` asserts it), so `W` took its number no
later than `S` did. `S` took its number before `stored_at_S` (the trigger). So `W`'s transaction started
before `stored_at_S ≤ now() − lag`, and ended within the sum of the chain, which the lag is at least:
`W` committed or rolled back before the reader's `now()`. The reader's snapshot is taken after `now()`
(an autocommit statement), so a committed `W` is in it. Every row at or below `S` is therefore final.

Two consequences:

- The page filter is on `stored_seq ≤ S`, **not** on each row's own `stored_at`. A row `W'` can take a
  number below `S`'s and read the clock a moment after `S` did (it was descheduled between the two),
  so its own `stored_at` falls just after the cut-off while `S`'s falls before. Filtering rows by their
  own `stored_at` would return `S`, move the cursor past `W'` and lose it. Filtering by `S` returns `W'`,
  which is safe because its transaction has ended. (This refines the design review's row filter; the
  high-water value is the rulings' own, R7.)
- When no share is older than the lag, `S` is null: nothing is returned and the cursor stays put.

**Residual holes (stated, not hidden).**

- (a) A WAL flush stall longer than the lag: the commit record's flush cannot be cancelled.
- (b) A crash or failover between the commit record and visibility: the share becomes visible after
  recovery with an old `stored_at`.
- (c) A database clock step backwards.
- If production uses synchronous replication (Azure HA), the wait for the standby sits inside the
  commit segment; it holds only if `statement_timeout` actually cancels that wait, and a cancel there
  makes the commit visible anyway (D-PG-VERSION / HA, pending Sachin; not blocking).

Detection: the overrun counter (R6) covers (a) for transactions the client sees complete. Consumer
reconciliation (re-pull from an older cursor, R8) is the only cover for (b).

**PostgreSQL 17.** `transaction_timeout` would make the first segment server-enforced. The production
version is unknown (001 research R2); nothing in 003 depends on it.

**Alternatives considered.** R5.

---

## R5. Watermarks that were not adopted

- **Advisory-lock watermark** (`pg_advisory_xact_lock(K, stored_seq)` in the store transaction; the
  reader returns seqs below the lowest one held). Racy as specified: the number is taken by the insert,
  and the lock can only follow it, even in a trigger. A reader that reads `pg_locks` in that window
  sees neither the lock nor the row and moves past it. Closing it needs an allocation mutex (a shared
  session lock around `nextval`, an exclusive one for the reader); session locks survive rollback and
  leak on pooled connections. More failure surface than the lag, and still holes (b) and (c).
- **`pg_current_snapshot()` / `xmin`.** Racy for a `stored_seq` cursor. The row's `xmin` is unusable
  (youth propagation and the sweep rewrite rows; 001 research), so it would need a fixed `stored_xid`
  column. Even then xid order is not seq order. Counterexample: X (xid 400) waits on the day lock while
  W (xid 500) takes seq 101; X then takes seq 102 and commits; the snapshot's xmin is 500, which is
  above 400, so X's row 102 is returned while W's 101 is still open. Only correct if the cursor itself
  is the xid, which changes the public contract.
- **`pg_stat_activity`** (bound = the earliest `xact_start` of other client backends). Plausibly
  correct but not proven: it assumes `xact_start` is published before the transaction takes anything
  and cleared only after it ends (not checked in the PostgreSQL source), and that the app role can see
  other sessions' rows. Any long transaction anywhere in the database (the sweep, a report, `pg_dump`)
  holds the feed back. Kept as a possible later improvement with a dedicated race test if a 110 s delay
  becomes a problem.

None is both proven and simpler than the lag; all share holes (b) and (c).

---

## R6. Checking the lag, and the overrun counter

**Decision.** `ReadApiConfig` checks at start: lag ≥ `transaction-timeout` + 2 × `statement-timeout` +
`idle-in-transaction-timeout` (this pod's `resultsstore.intake.store.*`), and lag ≤ 10 minutes. When the
lag is not set, it defaults to that sum; if the sum is above 10 minutes the error names
`resultsstore.intake.store.transaction-timeout`, so an operator who changed only the transaction
timeout is told what to change. `Rules` gains a four-argument `atLeast(name, value, boundName, bound)`
overload beside the existing three-argument one.

**The check is per process.** The writers are whichever pods have the subscription on. With read-only
pods, a rolling deploy that raises an intake timeout, or two deployments with different values, the
reader checks against its own values while writers hold numbers longer. So
`contracts/configuration.md` states: every pod shares `resultsstore.intake.store.*`; raise the lag
before raising any intake timeout; lower the timeouts before lowering the lag (D-READONLY-PODS, pending
Sachin).

**The runtime check (D-OVERRUN, pending Sachin).** Intake counts `resultsstore.intake.visibility.overrun`
when a store transaction's time from sending the share insert to its commit returning is at or above
the lag. The send happens before the number is taken and the return after the commit, so the measure
can only over-state the time the proof needs: it may give a false alarm, never miss a completed
overrun. `JdbcShareStore` measures it on an injected nanosecond clock and returns it on
`StoreResult.Stored`; `IntakeService` compares it with the threshold and calls
`IntakeObserver.visibilityOverrun()`; only a `Stored` result counts (a duplicate or refused copy
inserted nothing visible). Limitation: a commit the client never sees returning (connection lost during
`COMMIT`) is not counted.

Until T009 lands the read settings, `IntakeConfig` passes the derived default from `IntakeProperties`
as the threshold (T008); T009 switches it to the effective lag.

**Alternatives considered.** Writers recording their effective bound in a database row that readers
check: closes the per-process gap, but adds a table and a write per pod; deferred unless
D-READONLY-PODS says such deployments exist.

---

## R7. Where the cursor goes next, and `visibleUpTo`

**Decision.** The pull response is `{ items, nextStoredAfterSeq, hasMore, visibleUpTo }`. The store
reads `limit + 1` rows, so `hasMore` is exact.

- `hasMore` true: `nextStoredAfterSeq` is the last item's `storedSeq`.
- `hasMore` false: `nextStoredAfterSeq` is `max(storedAfterSeq, S)` (R4), so a filter that matches
  nothing in a range still moves the cursor, and a consumer can tell it has caught up.
- `visibleUpTo` is `now() − lag` from the same statement, as an ISO UTC instant. Contract: *every share
  stored at or before `visibleUpTo` with `storedSeq` at or below `nextStoredAfterSeq` has been
  presented* (if it matched the filters at the time). Every such share is at or below `S` by
  definition of `S`. YOT's nightly job waits for `hasMore` false with `visibleUpTo` at or after 18:00.

**Rationale.** Without the high-water value a court- or `true`-filtered consumer rescans every
non-matching visible row from its old cursor on every poll and never knows it has caught up. Without
`visibleUpTo` YOT's 18:00 barrier (its redesign) has nothing to wait on.

---

## R8. What a consumer may rely on: read-time filters and rows that change in place

**Decision.** Pull reads current column values. Rows change in place in these ways, none of which gives
a new `storedSeq`:

- `day_youth_seen` is recomputed for every share of the day when a share is stored (`TRUE` if any
  share is `TRUE`, else `NULL` if any is unknown, else `FALSE`; 001 research), and when the sweep fills
  a `FAILED` row;
- the sweep fills `keyDetails`, `anySubjectIsYouth` and `projectionStatus` on `FAILED` rows;
- `isLatest` and `predecessorShareId` move when a share is stored;
- a spec-004 rerun may rewrite key details in place (004 ruling C5: `NULL`→`FALSE`/`TRUE` written,
  `FALSE`→`TRUE` held, D-YOUTH-RAISE). Items expose `projectionVersion` and `projectedAt` so a consumer
  can see that a row was re-extracted.

Consequences written into `contracts/read-api.md` as normative text:

- `dayYouthSeen=notFalse` is complete for youth-relevant days: a day goes from `FALSE` to `TRUE` only
  through a new share (higher `storedSeq`), and `NULL` days are already visible.
- `dayYouthSeen=true` and `courtCentreId` can miss a share the sweep fills later. Hence
  D-COURT-FAILED (a court-filtered pull also returns `FAILED` rows, default yes) and D-PULL-TRUE
  (`true` kept, with the warning).
- **The unknown-row obligation**: *a share presented with `projectionStatus` `FAILED` or `dayYouthSeen`
  null is not final in its key details; re-read `GET /shares/{shareId}` until `projectionStatus` is
  `OK` (or the day's successor arrives) before deciding it is not yours.*
- An out-of-order share may not be the latest: read the day's versions. This matters for YOT's "skip
  unless latest" rule.
- Reconciliation: re-pulling from an older cursor is safe (filters at read time); it is the consumer's
  cover for hole (b) of R4. Search cannot do this (it needs a court and skips `FAILED` rows).

---

## R9. Search

**Decision.** `courtCentreId`, `sharedDayFrom` and `sharedDayTo` required; the range filters
`shared_day_london` (the register day), both ends included, at most 31 days; `dayYouthSeen` accepts
`notFalse`, `true` and `false`; `latestOnly` adds `is_latest`. Order: `shared_day_london`, `shared_at`,
`share_id`, ascending, which is the same as (`shared_at`, `share_id`) because the London day is a
function of `shared_at`. Keyset paging on those three values; the cursor is base64url without padding of
`v1|<sharedDayLondon>|<shared_at epoch microseconds>|<shareId>`, at most 128 characters, decoded
strictly (each part checked; a different version prefix is invalid). `{ items, nextCursor }`, with
`nextCursor` null when the `limit + 1` read shows no further row. No visibility lag.

**Rationale.** Required court and range keep every search on one index range
(`hearing_share_centre_day_ix`); keyset paging is stable under inserts; the opaque cursor leaves the
store free to change its key.

**Alternatives considered.** Offset paging (unstable under inserts, slow at depth); a court-less search
(no index; no consumer asked).

---

## R10. The payload bytes and the `ETag`

**Decision.**

- The body is `String.getBytes(UTF_8)` of the text the database returns for
  `COALESCE(payload_json::text, payload_text)` (the expression `JdbcShareStore.PAYLOAD_FOR_EXTRACTION`
  already uses), written unchanged as `ResponseEntity<byte[]>` through `ByteArrayHttpMessageConverter`.
- The `ETag` is the quoted lower-case SHA-256 hex of that same byte array. `PayloadChecksum` gains a
  `sha256Hex(byte[])` overload (today it hashes a `String`).
- `Content-Type: application/json` with no charset parameter; `_metadata` kept.
- `payload_sha256` is never this endpoint's `ETag` (002 FR-041): it is over `payload_text`, and the
  working copy is not byte-identical to it.
- `server.compression` stays off: a strong `ETag` is per representation. A test asserts no
  `Content-Encoding`, a `Content-Length` equal to the byte count and no chunked encoding with the audit
  filter's response wrapper in place.

**Determinism.** `jsonb::text` is deterministic for a stored value: keys ordered by length then bytes,
duplicate keys collapsed, fixed `", "` and `": "` spacing, numbers with their stored scale. It is not
documented as stable across PostgreSQL major versions (nor across a dump and restore into one). So the
contract promises stability only within a major version, and tells consumers to verify each response
against its own `ETag` and to compare content, not bytes, across an upgrade (D-JSONB-PROMISE, pending
Sachin; probation DV-19 to be told).

**Alternatives considered.** Storing the served text and its hash once (a later migration; would make
the bytes stable for ever); `payload_sha256` as the `ETag` (wrong for the working copy).

---

## R11. `304 Not Modified`

**Decision.** The controller returns `ResponseEntity.ok().eTag(etag).body(bytes)` and lets Spring's
`HttpEntityMethodProcessor` answer `If-None-Match` (weak comparison, a list or `*` accepted): `304` with
the `ETag` and no body. No manual `checkNotModified` call: with it the `ETag` header would be written
twice (once by `checkNotModified`, once from the entity). `SharePayloadControllerTest` asserts exactly
one `ETag` header on `200` and on `304`.

**Rationale.** The store must read and hash the body anyway, so a `304` saves transfer only (about
39 KB on average, 2.4 MB at worst); it is cheap and consumers expect it. The audit library publishes no
response event for an empty body, so a `304` has a request event only; the metric outcome is
`not_modified`. Only the `ETag` is promised on a `304`; the `Results-Store-*` headers are not.

---

## R12. Headers or fields; the sharer's user id; `eventType`

**Decision.**

- The payload endpoint's body is the payload, so identity and `enrichmentApplied` travel as headers:
  `Results-Store-Share-Id`, `-Hearing-Id`, `-Hearing-Day`, `-Shared-Time`, `-Enrichment-Applied`,
  `-Payload-Form` (`working-copy` or `arrived-text`). `Cache-Control: no-store`.
- Probation S12 (a user-id flag): no flag. The body keeps `_metadata`, so the consumer reads
  `_metadata.context.user` from the same response; an absent key means no user (D-S12, pending Sachin).
- YOT needs INT versus SJP per item: no `eventType` field. `keyDetails.isSjp` is the source: `true` =
  SJP, `false` = INT, `null` = unknown while `FAILED`.
- `sharedTime` in every item is the stored `shared_at` (cut to the microsecond), not the string as sent.
  The consumer's key is `shareId` from the store (UUID v5, namespace
  `3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60`), never derived by the consumer. YOT's redesign assumed a
  v3 id from `nameUUIDFromBytes`; that is noted for the YOT team (page-notes.md).

---

## R13. Errors

**Decision.**

- One bounded shape everywhere: `{"type":"about:blank","title":<reason phrase>,"status":n,"reason":<code>}`,
  `application/problem+json` from the filters and the advice. Never `detail` or `instance`, never a
  caller value, path, exception text or payload fragment.
- `api/ProblemReason` is the single table from reason to status and code; the filters'
  `RefusalWriter` writes the same shape without Spring MVC.
- `spring.mvc.problemdetails.enabled` stays **false**. Turning it on registers Boot's own
  `ResponseEntityExceptionHandler` advice, which can render MVC exceptions with Spring's `detail` text
  and the request path as `instance`. Instead `ReadApiExceptionHandler` extends
  `ResponseEntityExceptionHandler` and overrides `handleExceptionInternal`, so every MVC exception
  becomes the four fields: `HttpRequestMethodNotSupportedException` → `405 method_not_allowed`,
  `HttpMediaTypeNotAcceptableException` → `406 not_acceptable`, `NoResourceFoundException` →
  `404 route_not_found`, any other MVC exception → its own status with reason `bad_request` (a `4xx`) or `internal_error` (a `5xx`).
  A test sends each MVC exception type and asserts the four fields.
- Our own: `BadParameterException(reason)` → `400`; `NotFoundException(reason)` → `404`;
  `DataAccessResourceFailureException`, `QueryTimeoutException`, `CannotGetJdbcConnectionException` →
  `503 store_unavailable` with `Retry-After: 5`; anything else → `500 internal_error`, logged by
  exception class with `shareId` in MDC when known, never the message.
- `/error` (where the authorisation library's `sendError` for `401` and `403` lands):
  `BoundedErrorAttributes` replaces Boot's attributes with the four fields (reason from the status:
  `unauthenticated`, `forbidden`). `server.error.whitelabel.enabled=false`; a test sends
  `Accept: text/html` too, because `BasicErrorController.errorHtml` renders a different model.
- Statuses fit YOT's `RetryPolicy`: it retries `5xx`, `408` and `429` and honours `Retry-After`; other
  `4xx` are permanent; `404` means "not held".

**Alternatives considered.** Mapping every MVC exception by hand without extending
`ResponseEntityExceptionHandler`: easy to miss one. Leaving Boot's `/error` body: it echoes the path.

---

## R14. Audit

**What the library records** (`cp-audit-filter-springboot` 1.0.5, `javap`, facts): the request event
carries request headers, query parameters and path parameters resolved from `results-store-openapi.yaml`.
The response event is built with the request's headers and the **response body**, and is published only
when the body has text. No status, no response headers. `shouldNotFilter` skips URIs containing `/health`
or `/actuator`. `HttpAuditProperties` has only `enabled` and `openapiRestSpec`: there is no
`include-payload-body` switch (the design appendix's "audit events carry no bodies" is false for 1.0.5).
The `AuditFilter` and `AuditPayloadGenerationService` beans are `@ConditionalOnMissingBean`.

| Endpoint | Response event carries |
|---|---|
| pull, search, day versions, one share | the JSON page: ids, court, youth flags, key details; up to 500 items (an estimate of 0.3 to 0.5 MB, not measured) |
| payload `200` | the whole working copy: special-category and youth data; 39 KB on average, 265 KB at the 99th percentile, 2.4 MB or more as text |
| payload `304` | nothing (empty body) |
| `400`/`404`/`500` from the advice | the bounded problem body |

**Not audited:** `401` and `403` (the library's `sendError` ends the chain before the audit filter, and
`OncePerRequestFilter` skips the error dispatch; expected, pinned by `AuditIT`), and our filters' `404`,
`405` and `415`. They are counted (`resultsstore.read.refused`). Constitution VII's "every request is
audited" becomes *every request that reaches an endpoint is audited; refusals before authorisation are
counted* (D-VII-AUDIT-WORDING, D-REFUSALS-UNAUDITED, pending Sachin).

**D-AUDIT (pending Sachin).**

1. Accept, record in the DPIA. No code; youth and special-category data copied to the audit topic and
   store, outside this service's retention; messages of several MB on Artemis.
2. Ask the library owners for a body-exclusion switch. Estate-wide fix; timeline not ours; needs an
   interim.
3. Replace the `AuditFilter` bean with a subclass that skips the payload paths, and publish a body-free
   event ourselves. Reimplements event assembly (its private methods are not reusable); two code paths.
4. Replace the `AuditPayloadGenerationService` bean with `PayloadBodyFreeAuditPayloadGenerationService`,
   whose `generatePayload(ResponseInfo)` substitutes `{"payloadOmitted":true}` for the payload routes'
   body. The library's filter, transport, request event and response event all stay; about 30 lines and
   one bean, created only when `cp.audit.enabled=true`. Depends on the public `generatePayload`
   signature and on the form of `ResponseInfo.contextPath`; `AuditIT` pins both and fails on a library
   upgrade that changes them.
5. Serve from a path the filter skips. Rejected: unaudited.

**Default applied: option 4 now, option 2 in parallel (then delete 4), recorded in the DPIA.** List
pages keep their bodies (ids and flags, no prompt values). T011 builds option 4; option 1 is its
alternative branch. `AuditIT` pins whichever is chosen.

---

## R15. Indexes (V5) and the plan tests

**Decision.** V5 adds two partial indexes, each tied to its query (full DDL in data-model.md):

- `hearing_share_youth_feed_ix (stored_seq) WHERE day_youth_seen IS NOT FALSE`: pull with
  `dayYouthSeen=notFalse` or `true` (`true` written as `day_youth_seen IS NOT FALSE AND day_youth_seen`
  so the planner can prove the partial predicate). A range scan in seq order that stops after
  `limit + 1` rows.
- `hearing_share_centre_day_ix (court_centre_id, shared_day_london, shared_at, share_id) WHERE
  court_centre_id IS NOT NULL`: search; serves the keyset order with no sort step. Partial because
  `FAILED` rows have no court and can never match `court_centre_id = :cc`.

Served by existing indexes: unfiltered pull and court-only pull (`hearing_share_stored_seq_uk`); the
visibility bound (a backward scan of `hearing_share_stored_seq_uk` that stops at the first row older
than the lag; the rows inside the lag are the tail, about six at 110 s and the observed rate); one share
(`hearing_share_pk`); the payload (`hearing_share_payload_pk`); the day's versions and the
`versionNumber` count (`hearing_share_identity_uk`).

`ReadQueriesPlanIT` (T006) runs `EXPLAIN (FORMAT JSON)` with `enable_seqscan = off` over the
`JdbcShareQueries` SQL constants themselves, not copies, so the plan test proves the shipped query.

Plain `CREATE INDEX` inside Flyway's transaction takes a `SHARE` lock on `hearing_share` and blocks
intake inserts while it builds: fine before go-live, seconds at today's volume. If V5 ships after live
capture, the fallback is `-- flyway:executeInTransaction=false` with `CREATE INDEX CONCURRENTLY`
(risks).

**Rejected.** A court-centre variant of the pull index (cannot serve the `OR projection_status =
'FAILED'` arm without a `UNION`; add after NFT if needed). A court-less search index (search requires a
court). `share_defendant(defendant_id)` (no 003 endpoint reads it).

---

## R16. Times and nulls in JSON

**Decision.** An explicit formatter (`api/InstantFormat`) writes every instant as UTC with exactly six
fraction digits (`2026-10-03T09:15:00.120000Z`); Jackson's default drops a zero fraction, which would
break consumers that compare strings. Dates are `yyyy-MM-dd`. Every field is always written; null as
`null`. The response records carry these as strings already formatted, so no global Jackson setting
changes.

---

## R17. Metrics

**Decision.** As `contracts/metrics.md`: `resultsstore.read.requests{endpoint,outcome}`,
`resultsstore.read.refused{reason}`, `resultsstore.read.duration{endpoint}`,
`resultsstore.read.page.items`, `resultsstore.read.payload.bytes`, and intake's
`resultsstore.intake.visibility.overrun`. Tag values are lower-case enum names from `domain/`, every
combination registered at start. Refusals are counted through a `RefusalObserver` port created with the
filter in T002 (so phase A needs nothing from phase B); `MicrometerRefusalObserver` (T003) and
`MicrometerReadObserver` (T005) implement the two ports.

---

## R18. Settings and constants

**Decision.** Two settings (`contracts/configuration.md`): `resultsstore.read.pull.visibility-lag`
(derived default, R6) and `resultsstore.read.statement-timeout` (5 s; > 0 and below the driver's socket
timeout, the same rule `IntakeConfig` applies to the intake statement timeout). Everything a consumer
builds against is a **constant**, not a setting, so the contract cannot drift per environment: the base
path, default and maximum `limit` (100 and 500), the search span (31 days), the cursor length (128),
`Retry-After` (5 s), the route table and the action names. The service refuses to start with
`authz.http.enabled` false unless the `test` profile is active (D-AUTHZ-REQUIRED, pending Sachin):
checked in `ApiWebConfig`. A controller that reads `CJSCPPUID` itself would still refuse a missing one;
no 003 controller does.

---

## R19. The arrived text (phase D, D-RAW)

**Decision (if D-RAW is accepted).** `GET /shares/{shareId}/payload/arrived`, its own action
`results-store.get-share-arrived-payload` and rule, body `payload_text` exactly as stored, `ETag` the
quoted `payload_sha256`. That equals the SHA-256 of the served bytes because `PayloadChecksum` hashes the
UTF-8 text and the text is served as UTF-8; the test asserts the equality. Same identity headers, with
`Results-Store-Payload-Form: arrived-text`. Audit follows the D-AUDIT choice (option 4 covers this route
too). Constitution II gains the endpoint in 2.2.0.

**Alternatives considered.** `?variant=arrived` on `/payload`: one action for two kinds of data, and the
action filter would have to read the query string. A list of enriched application ids instead: probation
would rebuild the arrived text itself.

---

## R20. Constitution 2.2.0

**Decision.** MINOR (2.1.0 → 2.2.0): materially wider guidance, no rule reversed. Principle VII:

- "The action is worked out from the request's path and method by `ActionHeaderFilter`. A
  caller-supplied `CPP-ACTION` header is never trusted for a mapped path." becomes: *The action is
  derived from method and path for every request; a `CPP-ACTION` header or vendor media type sent by the
  caller is overridden; a path under the service that cannot be mapped is refused.*
- "Every read-API action's rule admits the "System Users" group." becomes: *Every read-API action's
  rule admits the "System Users" and "Second Line Support" groups and matches its route's method and
  path.*
- "Every request is audited by `cp-audit-filter-springboot`, with the library's default settings."
  becomes: *Every request that reaches an endpoint is audited by `cp-audit-filter-springboot`; refusals
  before authorisation are counted. The payload endpoints' response body is replaced in the audit event
  by a fixed marker.* (The last sentence only under D-AUDIT option 4.)

Principle II, only if D-RAW: "The read API serves the working copy, and the text when the working copy
is empty." gains *, and, on its own endpoint, the text as it arrived, with its checksum as the `ETag`*.

The Sync Impact Report records the change; spec 004 bumps to 2.3.0 for its own Principle I change.

---

## R21. The day's versions

**Decision.** `GET /hearings/{hearingId}/days/{hearingDay}/shares` returns `{ items }` in `shared_at`
order, unpaged; `versionNumber` is `row_number()` over `shared_at`; an empty day is
`404 hearing_day_not_found` (D-DAY-404; YOT's client treats `404` as "not held"). Unpaged because a
day's chain is small; the largest in production is not measured (risks).

---

## R22. Build traps

- **PMD `OnlyOneReturn`** (enforced on main code, `.github/pmd-ruleset.xml`): the natural shapes break
  it: filters that return after writing a refusal, the decoder of `SearchCursor` rejecting each bad
  part, `DayYouthFilter.fromValue`, `ProblemReason` look-ups. Each uses single-exit style or a site
  suppression with a reason; tasks say so.
- **JaCoCo** (0.88 line, 0.85 branch, `config/**` excluded): branching logic stays out of `config/`
  (`ApiRoute`, the filters, `ShareReadService`, `InstantFormat`), so the gate measures it.
- **The `test` profile** keeps `authz.http.enabled`, `audit.http.enabled` and `cp.audit.enabled` off;
  `ReadApiIT` and `AuditIT` switch them on for themselves.
- **Spec 004** is authored from this branch's tip and edits `ApiRoute`, `ActionHeaderFilter`, the DRL
  and the OpenAPI document. 003 lands first.
