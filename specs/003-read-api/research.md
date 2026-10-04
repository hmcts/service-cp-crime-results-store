# Research: Read API

**Feature**: `003-read-api` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

Each entry gives the decision, why, and what else was looked at. R1 to R3 settle the web edge (layering,
routes, rules); R4 to R8 the pull and what a consumer may rely on; R9 to R12 search, the payload and its
headers; R13 and R14 errors and audit; R15 to R18 the schema, times, metrics and settings; R19 the
arrived text; R20 the constitution; R21 and R22 the day's versions and the build traps; R23 the contract
repository the service takes its OpenAPI contract from. Every open point
is settled here, or is a decision in spec.md *Decisions taken with Sachin (2026-10-03)*; the production
PostgreSQL version stays a risk (R4).

Sources: the design review of spec 003 and its critique; the orchestrator's rulings on both
(2026-10-03), which win where they differ; the decisions taken with Sachin (2026-10-03, rulings section
E), which win over everything; the fact-finding reports on the store's read side (this
repository at `c21a901`, including `javap` of `cp-auth-rules-filter` 1.0.7 and
`cp-audit-filter-springboot` 1.0.5) and on YOT as the first consumer, with probation's asks; specs 001
and 002; and this repository's code as read for this document (`V3__create_share_store.sql`,
`V4__projection_tried_at.sql`, `JdbcShareStore`, `IntakeConfig`, `IntakeProperties`, `Rules`,
`IntakeObserver`, `application.yaml`, `application-test.yaml`).

---

## R1. Layering and wiring

**Decision.** As `.claude/rules/design_rules.md` sets out:

- `api/` holds the one controller, which implements the generated `SharesApi` of the contract jar
  (R23), the strict parameter checks that run before binding, and the mapping of each answer to the
  generated models or a bounded problem body (`ShareReadService` is called once per request); the exception
  advice; the bounded `/error` attributes; the instant format.
- `application/` holds `ShareReadService` and the ports `ShareQueries`, `ReadObserver` and
  `RefusalObserver`. Nothing there imports JDBC or HTTP types. One accepted exception to the direction
  of dependencies: `BadParameterException` and `NotFoundException` carry an `api/ProblemReason`, so
  `application/` imports one enum from `api/` (it holds each status as a plain `int`, so no HTTP type
  comes with it). Kept on purpose: `ProblemReason` is the single table
  from reason code to status, and a separate code-only enum in `domain/` would be a second list that
  must stay in step with it. The design rules forbid only JMS, JDBC and HTTP-client imports in
  `application/`, so no rule is broken.
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
database clock, in the same statement as the page. The default lag is 90 seconds (D-LAG-VALUE, E3). To
make 90 s provable, 003 lowers two intake defaults (spec 001 settings): `statement-timeout` 20 s → 10 s and
`lock-timeout` 10 s → 5 s; `transaction-timeout` (60 s) and `idle-in-transaction-timeout` (10 s) stay. A V5
`BEFORE INSERT` trigger sets `stored_at := clock_timestamp()`.

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
PostgreSQL or by Spring before a statement, none by the driver's client-side cancel; values are the new
defaults):

| Segment | Bound (default) | Enforced by |
|---|---|---|
| Every statement starts before the Spring deadline | `transaction-timeout` (60 s) from the start | the `TransactionTemplate` timeout (`IntakeConfig.java:91-92`); `StoreTimeoutIT` proves the give-up |
| The last statement, started just before the deadline | `statement-timeout` (10 s) | PostgreSQL `statement_timeout`, set per transaction (`JdbcShareStore.SET_TIMEOUTS`). A lock wait inside it ends sooner (`lock_timeout` 5 s ≤ statement) |
| The JVM's gap before `COMMIT` | `idle-in-transaction-timeout` (10 s) | PostgreSQL ends the session and aborts the transaction |
| `COMMIT`, with the deferred constraint triggers | `statement-timeout` (10 s) | PostgreSQL applies `statement_timeout` to `COMMIT` (design review; not re-checked against the PostgreSQL source) |

