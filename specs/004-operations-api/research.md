# Research: Operations API

**Feature**: `004-operations-api` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

Each entry gives the decision, why, and what else was looked at. R1 and R2 settle the layering and the
edge; R3 to R6 the rerun request; R7 to R12 the sweep's rerun work and its record; R13 to R15 the three
reads; R16 to R20 errors, audit, indexes, metrics and settings; R21 the constitution; R22 the build traps;
R23 the contract repository.
Every open point is settled here, or is a row of spec.md *Decisions pending Sachin* with its default
applied.

Sources: the design review of spec 004 and its critique; the orchestrator's rulings on both (2026-10-03,
sections A, C and D) and the decisions taken with Sachin for spec 003 (section E), which win where they
differ; the fact-finding report on the store's read side (this repository at `c21a901`); spec 003 as
built and merged (`main` at `8ea9980`, its tasks' close-out notes included); and this repository's code as
read for this document (`V2__reshape_event_receipt.sql`, `V3__create_share_store.sql`,
`V4__projection_tried_at.sql`, `V5__read_api.sql`, `JdbcShareStore`, `YouthFlags`, `ExtractionSweep`,
`ShareStore`, `SweepRowOutcome`, `SweepProperties`, `SweepSchedulingConfig`, `IntakeObserver`,
`FlywayMigrationIT`; 003's `ApiRoute`, `ActionHeaderFilter`, `ActionRequestWrapper`, `RefusalWriter`,
`UnsupportedContentTypeFilter`, `QueryParameterNames`, `ProblemReason`, `ReadApiExceptionHandler`,
`BoundedErrorController`, `ProblemErrorReportValve`, `SharesController`, `ShareParameters`,
`ShareParametersInterceptor`, `ReadMetricsInterceptor`, `PayloadBodyFreeAuditPayloadGenerationService`,
`ApiWebConfig`, `ReadApiWebMvcConfig`, `ReadApiConfig`, `StatementTimeoutBackstop`,
`MicrometerRefusalObserver`, `RouteRefusal`, `ReadEndpoint`, `ReadOutcome`; `application.yaml`,
`application-test.yaml`, `acl/results-store-rules.drl`, `results-store-openapi.yaml`,
`gradle/libs.versions.toml`, `docker-compose.yml` and `docker/wiremock/mappings/*`).

---

## R1. Layering and wiring

**Decision.** As the design rules (`design_rules.md`) and spec 003 research R1 set out:

- `api/`: `OperationsController`, the one implementation of the contract jar's generated
  `OperationsApi` (R23), as 003's `SharesController` is of `SharesApi`; `OperationsParameters` with
  `OperationsParametersInterceptor` (strict query parameters, 003's `ShareParametersInterceptor`
  pattern), `RerunBodyParser` with `RerunBodyAdvice` (the strict rerun body, R16),
  `OperationsResponseMapper` (application records to the generated models). No hand-written response
  records: the generated models are the responses, as in 003. Each operation calls one service and maps
  the answer.
- `application/`: `RerunService`, `ExtractionStatusService`, `ReceiptsService`,
  `ReconciliationService`; ports `RerunRequests`, `OperationsQueries`, `SweepRounds`,
  `OperationsObserver`, `SweepObserver`. No JDBC or HTTP type.
- `domain/`: `RerunSelector`, `SelectorKind`, `RerunReason`, `OperatorId`, `RerunRowOutcome`,
  `OperationsEndpoint`, `ReconciliationWindow`.
- `persistence/`: `JdbcRerunRequests` (writes only the two rerun tables), `JdbcOperationsQueries`
  (read-only, its own `JdbcTemplate` with a query timeout, as 003's `JdbcShareQueries`),
  `JdbcSweepRounds` (the round upsert). The rerun write path goes into `JdbcShareStore`, beside the
  sweep's `FAILED` path, so it reuses `setTimeouts`, `lockDay`, `YouthFlags` and the store
  transaction, and only the sweep writes key details (Principle I).
- `config/`: `OperationsProperties`, `OperationsConfig` (unconditional), `MicrometerOperationsObserver`,
  `SweepObserverConfig` and `MicrometerSweepObserver` (unconditional); `SweepProperties` and
  `SweepSchedulingConfig` extended.

Spec 003's pieces are reused, not copied: `ApiRoute`, `ActionHeaderFilter`, `ActionRequestWrapper`,
`UnsupportedContentTypeFilter`, `RefusalWriter`, `QueryParameterNames`, `ProblemReason`,
`application/BadParameterException`, `ReadApiExceptionHandler`, `BoundedErrorController`,
`ProblemErrorReportValve`, `RouteRefusal` and `RefusalObserver`, `InstantFormat`, the effective
`VisibilityLag` bean of `ReadApiConfig`, the pool backstop (`StatementTimeoutBackstop`), the
`ApplicationContextRunner` stub `DataSource` pattern, and the test support `PostgresTestSupport`,
`UsersGroupsStub` and `EmbeddedBrokerSupport`.

`ReadApiExceptionHandler` is **extended, not joined by a second advice**. It extends
`ResponseEntityExceptionHandler` and is the service's only `@RestControllerAdvice`, with no
`basePackages` or `assignableTypes` filter, so it already handles every controller. A second advice of the
same kind would compete with it for Spring MVC's own exceptions, and which one answered would depend on
bean order. It keeps its name; its javadoc says it is the API-wide advice.

**Wiring.** The operations beans are unconditional (spec 003 R1: a read-only pod is a valid shape, and a
support endpoint must exist on every pod). The sweep stays where it is: built only with the subscription
and the sweep on (`SweepSchedulingConfig`). The sweep's rerun meters live on a new `SweepObserver` port
wired unconditionally (ruling C9), so they exist at zero on a pod whose sweep is off.

**Alternatives considered.** Extending `IntakeObserver` with the rerun meters: it is only wired with the
subscription on, so the meters would be missing on read-only pods (critique, improvement 7). Putting the
rerun write in a separate adapter: it would duplicate the lock order and the youth recompute.

---

## R2. Routes, actions and allow rules

**Decision.**

| Method and path | Action | Endpoint tag |
|---|---|---|
| `POST /operations/extraction/rerun` | `results-store-operations.rerun-extraction` | `rerun` |
| `GET /operations/extraction/status` | `results-store-operations.get-extraction-status` | `status` |
| `GET /operations/receipts` | `results-store-operations.list-receipts` | `receipts` |
| `GET /operations/reconciliation/daily` | `results-store-operations.get-daily-reconciliation` | `reconciliation` |

- `ApiRoute` (003 R2: one constant per template, action and endpoint tag) gains the four constants. As
  built, every constant serves `GET` (`method()` returns a constant) and `resolve` special-cases the
  shared `/shares` template; it gains a method component, so `allowedMethods` answers `POST` for the
  rerun path and `GET` for the others. Its tag component widens from `ReadEndpoint` to a sealed interface
  `RouteEndpoint` in `domain/`, which `ReadEndpoint` and the new `OperationsEndpoint` implement; a static
  `readRoutes()` lists the read constants.
- `ReadMetricsInterceptor` is registered on `/results-store/v1/**` only (`ReadApiWebMvcConfig`), so it
  never runs on `/operations`; it and the advice's no-handler count (a `406` before the handler) still
  record only routes whose tag is a `ReadEndpoint`. An operations `406` is counted in
  `resultsstore.operations.refused{reason=not_acceptable}` instead.
- `ShareParameters.check` and `support/ApiRouteSamples.samplePath` are exhaustive switches over
  `ApiRoute`; T001 gives them the operations constants (never reached by `ShareParameters`, whose
  interceptor runs on the read paths only; sample paths for the tests). The ten test classes that iterate
  over `ApiRoute.values()` are narrowed to `readRoutes()` where a case is about reads.
- 003's filter already refuses an unmapped path under the service with `404 route_not_found`, a known
  path with another method with `405` and `Allow`, and rewrites vendor `Content-Type` and `Accept` to
  `application/json`. On the rerun route that rewrite matters: a vendor `Content-Type` would otherwise
  choose the action (003 R2).
- `acl/results-store-rules.drl`: one rule per operations action, admitting **"Second Line Support"
  only**, matching the `Action`'s method and path in the form spec 003 built (ruling A3): for the rerun,
  `attributes["method"] == "POST"` and `attributes["path"] matches "/operations/extraction/rerun"`; the
  other three `GET` and their own path. `eval(userAndGroupProvider.isMemberOfAnyOfTheSuppliedGroups($a,
  "Second Line Support"))`.
- Read-API rules keep admitting "System Users" and "Second Line Support" (ruling A3; constitution VII:
  support staff *read payloads through the read API under its own rules*). `ResultsStoreRulesTest`
  proves: read actions allowed for both groups and refused for a caller in neither; operations actions
  allowed for "Second Line Support" and refused for "System Users".

**Contract first, in the contract repository (R23; ruling C7).** T001 adds the four operations to
`hmcts/api-cp-crime-results-store` first; the service pins the draft, mirrors the paths in
`results-store-openapi.yaml` (003's `OpenApiContractDriftTest` compares the two as parsed objects), and
adds the rules and the routes. Spec 003's `OpenApiContractTest` reads the "controller mappings" from the
generated interfaces' annotations, not from the Spring context, so with `OperationsApi` added to its
sources the two-way check holds from T001. What waits for T008 is its
`every_sharesapi_operation_should_be_overridden` case, extended to `OperationsApi`: until a class
implements the interface, Spring registers no mapping for `/operations`, and a call there gets
`NoResourceFoundException`, which the advice answers `404 route_not_found`.

**Alternatives considered.** The design review's dotted names (`results-store-operations.extraction.rerun`):
replaced by the rulings' kebab verb-noun names, matching 003. One combined contract-and-controllers task:
rejected, the contract must land first (constitution, *Development Workflow*). An `x-planned: true` marker
on the paths until their controllers exist (this document's first draft): not needed now that the
mappings come from the generated interface, and a marker in the service's document alone would fail the
drift test.

---

## R3. A rerun is a request; the sweep rewrites in place

**Decision (ruling C1).** `POST /operations/extraction/rerun` writes a request and one item per matched
share, and nothing else. Each sweep round then works pending items: it re-reads the share's working copy
(`payloadForExtraction`, 002 FR-033), extracts outside any transaction, and writes under the day lock,
the item lock and the share lock. An `OK` share is rewritten in place and stays `OK`.

**Why.** Principle I already gives key details and `projection_*` to *the extraction sweep alone, re-read
from the stored payload*. A request-time write would put a second writer beside the sweep. Marking rows
`FAILED` to make the sweep pick them up would blank their key details (`hearing_share_failed_is_empty_ck`)
and drop them from consumers' searches while they wait.

**Alternatives considered.** Reset `OK` rows to `FAILED`: blanks them (rejected by the rulings). A
synchronous rerun inside the `POST`: unbounded time and locks inside a web request.

---

## R4. Writing a request: one transaction, chunks, the request row last

**Decision (rulings C1, C2; critique defects 1, 2, 4, 11, 12).**

- One transaction with its own timeouts (`resultsstore.operations.rerun.request.*`; defaults transaction
  120 s, statement 20 s, lock 10 s; the same rules as intake's: lock ≤ statement ≤ transaction,
  statement below the socket timeout). They are set with `set_config(..., TRUE)` inside the transaction,
  so for that transaction they replace the pool's backstop `statement_timeout` (003 FR-062, the intake
  statement timeout, 10 s). The request's statement timeout is above intake's on purpose: one chunk of
  5,000 items can take longer than one intake statement. The request never takes the day lock, so it
  does not hold intake up (R4 *Locks*); it is not part of the visibility lag's proof.
- Items are inserted in chunks of `chunk-size` (5,000) in `share_id` order, each chunk one statement:
  a `matched` CTE, an `INSERT … SELECT … ON CONFLICT (share_id) WHERE state = 'PENDING' DO NOTHING
  RETURNING`, and the two counts (data-model.md). So `matched` and `queued` come from the statement that
  inserted the items, and cannot drift from them.
- The request row is inserted **last**, with the summed counts. The items' foreign key to it is
  `DEFERRABLE INITIALLY DEFERRED`, checked at commit. Its counts are then plain fixed columns, and the
  fixed-columns guard never needs an exception.
- `queued_count` is not stored: `queued` = `matched` − `already_pending_count`, both stored, so a repeat
  can answer every count (critique defect 2). `unknown_share_count` is stored for `SHARE_IDS`.
- Over `max-matched` → the transaction is rolled back; nothing is written.

**Snapshots.** Each chunk runs at `READ COMMITTED`, with its own snapshot. A share stored between two
chunks with a higher `share_id` than the last chunk's may be included, and is then counted with it; one
with a lower `share_id` is not. For a stored range no share can appear (R6); for hearing and share lists
the contract says a share stored while the request is written may or may not be included.

**Locks.** Each item insert takes `FOR KEY SHARE` on its share through the foreign key, until commit.
The sweep locks the share `FOR NO KEY UPDATE` (R8) and intake's youth propagation is a non-key update;
neither conflicts with `FOR KEY SHARE`, so a long request blocks neither (critique defect 11).

**Alternatives considered.** A separate `count(*)` before the insert: two snapshots, so the cap can be
passed by a margin and `matched` can disagree with the items (critique defect 4). A `queued_count`
column updated after the items: the fixed-columns guard would refuse it (critique defect 1). Chunks in
separate transactions: a crash leaves a partial request whose counts are unknown. `REPEATABLE READ` for
the whole request: one snapshot, but a serialisation failure on a concurrent identical request would
need its own retry; the deferred key and the request-last order already give exact counts.

---

## R5. Repeats

**Decision (ruling C1, idempotency option A; critique defect 3).**

- The selector is made canonical: ids lower-case, sorted, duplicates removed; instants as UTC with six
  fraction digits; written as a fixed-order JSON text, `{"kind":"SHARE_IDS","shareIds":[…]}` and so on.
  `selector_sha256` is the SHA-256 hex of its UTF-8 bytes (`PayloadChecksum.sha256Hex`).
- Before the transaction, one autocommit read: an `OPEN` request with this hash → a repeat, nothing
  written. This saves the chunk work on a double click.
- In the transaction, the request insert's `ON CONFLICT (selector_sha256) WHERE status = 'OPEN' DO
  NOTHING` catches a race: no row back → roll back, then read the newest request with this hash in
  `OPEN` or `DONE`. `OPEN` → repeat. `DONE` (it closed in between) → try the whole request once more;
  a second conflict answers a repeat of the request then found.
- Two identical requests at once: the second's insert waits on the first's uncommitted unique entry,
  then does nothing, and becomes a repeat. Its items were also blocked on the first's pending items, so
  it wrote none that survive its rollback.
- A repeat returns the first request's id, status and counts, `repeat: true`. Its reason is not stored.
  After the request is `DONE`, the same selector makes a new request.

**Alternatives considered.** An `Idempotency-Key` header (B): needs the caller to manage keys. `409` on a
repeat (C): makes a double click an error.

---

## R6. A stored range must end before the visibility lag

**Decision (ruling C2; critique defect 16).** `storedTo` must be at or before the database's `now()`
minus spec 003's effective visibility lag (90 s at the defaults: transaction 60 s + 2 × statement 10 s +
idle-in-transaction 10 s, decided with Sachin, E3), checked inside the request's transaction on the
database clock. Otherwise `400 range_invalid`. The lag is the `VisibilityLag` bean, so a deployment that
sets a longer lag moves this cut-off with it.

**Why.** `stored_at` is read when the share row is inserted (003's V5 trigger), but the share commits up
to the lag later. A range ending near now could silently miss shares that commit after the request's
snapshot. Past the lag, every share with `stored_at` in the range has committed, so the range is closed.

---

## R7. Claiming items across pods

**Decision (ruling C3; critique defect 10).** A round claims its items in one statement: `UPDATE …
SET tried_at = now() WHERE (rerun_id, share_id) IN (SELECT … ORDER BY tried_at NULLS FIRST, queued_seq
LIMIT n FOR UPDATE SKIP LOCKED) RETURNING …` (data-model.md). The stamp moves claimed items behind the
rest of the queue, so a second pod claims the next ones, and `SKIP LOCKED` keeps two concurrent claims
apart. The per-item write still locks the item and checks it is pending (`SKIPPED` otherwise), so a slow
pod whose items were claimed again cannot write twice.

**Throughput.** 200 items per round, a round every 5 minutes: about 57,600 items a day per pod. A
31-day range at about 4,800 shares a day (about 150,000) takes about 2.6 days on one pod, and less with
more pods, because claims no longer collide.

**Alternatives considered.** Reading the queue head without a claim (the design review): every pod reads
the same head and all but one record `SKIPPED`, so more pods add work, not speed.

---

## R8. The in-place write

**Decision (rulings C3, C4; critique defects 6, 11, 14; improvement 1).**

- Lock order: day (`lockDay`), item (`FOR UPDATE`), share (`FOR NO KEY UPDATE`). Intake and the
  `FAILED` path take the day first too, so the order matches. `FOR NO KEY UPDATE` is enough because no
  key column changes, and it does not wait on the `FOR KEY SHARE` a request's item insert holds.
- The outcome table of spec FR-018, checked in that order: not pending → `SKIPPED`; stored version newer
  than the sweep's → `NEWER_KEPT`; `FAILED` share → the existing `fixed` or `failedAgain` (whatever its
  attempts: the operator asked); `OK` share → `KEPT`, `YOUTH_KEPT`, `YOUTH_RAISE_HELD`, `UNCHANGED` or
  `REEXTRACTED`.
- `UNCHANGED` means the key details, the youth subject and the defendant rows already match. Then only
  the item is written, unless the stored version is older than the sweep's; then the share gets the new
  version, attempts + 1 and the time, nothing else.
- `REEXTRACTED` writes the key details, the youth subject, the version, attempts + 1 and
  `projected_at = GREATEST(clock_timestamp(), projected_at)` (never back, even across a clock step); adds
  missing defendant rows with a new `INSERT_DEFENDANT_IF_ABSENT … ON CONFLICT (share_id, case_id,
  defendant_id) DO NOTHING` (the existing `INSERT_DEFENDANT` has no `ON CONFLICT`, so reusing it for an
  `OK` share would abort on its first existing row); then `YouthFlags.recompute`.
- The share is written before the item is marked done, in the same transaction, so the guard sees the
  item pending.

**Defendant rows stay add-only.** `share_defendant_guard_tg` forbids deletes. A defendant an older
extractor wrongly indexed stays; there is no defendant search yet (spec 001, 003 out of scope). A later
spec with a defendant view decides whether to clean them.

---

## R9. Youth subjects and values that become null

**Decision (ruling C5; D-YOUTH-RAISE, D-NEVER-BLANK, pending Sachin).**

| Stored | Re-read | Done | Why |
|---|---|---|---|
| `true` | `false` or unknown | nothing, `YOUTH_KEPT` | `YouthFlags` keeps the day's flag `true` *by construction: a share's own value never changes once known*; the guard backs it |
| `false` | `true` | nothing, `YOUTH_RAISE_HELD`, listed and counted | a consumer's `storedSeq` cursor has passed the day's shares; YOT filtering on `notFalse` would never see the day become youth-relevant (critique defect 8). Held until a youth-raised feed exists (a later spec) |
| unknown | `false` or `true` | written | the unknown-row obligation (003 FR-023) already tells consumers to re-read unknown rows |
| `false` | unknown | written (D-NEVER-BLANK default) | the day may become unknown again, with no new `storedSeq`; 003's contract says so |

Other key details may move from a value to null (D-NEVER-BLANK, default allow): an extractor fix may
correctly find a value absent. The store's claim is therefore "a rerun never moves a share to `FAILED`",
not "never blank" (critique defect 5).

A held share keeps a youth subject its working copy no longer supports. That is visible: listed in the
status, counted, and R2-detectable later. It is not a rule about the case; it is about how a change is
shown to consumers.

---

## R10. The share guard (V6)

**Decision (ruling C4; D-RERUN-GUARD option A, pending Sachin).** `CREATE OR REPLACE FUNCTION
hearing_share_guard()`; the trigger stays. The fixed-columns branch is V3's, unchanged. When any of the
14 key-detail and `projection_*` columns changes:

1. `hearing_share_projection_version_guard`, every row: `projection_version` not lowered,
   `projection_attempts` raised, `projected_at` not earlier. Every existing write already does this
   (`SET_EXTRACTED` and `SET_FAILED_AGAIN` both set `projection_attempts + 1` and
   `projected_at = clock_timestamp()` with the current version), so the `FAILED` path changes only in a
   rolling deploy, where an older pod could lower a newer reading; it then gets a refusal, counted as the
   row's `error`, until the old pods are gone.
2. `OK` rows only, in order: `hearing_share_projection_guard` (stays `OK`), `hearing_share_youth_guard`
   (`true` stays `true`), `hearing_share_rerun_guard` (a pending item names the row).

Each raises `restrict_violation` naming itself, so `OperationsSchemaIT` asserts each branch by name
(critique improvement 2).

**What it proves (critique defect 7).** That a pending rerun item names the row, not that an operator
asked. Any session that can insert an item can then rewrite the row while the item is pending. That is
still much stronger than nothing: an accidental `UPDATE` from another code path, or a hand-typed fix, is
refused unless an item is pending for that very share. The documents say exactly this.

**`FlywayMigrationIT`.** Its case `update_of_an_ok_share_s_key_details_or_projection_should_be_refused`
(parameterised over six assignments, expecting `hearing_share_projection_guard`) now meets the version
guard first for the assignments that do not raise the attempts. T002 re-parameterises it with the guard
each assignment meets, and updates the javadoc that says an `OK` share's key details and projection are
final (ruling C7, critique defect 20).

**Alternatives considered.** B, a transaction-local `set_config` flag the guard reads: any session can set
it. C, dropping the rule: no database backstop.

---

## R11. Items that keep failing

**Decision (ruling C3; D-RERUN-CANCEL, pending Sachin).** An operational failure (the payload read or the
write) raises the item's `attempts` in its own short transaction and leaves it pending. At
`resultsstore.sweep.rerun-max-attempts` (default 3) the item is marked done with outcome `ABANDONED`,
counted (`resultsstore.sweep.rerun.rows{outcome=abandoned}`, alertable) and listed in the status. Its
request can then close, and the share can be named by a new request.

**Not counted:** a pod that dies after claiming an item. Nothing failed that the store saw; the claim
time pushes the item behind the others and another pod works it.

**Alternatives considered.** No limit (the design review): a deterministic failure would keep its request
open for ever and block the share from every later request (critique defect 13). A cancel endpoint:
deferred.

---

## R12. The sweep's last round

**Decision (ruling C1; D-SWEEP-ROUND; critique defects 23, 24).** After every round, empty ones
included, `ExtractionSweep` reports a `SweepRoundRecord` (pod, extractor version, the round's length
from a monotonic clock, its counts) to `SweepRounds.record`. `JdbcSweepRounds` upserts one row per pod
in one statement: `finished_at = clock_timestamp()`, `started_at` = that minus the length, so every
time is the database's; `last_worked_at` moves only when the round worked a row; rows of other pods
older than `pod-recent` are deleted in the same statement. A failure is logged by class, counted
(`resultsstore.sweep.round.record.failed`) and never fails the round.

`pod` is `resultsstore.sweep.pod-name`, default `${HOSTNAME:local}`, checked against
`^[a-z0-9][a-z0-9.-]{0,252}$`. `runRound()` returns a `RoundResult` (the `FAILED` path's outcomes and the
rerun outcomes) instead of a list; `SweepSchedule` ignores the value as before.

**Alternatives considered.** ShedLock or a lock table (001 research: not needed for correctness). One row
per round: a history with no purge until retention exists.

---

## R13. The status

**Decision (spec FR-031 to FR-033).** One `GET`, no parameters, several short reads (data-model.md).

- `FAILED` counts use the sweep's own rule with the **serving pod's** `EXTRACTOR_VERSION` and
  `max-attempts`. During a rolling deploy two pods can answer differently; each pod's sweep row carries
  its own `extractorVersion`, so the difference is visible (critique defect 24).
- Rerun requests: the 20 most recent **whatever their state**, so a finished request's outcomes stay
  visible after it closes (the design review listed open ones only). Per request: selector kind, status,
  requested and finished times, the stored counts, pending items, and one count per stored outcome.
- Held (`YOUTH_RAISE_HELD`) and abandoned items: counts and the newest 50 share ids each.
- Never `reason`, never `requested_by`.

---

## R14. Receipts

**Decision (spec FR-034 to FR-036; ruling C6).** Exactly one form: `hearingId` + `hearingDay`
(`event_receipt_hearing_day_ix`, which V2 added *for spec 004's receipts endpoint*), or `messageId` (the
primary key). `messageId` is matched exactly: 1 to 256 printable ASCII characters, no space, because
the store keeps `JMSMessageID` as given (`ID:…`) or `sha256:<hex>`. `UNREADABLE` and `NO_IDENTITY`
receipts usually carry no hearing identity, so they are found by `messageId` only.

The query names its twelve columns; `OperationsSqlTest` asserts the exact list and refuses `*` (critique
improvement 6). No match → `200` with an empty list (a query, not a resource). At most `max-rows` (200),
reading one more to set `truncated`.

---

## R15. The daily reconciliation

**Decision (ruling C6; D-RECON-CLOCK, D-R1-WINDOW, D-R2, D-NIGHTLY, pending Sachin).**

- Window: [the date at 00:00 Europe/London, the next date at 00:00 Europe/London), worked out in Java
  (`ReconciliationWindow`), 23 or 25 hours on clock-change days. The London day is the register day
  (`shared_day_london`). A date after today in London is refused; today is allowed and `partial`.
- Receipts: counted by `first_received_at` in the window, by their current status.
- Shares: counted by `stored_at` in the window, with the extraction state as of now. A message received
  before midnight and stored after it counts in different days on the two sides; the contract says so.
- R1: receipts first received in the window and still `RECEIVED` whose **last** delivery is older than
  the give-up window (critique defect 17): a message the broker is still redelivering keeps moving its
  `last_received_at`, so measuring from the first arrival would flag work in flight. First arrival keeps
  window membership. Give-up default 1 hour, a setting, to be set from the broker's redelivery give-up
  time.
- R2: counts only (`extractionFailed`, `staleVersion`), `sampled: false`. A sampled re-extraction would
  make a `GET` parse payloads; it belongs to the nightly job.
- Nothing is stored. The nightly job, `reconciliation_finding`, and the alert "reconciliation does not
  run" are deferred to a later spec.

---

## R16. Errors, reasons and the rerun body

**Decision.**

- Spec 003's four-field body, `ProblemReason` table, advice and `/error` page, unchanged in shape.
  `ProblemReason` gains the operations reasons (contracts/operations-api.md §6), in 003's style.
- **The body.** The generated `OperationsApi.rerunExtraction` binds the contract's `RerunRequest`
  model (`consumes = application/json`; the schema has `additionalProperties: false`). Strictness does
  not depend on Jackson's defaults: `api/RerunBodyAdvice`, a `RequestBodyAdviceAdapter` that supports
  the rerun's body type only, reads at most 64 KiB + 1 of the raw bytes in `beforeBodyRead`, has
  `api/RerunBodyParser` read them into a tree with the service's Jackson 3 mapper and check them field by
  field, and hands the same bytes on for binding. A fault throws `BadParameterException` with its own
  reason (`unreadable_body`, `unknown_field`, `body_too_large`, and the value reasons); an empty body
  reaches `handleEmptyBody`, which gives `unreadable_body`. So no body fault reaches 003's mapping of
  `HttpMessageNotReadableException` (`bad_request`), which is unchanged.
- **Query parameters.** `api/OperationsParametersInterceptor`, registered on `/operations/**` by
  `config/OperationsWebMvcConfig` as 003's `ReadApiWebMvcConfig` registers `ShareParametersInterceptor`,
  runs `OperationsParameters` over the raw query string (`QueryParameterNames`) before Spring binds the
  generated method's typed arguments. A refusal is written through `RefusalWriter` and counted there,
  since the advice never sees it.
- A non-JSON `Content-Type` makes Spring MVC raise `HttpMediaTypeNotSupportedException`. 003's advice
  gives it `ProblemReason.forErrorStatus(415)`, which is `bad_request`; 004 adds one mapping: that
  exception → `415 unsupported_content_type` (the reason 003's multipart filter uses). The multipart
  filter is unchanged; its javadoc's "no request to the API has a body" becomes "only the rerun has a
  body, and it is JSON".
- **The operator.** The rerun operation declares an optional `CJSCPPUID` header parameter in the
  contract (a string), so the generated method receives it; `OperationsController` refuses a missing or
  non-UUID value with `401 unauthenticated` (ruling A6, critique defect 21) through an
  `OperatorMissingException` the advice maps. With authorisation on, the library refuses first; the
  controller's check matters where it is off. 003's `AuditIT` found that the audit library writes no
  request header into an event, so declaring the header leaks nothing into the audit store.
- Operations `4xx` from the advice are counted in `resultsstore.operations.refused{endpoint,reason}`;
  the endpoint comes from the matched `ApiRoute` (request attribute `ApiRoute.REQUEST_ATTRIBUTE`, set
  by 003's filter). Each refusal is counted once: by the interceptor, or by the advice.

---

## R17. Audit

**Decision (ruling A7; E1, E13).** The library audits every operations request that reaches the
endpoint. Its response event copies the response body: here ids, counts, times and bounded codes, never
a payload or message text. Spec 003's `PayloadBodyFreeAuditPayloadGenerationService` swaps the body for
`{"payloadOmitted":true}` only when the request's derived `CPP-ACTION` is one of the two payload
actions (`results-store.get-share-payload`, `results-store.get-share-arrived-payload`); the operations
actions are not payload routes, so their events are the library's own and the class is not changed.
Refusals before the audit filter are counted, not audited (003 FR-051, constitution VII 2.2.0).

What 003's `AuditIT` pinned for `cp-audit-filter-springboot` 1.0.5: an event carries the caller
(`_metadata.context.user`), the correlation id, the query and path parameters (path parameters only
where the document declares them inline) and the body; no request header; the inner record is named from
`Accept` or `Content-Type`, so the derived action is not in the event.

**Not verified:** what the request event holds for a `POST` body. Every 003 route is a `GET`, so no
request body has been seen. If the library copies it, the rerun's reason lands in the audit store.
`AuditIT` gains a case that pins what the library does, and the result is recorded under T009 and in the
contract. The OpenAPI description of `reason` says it must hold no personal data; nothing can enforce
that in free text. The operator's id is in every event as the caller: that is the audit record, not a
log line, and FR-011's "never logged" is about the service's own logs.

---

## R18. Indexes and the plan tests

**Decision.**

| Index | Serves |
|---|---|
| `event_receipt_first_received_ix (first_received_at)` | daily receipt counts |
| `event_receipt_stale_ix (last_received_at) WHERE status = 'RECEIVED'` | R1 (ruling C6) |
| `hearing_share_stored_at_ix (stored_at)` | daily share counts; the stored-range selector |
| the rerun tables' indexes | the queue, the pending look-up, closing, repeats, the status |

`OperationsQueriesPlanIT` runs `EXPLAIN (FORMAT JSON)` over the `JdbcOperationsQueries` and
`JdbcRerunRequests` constants, with `enable_seqscan` off on a connection the test owns (as 003's
`ReadQueriesPlanIT`), and asserts each uses its intended index by name. R1 must use `event_receipt_stale_ix`: the test seeds
many `RECEIVED` rows delivered recently and a few delivered long ago, runs `ANALYZE`, and picks a cut-off
that only the old ones pass, so the new index is the selective one. Plain `CREATE INDEX` (pre go-live; `CONCURRENTLY` in the risks).

---

## R19. Metrics

**Decision (ruling C9; contracts/metrics.md).** Every meter registered at start, tags from domain enums,
no id, date, operator or reason in a tag.

- `MicrometerOperationsObserver`: `resultsstore.operations.rerun.requests{selector,result}`,
  `resultsstore.operations.rerun.shares.queued{selector}`, `resultsstore.operations.refused{endpoint,reason}`.
- `MicrometerSweepObserver`: `resultsstore.sweep.rerun.rows{outcome}` (twelve outcomes),
  `resultsstore.sweep.rerun.requests.finished`, `resultsstore.sweep.round.record.failed`.
- Unchanged: `resultsstore.sweep.rows{outcome}` counts the `FAILED` path only;
  `resultsstore.extraction.failed{stage=sweep,…}` also moves for a rerun's `FAILED_AGAIN`.
- Spec 003's `resultsstore.read.refused{reason}` is the edge's counter, with the `RouteRefusal` tags as
  built: `route_not_found` and `method_not_allowed` (action filter), `unsupported_content_type` (the
  multipart filter), `unauthenticated` and `forbidden` (the `/error` page, for the authorisation
  library's `401` and `403`), `connector_rejected` (the host's error report). They count for every path
  they guard, `/operations` included. The name is 003's and is kept; the metrics delta says it covers both
  bases. A `415` that Spring MVC raises for a non-JSON rerun body is not an edge refusal: it is counted in
  `resultsstore.operations.refused`.

---

## R20. Settings

**Decision.** `OperationsProperties` (`resultsstore.operations.*`) and three new `SweepProperties`
fields; every bound in contracts/configuration.md, checked at start through `config/Rules`. Bounds that
are part of the contract and the schema stay constants: the reason length (10 to 500, the table's
`CHECK`), the 64 KiB body, the status's 20 requests, 20 pods and 50 ids, R1's 50 ids, the
`messageId` length.

---

## R21. Constitution 2.3.0

**Decision (ruling A11; D-PRINCIPLE-I-BUMP, pending Sachin).** MINOR, 2.2.0 (in the tree since spec 003)
→ 2.3.0: Principle I gains a new freedom, which changes
what a stored version means to consumers, so it is not a PATCH (critique defect 19). The sweep bullet of
Principle I becomes:

> And by the extraction sweep alone, re-read from the stored working copy (Principle II):
>
> - the key-details columns;
> - the `projection_*` columns (extraction status and reason).
>
> The sweep retries a `FAILED` share. It re-reads an `OK` share only while a pending rerun item, made by
> an operator's rerun request, names it. An `OK` share stays `OK`: a re-read that fails keeps the key
> details it had, an older extractor never overwrites a newer reading, and a youth subject once true
> stays true. A rerun request never changes a share while it waits.

The *Rationale* gains one proposed sentence: "A stored version's facts and payload never change; its key
details are the store's current reading of that payload, and say when they were last read." The Sync Impact Report
records the bump and the cross-reference to Principle II (the working copy). Principle VII is unchanged
by 004: 2.2.0 already says `/operations/**` admits "Second Line Support" only and never returns a
payload, and states the audit rule 004 follows.

---

## R22. Build traps and phase independence

- **PMD `OnlyOneReturn`**: the body parser, `OperationsParameters`, `RerunSelector` factories,
  `ReconciliationWindow`, `RerunRowOutcome` decisions and the store's outcome switch use single-exit
  style or a site suppression with a reason (ruling A1).
- **`AvoidCatchingGenericException`**: the sweep's rerun path catches `RuntimeException` to record an
  outcome, with the existing suppression comment style.
- **JaCoCo** (0.88 line, 0.85 branch, `config/**` excluded): decisions live in `domain/`,
  `application/` and `persistence/`, so the gate measures them.
- **Contexts**: the operations beans are unconditional; every context test is already on Postgres or
  has a stub `DataSource` after spec 003 (ruling A9).
- **`ExtractionSweep`'s constructor changes twice** (T005: `SweepObserver` and the rerun settings;
  T006: `SweepRounds`). Each task updates `SweepSchedulingConfig` and every test that builds it, so each
  phase stays green.
- **`FlywayMigrationIT`'s version list** gains `"6"` in T002 (it lists `"1"` to `"5"` after spec 003);
  its checksum map, `V1_TO_V4_CHECKSUMS` today, gains V5's checksum, so V5 is pinned once V6 exists.
- **Exhaustive switches and route iterations.** `ShareParameters.check` and `ApiRouteSamples.samplePath`
  switch over `ApiRoute` and stop compiling when constants are added; ten test classes iterate over
  `ApiRoute.values()` (R2). T001 updates them in the same commit.
- **The real server.** `OperationsApiIT` runs with `webEnvironment = RANDOM_PORT`, as `ReadApiIT`: the
  authorisation library refuses with `sendError`, which reaches `/error` only on a real container, so a
  `401` or `403` case under MockMvc proves nothing.

---

## R23. The contract repository (D-OPS-CONTRACT)

**Decision (default, pending Sachin).** The operations API is HTTP, so it is published contract-first
like the read API (003 research R23, 003 FR-063). Its four operations go into the **same** repository and jar,
`hmcts/api-cp-crime-results-store` / `uk.gov.hmcts.cp:api-cp-crime-results-store`:

- the paths under the tag `operations`, so the generator emits `uk.gov.hmcts.cp.resultsstore.openapi.api.OperationsApi`
  beside `SharesApi` (interface only, as 003);
- schemas `RerunRequest` (`additionalProperties: false`), `RerunAccepted`, `ExtractionStatus` (with its
  nested types), `Receipts`, `Receipt`, `DailyReconciliation` (with its nested types); `date-time` is
  `java.time.Instant`, durations are strings;
- the shared `ProblemDetail` schema's `reason` enum gains the operations reasons, so the advice's
  `ProblemDetail.ReasonEnum.fromValue(reason.code())` works for every reason;
- the rerun operation's optional `CJSCPPUID` header parameter (R16);
- a CHANGELOG line and `OpenApiObjectsTest` cases (each operation's return type).

The flow is 003's: a pull request to the api repository, merged and fast-forwarded to `team/rs`, whose
build publishes a `rs-<sha7>` draft to `hmcts-lib`; the service pins the draft in
`gradle/libs.versions.toml` and mirrors the paths in `results-store-openapi.yaml` (the drift test is red
until both are made); any later contract change repeats the loop. At the end (T011) a GitHub Release
`v0.3.0` publishes `0.3.0`, the service pins it, and `./gradlew validateApiSpecVersions` (run by
`ci-released.yml`) passes. The audit library keeps reading the service's own `results-store-openapi.yaml`
(`audit.http.openapi-rest-spec`), which is why the service keeps its mirrored copy. Its `info.description`
stops saying that the caller may be in either group: the read routes admit both, `/operations` admits
"Second Line Support" only.

One `OperationsController implements OperationsApi` serves all four operations, as `SharesController`
serves `SharesApi`: the generated interface's mappings would register once per implementing class.

**Alternatives considered.** A second repository, `api-cp-crime-results-store-operations`: its own jar,
version gate and drift test, and its own `ProblemDetail` model in another package, so the one advice
would have to build two problem types by route. It would let the operations contract move on its own
release cycle, which nothing needs today. Keeping the operations out of any contract repository: against
the constitution's contract-first workflow and the estate convention 003 followed.
