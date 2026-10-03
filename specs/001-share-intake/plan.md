# Implementation Plan: Share intake

**Branch**: `001-share-intake` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/001-share-intake/spec.md`

## Summary

Build the store's write path: from the `public.events.hearing.hearing-resulted` message on the
shared durable subscription to a stored, versioned share, without progression enrichment (spec
002) and without the read API (spec 003).

Per message: read the identity and extract the key details in memory; write the receipt keyed by
the broker's message id in its own short transaction; stop and acknowledge for a non-share or a
redelivery that has already settled; otherwise run one store transaction (lock the hearing day,
insert the share with `ON CONFLICT DO NOTHING`, payload text and parsed copy, defendant index,
chain by `shared_at`, youth flags, receipt `STORED`); fire metrics; return so the container
commits the JMS session, which acknowledges the message. Duplicates are dropped by the unique key
and marked `DUPLICATE`. An extraction failure never stops a share being stored; a scheduled sweep,
safe on several pods without a distributed lock, retries `FAILED` rows. A retryable failure is
rethrown after a capped pause so the broker redelivers. Flyway migrations V2, V3 and V4 (the
sweep's `projection_tried_at`); V1 is untouched. Detail: [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/).

## Technical Context

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.1.1 (web, jdbc, flyway, artemis, actuator,
opentelemetry); Spring JMS over the Artemis Jakarta client; `JdbcClient` and `TransactionTemplate`
over Boot's `JdbcTransactionManager`; Jackson 3 (`tools.jackson`); Micrometer, plus
`io.micrometer:micrometer-registry-prometheus` (new, version from the Boot BOM; research R16);
Lombok available  
**Storage**: PostgreSQL (16 locally and in tests; production Azure Flexible Server version not
known yet, see research R2), Flyway migrations V2, V3 and V4  
**Testing**: JUnit 5, Mockito, AssertJ, Awaitility; Testcontainers `postgres:16`
(`support/PostgresTestSupport`); embedded Artemis (`artemis-jakarta-server`); all in the one
`test` task, `failFast` on  
**Target Platform**: Linux container on AKS (2 or more pods), Gradle build  
**Project Type**: single Spring Boot service (`uk.gov.hmcts.cp.resultsstore`)  
**Performance Goals**: about 4,800 shares a day (design page, *Volumes*), bursty after court
sittings; payload p99 265 KB, largest seen 2.4 MB; a published share stored and acknowledged
within 1 s on the compose stack (SC-001); the store transaction gives up within the lock timeout
plus 1 s when the day is locked elsewhere (SC-009)  
**Constraints**: acknowledge only after commit; never refuse to store; JaCoCo 0.88 line / 0.85
branch (excluding `Application` and `config/**`); PMD 7.22.0 clean on main and test
(`OnlyOneReturn` on); no wildcard imports; ids only in logs; bounded metric tags; Hikari socket
timeout 30 s; V1 never edited  
**Scale/Scope**: 2 or more pods, one consumer each on the one shared subscription; about 35 new
production classes and 25 test classes; 16 tasks (T001 done) in four phases and a final task

No item above is unresolved: every open point is settled in [research.md](research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution 2.0.0.

| Principle | How this feature satisfies it | Gate |
|---|---|---|
| I. Every share is an immutable version | Payloads and defendant rows are insert-only (`refuse_row_change` guards; the sweep only inserts the first defendant rows of a `FAILED` share). A share's facts, `arrived_out_of_order` and `enrichment_applied` included, are fixed at insert (`hearing_share_fixed_columns_guard`). Only `is_latest`, `predecessor_share_id`, `day_youth_seen` (store transaction, under the day lock), the key-detail and `projection_*` columns (sweep only, under the day lock, row still `FAILED`) and `projection_tried_at` (sweep only, after every attempt, any status) are ever updated; on the day row only latest, count and `youth_seen` (data-model "What may change") | PASS |
| II. The payload is the source of truth | Text stored exactly as received, its byte size, SHA-256 over the UTF-8 text; every key-detail column is read from it and the sweep rebuilds from the text, never the parsed copy. No enrichment in 001 (`enrichment_applied` false) | PASS |
| III. Consumers search indexed columns | No read path in 001. Key details are normalised nullable columns; the consumer indexes arrive with the read API in spec 003 | PASS (not exercised) |
| IV. No business rules | Facts recorded as stated: `jurisdiction_type` with no value check, `youth_court_id` as stated, nothing derived from `youthCourtDefendantIds`. The youth flags are three-valued records of `isYouth`, agreed with Sachin (D1, D3), not a youth rule | PASS |
| V. Never refuse to store | Only the three identity fields are checked. Non-shares recorded with bounded reason and text, counted, acknowledged, never dead-lettered. Extraction runs before the store transaction and cannot throw; failure stores the share as `FAILED`. `\u0000` and unpaired surrogates leave the parsed copy empty instead of failing (R8) | PASS |
| VI. Idempotent, transactional intake | Receipt first, in its own transaction, keyed by message id, statuses and attempts. One store transaction for lock, share, payload, key details, youth, latest, receipt. Ack after commit. Duplicates by the unique key with `ON CONFLICT DO NOTHING`, no comparison, no anomaly. Retryable failures rethrown after `min(2^n s, 30 s)`; no retry loop | PASS |
| VII. Default-deny authorisation | No endpoint added; the actuator `prometheus` endpoint is already exposed and excluded from the rules | PASS (not exercised) |
| VIII. Observability through Azure Monitor | Counters and a lag timer with bounded tags (contracts/metrics.md); every drop or failure path moves a counter; no exception report | PASS |
| IX. Artemis only for legacy integration | Consumes the existing subscription; name and selector unchanged; publishes nothing | PASS |
| X. Test-driven development | Every task in the phase plan lists its test first; red run quoted before green (phase-gate) | PASS |
| XI. Privacy in telemetry | MDC holds ids only and is cleared per message; exceptions named by class; reasons are bounded codes; `NoPayloadInLogsIT` and the metric-tag check (SC-010) | PASS |
| XII. Estate conventions | Gradle, Java 25, Boot 4; constructor injection; records; explicit imports; Flyway; typed validated properties; Conventional Commits; no AI attribution | PASS |
| Quality gates | `build pmdMain pmdTest jacocoTestReport` green per phase; reviewers code-reviewer, qa, spec-validator (and Codex) | PASS |

**Initial gate: PASS.** No violation, so Complexity Tracking is empty.

**Re-check after Phase 1 design: PASS.** The design adds nothing that touches a principle beyond
the table above. Points checked again:
- The sweep writes defendant rows for a row that had failed extraction: an insert of the index,
  not an update of a stored fact (I).
- `day_youth_seen` on older rows is the day's youth flag, which I lists as updatable under the
  lock.
- A raw U+0000 in a message cannot be stored by PostgreSQL in any `text` column, so such a
  receipt keeps the reason without the text (research R8). The message is still recorded and
  acknowledged, so V holds; the narrowing of FR-008's "with the message text" is noted for
  `/speckit-analyze`.
- Adding the Prometheus registry adds no endpoint (the exposure list already names it).

**Re-check after implementation (T016): PASS.** V4 adds `projection_tried_at`, a `projection_*`
column written by the sweep alone (Principle I's sweep-only list); it is stamped whatever the row's
status, so it is outside `hearing_share_projection_guard`'s `FAILED`-only list (FR-044). The
`sweepSchedule` liveness contributor adds no endpoint. No gate is violated.

## Project Structure

### Documentation (this feature)

```text
specs/001-share-intake/
├── spec.md              # approved specification
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R22
├── data-model.md        # Phase 1: V2/V3 DDL, entities, state machines, update rules
├── quickstart.md        # Phase 1: gate, stack, sample message, phase-gate use
├── contracts/
│   ├── inbound-event.md # the subscription and message contract
│   ├── metrics.md       # meter names and bounded tags
│   ├── configuration.md # new properties, defaults, env vars, rules
│   └── schema.md        # pointer to data-model.md; V1 is never edited
├── checklists/          # spec quality checklist
└── tasks.md             # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

New (`+`), rewritten (`~`), deleted (`-`). Packages follow `.claude/rules/design_rules.md`:
nothing in `domain/` or `application/` imports a JMS, JDBC or HTTP type.

```text
src/main/java/uk/gov/hmcts/cp/resultsstore/
├── domain/
│   ├── + ShareIdentity.java            # record: hearingId, hearingDay, sharedAt + the three raw strings
│   ├── + ShareId.java                  # UUID v5, fixed namespace (research R4)
│   ├── + PayloadChecksum.java          # SHA-256 hex over UTF-8 (R5)
│   ├── + SharedDays.java               # record + factory: London and UTC days (R6)
│   ├── + ReceiptStatus.java            # RECEIVED, STORED, DUPLICATE, UNREADABLE, NO_IDENTITY
│   ├── + NonShareReason.java           # bounded reason codes + status + metric tag
│   ├── + ProjectionStatus.java         # OK, FAILED
│   ├── + KeyDetails.java               # record: the nullable key-detail values
│   ├── + DefendantRef.java             # record: caseId, defendantId, masterDefendantId
│   ├── + Projection.java               # sealed: Extracted(KeyDetails, defendants, anySubjectIsYouth) | Failed(reason, kind)
│   ├── + ExtractionFailureKind.java    # MISSING, WRONG_TYPE, INVALID_UUID, UNEXPECTED (+ tag)
│   ├── + IntakeOutcome.java            # STORED, DUPLICATE, NOT_A_SHARE, ALREADY_SETTLED (+ tags)
│   ├── + IntakeFailureCause.java       # LOCK_TIMEOUT, STATEMENT_TIMEOUT, DATABASE, OTHER (+ fromSqlState(String))
│   └── + SweepRowOutcome.java          # FIXED, FAILED_AGAIN, SKIPPED, ERROR, CANCELLED (+ tag)
├── application/
│   ├── + IntakeCommand.java            # record: messageId (nullable), deliveryCount, text (nullable)
│   ├── + IntakeResult.java             # record: outcome + ids for the MDC
│   ├── + ShareIdentityParser.java      # replaces PublicEventEnvelope: Reading = Share | NotShare (R7, R8)
│   ├── + KeyDetailsExtractor.java      # JsonNode -> Projection; EXTRACTOR_VERSION; catch RuntimeException only
│   ├── + IntakeService.java            # parse, extract, receipt tx, store tx, observer after commit
│   ├── + EventReceipts.java            # port: recordArrival(...) -> ReceiptState
│   ├── + ReceiptState.java             # record: status, shareId, attempts, inserted
│   ├── + ShareStore.java               # port: store(StoreRequest) -> StoreResult; sweepCandidates, payloadText,
│   │                                   #   recordReextraction, recordSweepAttempt
│   ├── + StoreRequest.java             # record: messageId, identity, shareId, days, checksum, text, projection
│   ├── + StoreResult.java              # sealed: Stored(shareId, storedAt, outOfOrder, parsedCopySkipped) | Duplicate(existingShareId)
│   ├── + IntakeObserver.java           # port: one method per metric event
│   ├── + RetryableIntakeException.java # carries stage + IntakeFailureCause; thrown by the persistence adapters
│   │                                   #   (they classify the SQLSTATE and rethrow); IntakeService counts it and rethrows
│   └── + ExtractionSweep.java          # one round: candidates, extract outside tx, per-row apply, count
├── persistence/
│   ├── + JdbcReceiptStore.java         # EventReceipts: upsert with CASE in SET; markStored/markDuplicate guarded on RECEIVED
│   ├── + JdbcShareStore.java           # ShareStore: storeTx with set_config timeouts, day lock, insert, chain, youth, receipt
│   ├── + ShareChain.java               # chain decisions under the lock (P/N lookups, clear-then-set, relink)
│   ├── + YouthFlags.java               # day recompute and propagation (R15)
│   └── + NulSafety.java                # pre-check for \u0000 and unpaired surrogate escapes (R8)
├── adapter/publicevents/
│   ├── ~ HearingResultedEventListener.java  # builds IntakeCommand; MDC; pause then rethrow on failure
│   ├── + RedeliveryPause.java          # min(2^n s, cap); Sleeper; interrupt handling (R14)
│   ├── + Sleeper.java                  # port for the pause, faked in tests
│   └── - PublicEventEnvelope.java      # replaced by ShareIdentityParser
├── config/
│   ├── PublicEventsConfig.java         # unchanged
│   ├── + IntakeProperties.java         # resultsstore.intake.* (contracts/configuration.md)
│   ├── + SweepProperties.java          # resultsstore.sweep.*
│   ├── + IntakeConfig.java             # registers properties, validates relations, wires TransactionTemplates and Sleeper
│   ├── + SweepSchedulingConfig.java    # the sweep and its schedule, only when publicevents and sweep are enabled
│   ├── + SweepSchedule.java            # dedicated scheduler, fixed delay; stop() waits a bounded time
│   ├── + SweepScheduleHealthIndicator.java # always registered: `sweepSchedule` in the liveness group;
│   │                                   #   DOWN once an Error ended the schedule, UP with no sweep
│   └── + MicrometerIntakeObserver.java # IntakeObserver -> Micrometer; no branches
└── filters/ActionHeaderFilter.java     # unchanged

src/main/resources/
├── application.yaml                    # ~ resultsstore.intake.* and resultsstore.sweep.* defaults
└── db/migration/
    ├── V1__create_event_receipt.sql    # unchanged, never edited
    ├── + V2__reshape_event_receipt.sql
    ├── + V3__create_share_store.sql
    └── + V4__projection_tried_at.sql   # the sweep's try stamp and its candidate index (T012)

src/test/java/uk/gov/hmcts/cp/resultsstore/
├── domain/        + ShareIdTest, PayloadChecksumTest, SharedDaysTest, NonShareReasonTest,
│                    IntakeFailureCauseTest, IntakeOutcomeTest
├── application/   + ShareIdentityParserTest, KeyDetailsExtractorTest (table-driven), IntakeServiceTest,
│                    ExtractionSweepTest
├── persistence/   ~ FlywayMigrationIT (V1→V3 and every DB rule); + JdbcReceiptStoreIT, JdbcShareStoreIT,
│                    ShareChainIT, YouthSeenIT, StoreTimeoutIT, ExtractionSweepIT, NulSafetyTest
├── adapter/publicevents/
│                  ~ HearingResultedEventListenerTest, ~ HearingResultedEventListenerIT (subscription shape);
│                  + RedeliveryPauseTest, IntakeIT (end to end on embedded Artemis + Postgres);
│                  - PublicEventEnvelopeTest
├── config/        + PublicEventsConfigTest, ConfigurationValidationTest, MicrometerIntakeObserverTest
├── integration/   + NoPayloadInLogsIT
└── support/       PostgresTestSupport, CapturedLog (exist); + EmbeddedBrokerSupport, SampleShares,
                   FailingFirstCommitConnectionFactory

src/test/resources/application-test.yaml  # ~ pause off, sweep off
scripts/container-smoke.sh                # ~ publish three messages, assert rows (T015)
build.gradle                              # ~ micrometer-registry-prometheus
```

**Structure Decision**: one Spring Boot service, packages as in the design rules (`domain/`,
`application/`, `persistence/`, `adapter/`, `config/`). The orchestration plan's provisional
package names (`intake/`, `projection/`, `sweep/`) are mapped onto these: intake and sweep logic
into `application/`, records and enums into `domain/`. Decisions with branches stay outside
`config/` so the coverage gate measures them (research R18).

## Phase plan

Mirrors the orchestration plan's Step 3. T001 (constitution 2.0.0 and rules) is done
(commit `03063b4`). Each task is test first; each phase passes the phase gate (quickstart §6)
before the next starts. Phase 3 must run before phase 4: the sweep reads rows only phase 3 writes.

### Phase 1 — schema and pure domain

| Task | Test first | Then | Covers |
|---|---|---|---|
| T002 | `FlywayMigrationIT`: V1→V3 apply; V2 refuses a non-empty V1 table; identity conflict inserts 0 rows; a second `is_latest` per day refused; non-hex checksum refused; text on a `STORED` receipt refused; `FAILED` without reason refused; partial identity on `NO_IDENTITY` accepted; NULL `payload_json` accepted; explicit `stored_seq` refused; `settled_at` rule; `expires_at` must be NULL | `V2__reshape_event_receipt.sql`, `V3__create_share_store.sql` exactly as data-model.md | FR-042, FR-043 |
| T003 | `ShareIdentityParserTest`, `ShareIdTest` (golden vectors incl. `…706Z` / `…7060Z`), `PayloadChecksumTest`, `SharedDaysTest` (both clock changes) | `ShareIdentityParser` (delete `PublicEventEnvelope` and its test), `ShareIdentity`, `ShareId`, `PayloadChecksum`, `SharedDays`, `ReceiptStatus`, `NonShareReason` | FR-007–FR-012, FR-016, FR-017 |
| T004 | `KeyDetailsExtractorTest`, table-driven: every key-detail path; missing optional → NULL + OK; wrong type and invalid UUID per field → FAILED with path reason; `RuntimeException` → `UNEXPECTED:<class>`; defendant merge; one defendant on two cases; youth three values incl. no defendants; reason never holds payload text | `KeyDetailsExtractor` (`EXTRACTOR_VERSION = 1`), `Projection`, `KeyDetails`, `DefendantRef`, `ExtractionFailureKind`, `ProjectionStatus` | FR-018, FR-019, FR-026, FR-029–FR-031 |

### Phase 2 — receipt, service, listener

| Task | Test first | Then | Covers |
|---|---|---|---|
| T005 | `JdbcReceiptStoreIT`: first arrival `RECEIVED`, attempts 1; redelivery attempts + 1 and status unchanged; non-share straight to end state with `settled_at`; end state never changes; `RETURNING` always gives the status; `markStored`/`markDuplicate` only from `RECEIVED`; synthetic `sha256:` key | `JdbcReceiptStore`, `EventReceipts`, `ReceiptState` | FR-002–FR-005, FR-008, FR-009 |
| T006 | `IntakeServiceTest` (mock ports, no Spring): non-share recorded without touching the store; settled redelivery short-circuits; duplicate and stored paths; receipt and store failures propagate; observer called after the templates return, failure counted once; single-exit shape | `IntakeService`, `IntakeCommand`, `IntakeResult`, `IntakeObserver`, `StoreRequest`, `StoreResult`, `IntakeOutcome`, `IntakeFailureCause`, `RetryableIntakeException` | FR-004, FR-006, FR-010, FR-021, FR-040 |
| T007 | `HearingResultedEventListenerTest` (MDC set and cleared, pause then rethrow, non-text body), `RedeliveryPauseTest` (2^n capped, interrupt), `PublicEventsConfigTest` (transacted, no transaction manager, shared, concurrency 1), `ConfigurationValidationTest` (every rule in contracts/configuration.md) | listener rewrite, `RedeliveryPause`, `Sleeper`, `IntakeProperties`, `SweepProperties`, `IntakeConfig`, `application.yaml`, `application-test.yaml` | FR-001, FR-006, FR-041, FR-045, FR-046 |

### Phase 3 — store transaction, chain, end to end

| Task | Test first | Then | Covers |
|---|---|---|---|
| T008 | `JdbcShareStoreIT`: one share writes share, payload (text, bytes, parsed), defendants, day row, receipt `STORED`; conflict → `DUPLICATE` with the **existing** id; `\u0000` → parsed copy NULL + counter; 2.4 MB payload byte for byte; failure part way → nothing left, receipt `RECEIVED` | `JdbcShareStore`, `NulSafety`, `ShareStore` | FR-013–FR-017, FR-020 |
| T009 | `ShareChainIT` (first, newest, late; T1/T3 then T2; clear before set), `YouthSeenIT` (three values, sticky TRUE, propagation to every share of the day) | `ShareChain`, `YouthFlags` | FR-022–FR-028 |
| T010 | `StoreTimeoutIT`: day row held by a second connection → gives up within lock timeout + 1 s, no rows; the next transaction on the same pooled connection has default timeouts | timeouts in `JdbcShareStore` | FR-020, SC-009 |
| T011 | `IntakeIT` (embedded Artemis + Postgres): stored and acknowledged; store fails once then `STORED` with attempts 2; first `session.commit()` fails → one share; unreadable and no-identity acknowledged, not redelivered; persistent failure ends on the dead-letter address with attempts shown; two containers, 50 out-of-order shares → one latest, gapless chain, count 50; same share twice at once → one `STORED`, one `DUPLICATE`; null message id | `EmbeddedBrokerSupport`, `SampleShares`, `FailingFirstCommitConnectionFactory`; fixes found | US1–US4, US6; SC-001–SC-005, SC-007 |

### Phase 4 — sweep, observability, gate

| Task | Test first | Then | Covers |
|---|---|---|---|
| T012 | `ExtractionSweepTest` (selection rule, per-row failure counted and the round continues), `ExtractionSweepIT` (FAILED → OK after a version raise; UNEXPECTED stops at 3 attempts; two sweeps at once process each row once, with a held day lock and a latch) | `ExtractionSweep`, sweep methods on `JdbcShareStore`, `SweepSchedulingConfig`, `SweepRowOutcome` | FR-033–FR-037; US5; SC-006 |
| T013 | `MicrometerIntakeObserverTest` (every name and tag set; no tag outside the lists; lag timer), `NoPayloadInLogsIT` (marker text never in a captured line) | `MicrometerIntakeObserver`, `micrometer-registry-prometheus` in `build.gradle` | FR-038–FR-041; US7; SC-010 |
| T014 | — (gate task) | full gate `build pmdMain pmdTest jacocoTestReport jacocoTestCoverageVerification`; fix `OnlyOneReturn` / `AvoidDuplicateLiterals` by shape, or a reasoned site suppression | SC-011 |
| T015 | extend `scripts/container-smoke.sh` first so it fails on the old build: publish a real-shaped message, the same again, an unreadable one; assert with `psql` the receipts `STORED` / `DUPLICATE` / `UNREADABLE`, one share, its payload, its defendants, its day row | any fix the smoke finds | FR-047; SC-012 |

Rules for every task: a unit test per class; an IT on Testcontainers Postgres or the embedded
broker for every persistence and messaging path; latches or Awaitility, never sleeps; no payload
text in assertion or log output; one commit per task, red run quoted before green.

## Complexity Tracking

No constitution principle is violated, so nothing needs justifying here.