Sum: 60 + 10 + 10 + 10 = **90 s** = transaction + 2 × statement + idle-in-transaction. The rule the
service checks at start is therefore lag ≥ transaction + 2 × statement + idle-in-transaction, with
lock ≤ statement kept from 001 (a lock wait is inside a statement, so it adds nothing to the sum).

Why not 91 s at the old timeouts: the design review replaced the last-statement row with "1 s past the
deadline", which is the JDBC query timeout that pgjdbc enforces on the client by sending a cancel over a
new connection. A GC pause, a failed cancel connection or a pooler that does not route cancel keys lets
the statement run to the server's limit, so a client-side cancel is never part of the bound. Sachin chose
to reach 90 s by lowering the server-side limits instead (E3).

**The pool backstop (E3).** Every connection the pool opens also starts with `SET statement_timeout`
equal to the intake statement timeout (Hikari's connection-init SQL, FR-062). It covers the read API and
anything that forgets its own limit. It is not part of the proof: the store transaction's own
`set_config(..., true)` values are what the chain above rests on, and they are set inside the
transaction whatever the session default is. The init SQL is built in Java from the bound `Duration`
(`SET statement_timeout = '<n>ms'`), not written in `application.yaml` from the same environment
variable, because Spring and PostgreSQL read duration strings differently (`1m` is a minute to Spring
and an error to PostgreSQL; `PT10S` is valid only to Spring); an init SQL PostgreSQL rejects would fail
every connection.

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
  makes the commit visible anyway (D-PG-VERSION / HA, E11: unknown, a recorded risk, not blocking).

Detection: the overrun counter (R6) covers (a) for transactions the client sees complete. Consumer
reconciliation (re-pull from an older cursor, R8) is the only cover for (b).

**PostgreSQL 17.** `transaction_timeout` would make the first segment server-enforced. The production
version is unknown (001 research R2; E11); nothing in 003 depends on it. It is a later tightening.

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
  holds the feed back. Kept as a possible later improvement with a dedicated race test if a 90 s delay
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
`contracts/configuration.md` states: every pod runs the same `resultsstore.intake.store.*` values (no
read-only pods, no pods with other timeouts: D-READONLY-PODS = no, E10); raise the lag before raising any
intake timeout; lower the timeouts before lowering the lag. The 003 change itself lowers timeouts and the
lag together. That is safe only because the read API is new: no consumer pulls until every pod runs
003, so no new pod's 90 s lag is ever checked against an old pod's 20 s statement timeout. Any later
change follows the rollout order.

**The runtime check (D-OVERRUN = yes, E4).** Intake counts `resultsstore.intake.visibility.overrun`
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
check: closes the per-process gap, but adds a table and a write per pod; not needed, because no such
deployment exists (E10).

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
- `dayYouthSeen=true` and `courtCentreId` can miss a share the sweep fills later. `true` is kept with the
  warning (D-PULL-TRUE). A court-filtered pull is an exact match and never returns a `FAILED` row
  (D-COURT-FAILED = no, E5): a share whose court the sweep fills in later is behind the cursor and is not
  presented to it. A consumer that needs completeness uses the unfiltered pull or `notFalse` and filters
  by court itself.
- **The unknown-row obligation**: *a share presented with `projectionStatus` `FAILED` or `dayYouthSeen`
  null is not final in its key details; re-read `GET /shares/{shareId}` until `projectionStatus` is
  `OK` (or the day's successor arrives) before deciding it is not yours.* Such shares come from the
  unfiltered pull and from `notFalse`; a court-filtered pull never presents a `FAILED` one.
- An out-of-order share may not be the latest: read the day's versions. This matters for YOT's "skip
  unless latest" rule.
- Reconciliation: re-pulling from an older cursor is safe (filters at read time); it is the consumer's
  cover for hole (b) of R4. Search cannot do this (it needs a court and skips `FAILED` rows).

---

## R9. Search

**Decision (E6).** The pull stays on `storedSeq`, the only complete cursor. Search requires
`courtCentreId` and exactly one of two range forms:

- **Day form**: `sharedDayFrom` and `sharedDayTo`, London register days, both ends included, at most 31
  days counting both ends (`day_range_too_long`, `day_range_reversed`, `invalid_shared_day`).
- **Time form**: `sharedFrom` and `sharedTo`, instants on `shared_at`, half-open [`sharedFrom`,
  `sharedTo`), `sharedTo` after `sharedFrom` and at most 31 days (P31D) after it (`time_range_too_long`,
  `time_range_reversed`). Each is ISO-8601 UTC with `Z` and at most six fraction digits; anything else is
  `invalid_shared_from` or `invalid_shared_to`. Six digits because `shared_at` holds microseconds.
- A parameter of each form in one call is `conflicting_parameters`; neither form complete is
  `missing_parameter`.

The service turns the day form into the time form in Java: `domain/SharedDays` gives London midnight at
the start of `sharedDayFrom` and London midnight at the start of the day after `sharedDayTo`
(`Europe/London`, so a 23- or 25-hour day at a clock change is covered exactly). Because
`shared_day_london` is the London date of `shared_at`, this selects exactly the rows the old
`shared_day_london BETWEEN` filter selected. `JdbcShareQueriesIT` proves the equality for a 00:30 BST
share and for days at both clock changes.

`dayYouthSeen` accepts `notFalse`, `true` and `false`; `latestOnly` adds `is_latest`. Order: `shared_at`,
then `share_id`, ascending, in both forms. Keyset paging on those two values; the cursor is base64url
without padding of `v1|<shared_at epoch microseconds>|<shareId>` (signed: negative before 1970, as
`Long.toString` writes it), at most 128 characters, decoded
strictly (each part checked; a different version prefix is invalid). `{ items, nextCursor }`, with
`nextCursor` null when the `limit + 1` read shows no further row. No visibility lag.

**Index.** One index serves both forms: `hearing_share_centre_shared_at_ix (court_centre_id, shared_at,
share_id) WHERE court_centre_id IS NOT NULL` (R15). It replaces the planned
`hearing_share_centre_day_ix (court_centre_id, shared_day_london, shared_at, share_id)`: with the day
range turned into instants the day column adds nothing, and an index that led with the day could not
serve the time form without a sort step. One index is smaller and keeps one plan for search.

**Rationale.** YOT's 18:00 cross-check wants the shares shared up to a moment, which the day form cannot
say. A required court and range keep every search on one index range; keyset paging is stable under
inserts; the opaque cursor leaves the store free to change its key.

**Alternatives considered.** A shared-time cursor on pull (incomplete: a share stored late with an early
`sharedTime` would be behind it); offset paging (unstable under inserts, slow at depth); a court-less
search (no index; no consumer asked); two indexes, one per form (twice the write cost for one query
shape).

---

## R10. The payload bytes and the `ETag`

**Decision.**

- **No `_metadata` (E8).** No legacy CP query service returns the message envelope, so the store does
  not either. Nothing stored changes; only what is served omits it.
- **Working copy.** The query returns `(payload_json - '_metadata')::text`: the jsonb `-` operator removes
  the top-level key in the database, and the result is written as text exactly as for the whole copy.
  The body is `String.getBytes(UTF_8)` of that text, written unchanged as `ResponseEntity<byte[]>`
  through `ByteArrayHttpMessageConverter`. The generated `SharesApi.getSharePayload` declares that
  return type once the contract change of R23 (C3) is released; never a `String`, a `Map` or a
  `Resource`.
- **Text fallback** (`payload_json` is NULL, for example a `\u0000` escape jsonb cannot hold). The query
  returns `payload_text`; `domain/EnvelopeMetadata` parses it with Jackson 3 (which can hold `\u0000`),
  removes the top-level `_metadata` member and writes the tree back as compact JSON text. Numbers are
  read as exact decimals so no digit is lost; key order is kept; spacing and escape forms are Jackson's.
  Every stored share's text was parsed as JSON at intake (001 FR-008), so the parse cannot fail in
  practice; if it ever does, the answer is `500 internal_error`, never the text with `_metadata`.
- The `ETag` is the quoted lower-case SHA-256 hex of the byte array served, in every case.
  `PayloadChecksum` gains a `sha256Hex(byte[])` overload (today it hashes a `String`).
- `Content-Type: application/json` with no charset parameter.
- `payload_sha256` is never an `ETag` (002 FR-041): it is over `payload_text`, `_metadata` included, and
  no served body is byte-identical to that text. This holds for the arrived endpoint too (R19).
- The `Results-Store-*` headers stay: they are the store's own facts, not the envelope's.
- `server.compression` stays off: a strong `ETag` is per representation. A test asserts no
  `Content-Encoding`, a `Content-Length` equal to the byte count and no chunked encoding with the audit
  filter's response wrapper in place.

**Determinism.** `jsonb::text` is deterministic for a stored value: keys ordered by length then bytes,
duplicate keys collapsed, fixed `", "` and `": "` spacing, numbers with their stored scale. It is not
documented as stable across PostgreSQL major versions (nor across a dump and restore into one). So the
contract promises stability only within a major version, and tells consumers to verify each response
against its own `ETag` and to compare content, not bytes, across an upgrade (D-JSONB-PROMISE, E7;
probation DV-19 to be told). The texts the service writes itself (the fallback and the arrived endpoint)
are deterministic for one Jackson version; the contract says a library upgrade may change their bytes in
the same way.

**Alternatives considered.** Storing the served text and its hash once (a later migration; would make
the bytes stable for ever); `payload_sha256` as the `ETag` (wrong for every served body); stripping
`_metadata` from the working copy in Java as well (one more parse per read for no gain: the jsonb `-`
operator does it in the query).

---

## R11. `304 Not Modified`

**Decision.** The controller returns `ResponseEntity.ok().eTag(etag).body(bytes)` (a `byte[]`, R23 C3) and lets Spring's
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
- Probation S12 (a user-id flag): not addressed in 003 (E9). The store exposes no envelope metadata;
  whether a message carried a user id is not served. It is a consumer concern for the probation design.
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
  `401` `unauthenticated`, `403` `forbidden`, any other `4xx` `bad_request`, any `5xx`
  `internal_error`). Boot's `BasicErrorController` has a second handler, `errorHtml`, for
  `Accept: text/html`; with the white-label page off it resolves no view, and the answer would be
  undefined. So the service registers its own `api/BoundedErrorController` (an `ErrorController` bean;
  Boot's backs off): one handler at `/error` for every media type, which writes the four fields as
  `application/json`. A `text/html` caller gets the same JSON body. The controller also counts a `401`
  as `unauthenticated` and a `403` as `forbidden` in `resultsstore.read.refused`, because the error
  dispatch is the one place this service sees them. `server.error.whitelabel.enabled=false` stays as a
  second guard; a test sends `Accept: text/html`, `application/json` and no `Accept`.
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
| payload `200` | without option 4, the whole served body: special-category and youth data; 39 KB on average, 265 KB at the 99th percentile, 2.4 MB or more as text. With option 4 (decided), the marker |
| payload `304` | nothing (empty body) |
| `400`/`404`/`500` from the advice | the bounded problem body |

**Not audited:** `401` and `403` (the library's `sendError` ends the chain before the audit filter, and
`OncePerRequestFilter` skips the error dispatch; expected, pinned by `AuditIT`), and our filters' `404`,
`405` (refused before authorisation) and `415` (refused after authorisation, before audit). None of
them reaches the audit filter. They are counted (`resultsstore.read.refused`: our filters count their
own; `BoundedErrorController` counts `401` and `403`). Constitution VII's "every request is audited"
becomes *every request that reaches an endpoint is audited; a request refused by a filter or by
authorisation is counted* (D-VII-AUDIT-WORDING and D-REFUSALS-UNAUDITED accepted, E13). The rulings'
text said "refusals before authorisation are counted"; it is reworded because a `415` and a `403` are
not refused before authorisation.

**D-AUDIT (decided, E1).**

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

**Decision: option 4 now, option 2 in parallel (then delete 4), recorded in the DPIA (E1).** List
pages keep their bodies (ids and flags, no prompt values). T011 builds option 4 and `AuditIT` pins it.

---

## R15. Indexes (V5) and the plan tests

**Decision.** V5 adds three partial indexes, each tied to its query (full DDL in data-model.md):

- `hearing_share_youth_feed_ix (stored_seq) WHERE day_youth_seen IS NOT FALSE`: pull with
  `dayYouthSeen=notFalse` or `true` (`true` written as `day_youth_seen IS NOT FALSE AND day_youth_seen`
  so the planner can prove the partial predicate). A range scan in seq order that stops after
  `limit + 1` rows.
- `hearing_share_centre_feed_ix (court_centre_id, stored_seq) WHERE court_centre_id IS NOT NULL`: pull
  with `courtCentreId` (with or without a youth filter). **Now justified (E5)**: the court arm is a plain
  equality, so one index range scan returns that court's rows in `stored_seq` order and stops after
  `limit + 1`. Without it, a court pull from an old cursor walks `hearing_share_stored_seq_uk` through
  every court's rows (about 150 courts) to find a page of one court's rows, which a catch-up over weeks
  could push past the 5 s read timeout. The cost is one small index written on insert.
- `hearing_share_centre_shared_at_ix (court_centre_id, shared_at, share_id) WHERE court_centre_id IS NOT
  NULL`: both search forms (R9); serves the keyset order with no sort step. Partial because `FAILED` rows
  have no court and can never match `court_centre_id = :cc`.

Served by existing indexes: unfiltered pull (`hearing_share_stored_seq_uk`); the
visibility bound (a backward scan of `hearing_share_stored_seq_uk` that stops at the first row older
than the lag; the rows inside the lag are the tail, about five at 90 s and the observed rate); one share
(`hearing_share_pk`); the payload (`hearing_share_payload_pk`); the day's versions and the
`versionNumber` count (`hearing_share_identity_uk`).

`ReadQueriesPlanIT` (T006) runs `EXPLAIN (FORMAT JSON)` with `enable_seqscan = off` over the
`JdbcShareQueries` SQL constants themselves, not copies, so the plan test proves the shipped query.

Plain `CREATE INDEX` inside Flyway's transaction takes a `SHARE` lock on `hearing_share` and blocks
intake inserts while it builds: fine before go-live, seconds at today's volume. If V5 ships after live
capture, the fallback is `-- flyway:executeInTransaction=false` with `CREATE INDEX CONCURRENTLY`
(risks).

**Rejected.** A search index that leads with `shared_day_london` (cannot serve the time form without a
sort; R9). A court-less search index (search requires a court). `share_defendant(defendant_id)` (no 003
endpoint reads it).

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
`MicrometerReadObserver` (T005) implement the two ports. `requests` and `duration` are recorded by a
handler interceptor (`api/ReadMetricsInterceptor`, T010) when the request completes, from the route the
action filter matched and the response status, so a `304` decided by Spring after the controller returns
is counted `not_modified`, and an exception mapped by the advice is counted by its status; the service
records page sizes and payload bytes. The service never catches an exception to count it.

---

## R18. Settings and constants

**Decision.** Two settings (`contracts/configuration.md`): `resultsstore.read.pull.visibility-lag`
(derived default, R6) and `resultsstore.read.statement-timeout` (5 s; > 0 and below the driver's socket
timeout, the same rule `IntakeConfig` applies to the intake statement timeout). 003 also changes two
intake defaults (statement 10 s, lock 5 s; R4) and adds the pool backstop (R4). Everything a consumer
builds against is a **constant**, not a setting, so the contract cannot drift per environment: the base
path, default and maximum `limit` (100 and 500), the search span (31 days, both forms), the cursor length (128),
`Retry-After` (5 s), the route table and the action names. The service refuses to start with
`authz.http.enabled` false unless the `test` profile is active (D-AUTHZ-REQUIRED = yes, E12):
checked in `ApiWebConfig`. A controller that reads `CJSCPPUID` itself would still refuse a missing one;
no 003 controller does.

---

## R19. The arrived text (phase D, D-RAW accepted)

**Decision (E2, E8).** `GET /shares/{shareId}/payload/arrived`, its own action
`results-store.get-share-arrived-payload` and rule. The body is `payload_text` parsed with Jackson 3,
`_metadata` removed and written back as JSON text (`domain/EnvelopeMetadata`, the same code as the
`/payload` fallback): the text as it arrived, without the envelope metadata. No application results are
added. The `ETag` is the quoted SHA-256 of the bytes served. It is **not** `payload_sha256`: that checksum
is over the text with `_metadata`, so it never equals a hash of this body; a test asserts they differ.
Same identity headers, with `Results-Store-Payload-Form: arrived-text`; `304` as for `/payload`. Audit
option 4 covers this route too. Constitution II gains the endpoint in 2.2.0, written by T012 (R20); T013
touches no constitution.

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
  becomes: *Every request that reaches an endpoint is audited by `cp-audit-filter-springboot`; a
  request refused by a filter or by authorisation is counted. The payload endpoints' response body is replaced in the audit event
  by a fixed marker.* (E1, E13)

Principle II: "The read API serves the working copy, and the text when the working copy is empty."
becomes *The read API serves the working copy without the message envelope's metadata (`_metadata`), and
the text, likewise without it, when the working copy is empty* (E8), and gains *, and, on its own
endpoint, the text as it arrived, likewise without the envelope metadata* (E2). Both are written in 2.2.0
by T012, so 2.2.0 is never edited twice and T013 touches no constitution.

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

---

## R23. The contract repository (`api-cp-crime-results-store`)

**Decision.** The read API's OpenAPI contract is published from its own repository,
`hmcts/api-cp-crime-results-store`, as the jar `uk.gov.hmcts.cp:api-cp-crime-results-store:<version>`.
The service takes it the way `service-cp-crime-hearing-results-validator` takes
`api-cp-crime-hearing-results-validator`: an `apiSpec` configuration that `implementation` extends,
`gradle/apispec-validation.gradle` applied, and a `validateApiSpecVersions` job in `ci-released.yml`.

- **What the jar holds.** The generated Spring interface `uk.gov.hmcts.cp.resultsstore.openapi.api.SharesApi`
  (interface only, `useBeanValidation` off); the models `uk.gov.hmcts.cp.resultsstore.openapi.model`
  `ShareSummary`, `KeyDetails`, `PullPage`, `SearchPage`, `DayVersions`, `ProblemDetail` and
  `PullOrSearchShares200Response` (an interface `PullPage` and `SearchPage` implement; `date-time` is
  `java.time.Instant`; no `@JsonInclude(NON_NULL)`); the spec once, at `openapi/openapi-spec.yml`;
  `META-INF/CHANGELOG.md` and the SBOM.
- **Where it is published.** Azure Artifacts `hmcts-lib`
  (`https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/maven/v1`, anonymous read; already in
  this service's `gradle/repositories.gradle`, no credentials) and GitHub Packages.
- **Versions.** A push to the api repo's `main` publishes the draft `0.2.0-<sha7>`; a push to `team/rs`
  publishes `rs-<sha7>`; a GitHub Release `vX.Y.Z` publishes `X.Y.Z`. The first drafts, from api commit
  `2c5bc08` (2026-10-04): `0.2.0-2c5bc08` (main) and `rs-2c5bc08` (`team/rs`). The payload change of C3
  followed as api commit `69080b1` (`rs-69080b1`, pinned by T009 for phases A to C), and the arrived-text
  operation as api commit `a33c5ec` (`rs-a33c5ec`, pinned by T013). The service stays on `rs-a33c5ec` until
  the one release `0.2.0`, made after phase D and covering all five operations.

The rulings (C1 to C5; lettered so they do not clash with the research numbers):

- **C1. The service keeps its own `src/main/resources/results-store-openapi.yaml`.** It sits in
  `BOOT-INF/classes` under a name no other jar uses, so the audit filter's suffix glob
  (`audit.http.openapi-rest-spec`, unchanged) finds exactly one document, and no resource has to be read
  from the root of a nested jar. A build-time drift test, `api/OpenApiContractDriftTest`, proves it says
  the same as the jar's `openapi/openapi-spec.yml`: both parsed with swagger-parser (already a test
  dependency), then `paths` (operations, parameters, request bodies, responses and their headers and
  content), `components` (schemas, parameters, responses, headers) and `tags` compared as parsed objects.
  `info` and `servers` are not compared: CI rewrites `info.version`, the api repo sets `servers` and adds
  `info.contact` and `info.license`. On 2026-10-04 the two documents were equal in everything but `info`
  and `servers` (checked against api commit `2c5bc08`).
- **C2. The jar ships the spec once.** Until `2c5bc08` it also carried a copy at its root named
  `results-store-openapi.yaml`; two documents with that name would break the "exactly one document" check
  of `OpenApiDocumentTest`, so the root copy was removed (api commit `2c5bc08`, "build: ship the spec
  once, under openapi/").
- **C3. The controller implements `SharesApi`.** The service maps its read types to the generated
  models. The generated operations (from `rs-2c5bc08`):

  | Operation | Generated signature |
  |---|---|
  | pull and search | `ResponseEntity<PullOrSearchShares200Response> pullOrSearchShares(Long storedAfterSeq, Integer limit, String dayYouthSeen, UUID courtCentreId, LocalDate sharedDayFrom, LocalDate sharedDayTo, Instant sharedFrom, Instant sharedTo, Boolean latestOnly, String cursor)`; `limit` has `defaultValue = "100"`, `latestOnly` `defaultValue = "false"`; the dates `@DateTimeFormat(iso = DATE)`, the instants `@DateTimeFormat(iso = DATE_TIME)`; all optional |
  | one share | `ResponseEntity<ShareSummary> getShare(UUID shareId)` |
  | payload | `ResponseEntity<Map<String, Object>> getSharePayload(UUID shareId, String ifNoneMatch)` (`If-None-Match` optional) |
  | day versions | `ResponseEntity<DayVersions> listHearingDayShares(UUID hearingId, LocalDate hearingDay)` (`@DateTimeFormat(iso = DATE)`) |

  Every mapping is `@RequestMapping(method = GET, value = SharesApi.PATH_…, produces = { "application/json", "application/problem+json" })`.

  What follows from reading it:

  - **The payload signature changes first, in the api repo.** `ResponseEntity<Map<String, Object>>`
    cannot serve the stored bytes: a `Map` would be parsed and written again by Jackson (spacing, key
    order and number forms change, so the `ETag` would no longer be the SHA-256 of the body), and
    returning `byte[]` under that signature needs an unchecked cast whose converter choice depends on the
    converter order. The 200 response's `application/json` schema becomes `type: string`,
    `format: binary` (the description kept), and the api repo's generator maps `file` to `byte[]`
    (`typeMappings` gains `"file": "byte[]"`). Tried locally on 2026-10-04: the generator then gives
    `ResponseEntity<byte[]> getSharePayload(UUID shareId, String ifNoneMatch)`, written by
    `ByteArrayHttpMessageConverter`. `format: binary` without the mapping gives
    `ResponseEntity<org.springframework.core.io.Resource>`, rejected: for a `Resource` body Spring sets
    `Accept-Ranges: bytes` and answers a `Range` header with `206` and part of the body, so the SHA-256 of
    a body would no longer equal the `ETag`. `format: byte` is rejected too: it means base64 text. The
    service's own document follows the same change in the same task (C1).
  - **One controller, not three.** `@RequestMapping` on an interface's default methods is inherited by
    every class that implements it, so three controllers that each implemented `SharesApi` would each
    register all four mappings and the context would fail with ambiguous mappings. One
    `api/SharesController implements SharesApi` overrides every operation and stays thin; the per-endpoint
    work sits in plain classes it calls (`api/ShareResponseMapper`, `api/PayloadResponses`). The test
    classes stay per endpoint.
  - **The generated parameters are typed, the contract's checks are on the text.** Spring binds `UUID`
    with `UUID.fromString` (which takes non-canonical forms such as `1-1-1-1-1`), `Instant` with ISO
    date-time (which takes offsets other than `Z` and nine fraction digits), `Boolean` from `yes`, `on`
    or `1`, and it never sees an unknown or repeated parameter. So the strict checks of
    contracts/read-api.md §2.2 run on the raw request before binding, in
    `api/ShareParametersInterceptor` (a `HandlerInterceptor`: its `preHandle` runs after the handler is
    chosen and before the arguments are resolved; it reads the query string and the URI template
    variables). It refuses with the bounded `ProblemReason` body and never echoes a value. A value that
    passes it always binds; as a backstop, `ReadApiExceptionHandler` maps a
    `MethodArgumentTypeMismatchException` to the parameter's own reason (`invalid_share_id`, …), not to
    `bad_request`.
  - **Times.** The models hold `Instant`; the contract writes six fraction digits and `Z`. A Jackson
    serializer for `Instant` backed by `api/InstantFormat` is registered on the MVC JSON mapper.
- **C4. Every API change is api-repo-first.** A pull request to the api repo; on merge a `team/rs` draft
  (`rs-<sha7>`); the service pins that draft and builds against it; the service change merges; a GitHub
  Release `vX.Y.Z` of the api repo; the service bumps to `X.Y.Z`. `validateApiSpecVersions` (strict
  `X.Y.Z`) runs in `ci-released.yml` and refuses a draft, so a release of the service can never carry one.
  The arrived-text endpoint (phase D) and the payload change of C3 follow this path.
- **C5. Validation stays in the service.** Generated bean validation is off, so the bounded `ProblemReason`
  bodies stay the only error bodies. The generated `produces` lists `application/problem+json` beside
  `application/json`; together with `ActionRequestWrapper`'s `Accept` rewrite (a vendor media type is
  read as `application/json`) a test pins what each `Accept` gives: `application/json`, `*/*`, absent and a
  vendor type get `200` with `Content-Type: application/json`; `application/problem+json` gets `200` with
  `Content-Type: application/json` too, because every `200` sets its content type explicitly; `text/html`
  gets `406 not_acceptable`.

**Rationale.** Consumers (YOT, probation, court register) get a versioned artefact to build clients
from, the same way the estate's other APIs are consumed, and the service cannot drift from what it
publishes. Keeping the service's own copy of the document avoids a change to the audit library's glob
or to the deploy values.

**Alternatives considered.** Keeping the document only in the service (consumers hand-write clients
from a file in another repository); reading the document out of the jar for the audit filter (either
two documents of the same name or a resource at the root of a nested jar); generated bean validation
on (its `400` bodies are not the bounded ones, and it cannot express an unknown or repeated parameter);
one controller per endpoint each implementing `SharesApi` (ambiguous mappings, above).
