---

description: "Task list for feature 001: share intake"
---

# Tasks: Share intake

**Input**: Design documents from `/specs/001-share-intake/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (inbound-event, metrics,
configuration, schema), quickstart.md; constitution 2.0.0

**Tests**: Mandatory (Principle X). Each task names its tests first, then the production files. The
implementer writes the tests, runs them, records the RED run (a failing assertion, never a compile
error) under the task, then writes the minimum production code and records the GREEN run. One
commit per task, test at or before the production code.

**Organisation**: four phases, as in plan.md "Phase plan". Each phase is one run of
`.claude/workflows/phase-gate.js` over a contiguous task range. The user stories cut across the
phases (the write path is built layer by layer), so every task carries the tags of the stories it
serves; the story-to-task map is under "Dependencies".

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (disjoint files, no dependency on an unfinished task)
- **[USn]**: the user story (spec.md) the task serves
- Paths are repository-relative. Abbreviations are not used: `src/main/java/uk/gov/hmcts/cp/resultsstore/…`
  and `src/test/java/uk/gov/hmcts/cp/resultsstore/…` are written out in full.

## Phase-gate invocations

One run per phase, in order. `baseCommit` is `git rev-parse HEAD` in the tree at the moment the
phase starts (for phase 1, the commit that adds this file). Each run needs the previous phase's
run to have ended with every reviewer at PASS.

| Phase | Invocation (paths absolute) |
|---|---|
| 1 | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/001-share-intake", "tasks": ["T002","T003","T004"], "baseCommit": "<HEAD at phase 1 start>", "codex": true}` |
| 2 | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/001-share-intake", "tasks": ["T005","T006","T007"], "baseCommit": "<HEAD at phase 2 start>", "codex": true}` |
| 3 | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/001-share-intake", "tasks": ["T008","T009","T010","T011"], "baseCommit": "<HEAD at phase 3 start>", "codex": true}` |
| 4 | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/001-share-intake", "tasks": ["T012","T013","T014","T015"], "baseCommit": "<HEAD at phase 4 start>", "codex": true}` |

T016 is not a phase-gate run: it follows phase 4 (see "Final").

## Gate used by every task's "Done when"

`flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew build pmdMain pmdTest jacocoTestReport`
exits 0 (from T014 on, `jacocoTestCoverageVerification` is added). Written below as **the gate**.

## Rules for every task (orchestration plan, "Test coverage rule")

- A unit test per production class; an IT on Testcontainers Postgres (`support/PostgresTestSupport`)
  or the embedded Artemis broker for every persistence and messaging path.
- Latches or Awaitility, never `Thread.sleep` (`failFast` is on).
- No payload or message text in assertion messages or log output; ids only.
- Explicit imports only; constructor injection; records; PMD clean with `OnlyOneReturn` on.
- Migration `V1__create_event_receipt.sql` is never edited.

## Wiring note (applies from T005 to T013)

The `test` profile has no datasource and the listener is off, so context-load tests (for example
`ActuatorIntegrationTest`, `AuthzIT`) run without Docker. That must stay true:
- the intake beans (listener, `IntakeService`, `JdbcReceiptStore`, `JdbcShareStore`, the observer,
  the sweep) are created only when `resultsstore.publicevents.enabled` is true (and, for the sweep,
  `resultsstore.sweep.enabled`), registered from `IntakeConfig` / `SweepSchedulingConfig`;
- until the real bean lands, a Spring-context IT that needs `ShareStore` (real in T008) or
  `IntakeObserver` (real in T013) stands it in with `@MockitoBean`; the task that lands the real
  bean removes the stand-in.

---

## Phase 0: Guidance alignment (done)

- [X] T001 Align the constitution (2.0.0, Sync Impact Report) and the repository rules with design page v45 in .specify/memory/constitution.md, .claude/rules/workflow.md, .claude/rules/technical-rules.md, .claude/rules/design_rules.md, .claude/agents/code-reviewer.md, .claude/agents/qa.md, .claude/agents/spec-validator.md, .claude/agents/software-engineer.md, CLAUDE.md, src/main/resources/application.yaml
  - Done: commit `03063b4` (`docs: align constitution and rules with design page v45`).

---

## Phase 1: Schema and pure domain (T002–T004)

**Purpose**: the tables, the identity rules and the key-details extraction, with no I/O in the Java
code. Blocks every later phase.

**Independent test**: `FlywayMigrationIT` proves every database rule; the domain and extractor
tests prove identity, share id, checksum, shared days and extraction without Spring.

- [X] T002 [P] [US1] [US2] [US3] [US4] [US5] Test first: `FlywayMigrationIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/FlywayMigrationIT.java; then migrations src/main/resources/db/migration/V2__reshape_event_receipt.sql and src/main/resources/db/migration/V3__create_share_store.sql, exactly as data-model.md (V1 untouched)
  - Cases: V1→V3 apply on an empty database; V2 refuses a non-empty V1 `event_receipt`; identity conflict on `hearing_share` inserts 0 rows; a second `is_latest` for one day refused by the partial unique index; non-hex or wrong-length `payload_sha256` refused; `message_text` on a `STORED` receipt refused; status outside the five values refused; `FAILED` without `projection_reason` refused; `projection_status` outside OK/FAILED refused; partial identity on a `NO_IDENTITY` receipt accepted; NULL `payload_json` accepted; explicit `stored_seq` refused; `settled_at` rule (set on end states only); `expires_at` must be NULL; `share_defendant` primary key (share_id, case_id, defendant_id) refuses a repeat.
  - Covers: FR-042, FR-043, FR-044 (columns that may change).
  - Done when: every case above green in `FlywayMigrationIT`; the gate green.
  - RED: `./gradlew test --tests '*FlywayMigrationIT'` before V2/V3 existed: 3 tests completed, 3 failed (failFast), each
    `java.lang.AssertionError: [applied migrations] Expecting ArrayList: ["1"] to contain: ["2", "3"]`
    (e.g. `startup_on_an_empty_database_should_apply_v1_to_v3`).
  - GREEN: with V2/V3 added: `FlywayMigrationIT` 30 tests, 0 failures (root 2, `event_receipt` 10,
    `hearing_share and its children` 18); the gate exits 0.

- [X] T003 [P] [US1] [US2] [US3] Test first: `ShareIdentityParserTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ShareIdentityParserTest.java, `ShareIdTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ShareIdTest.java, `PayloadChecksumTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadChecksumTest.java, `SharedDaysTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/SharedDaysTest.java, `NonShareReasonTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/NonShareReasonTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareIdentityParser.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ShareIdentity.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ShareId.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadChecksum.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SharedDays.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReceiptStatus.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/NonShareReason.java; delete src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/PublicEventEnvelope.java and src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/PublicEventEnvelopeTest.java (the current listener moves to the parser in the same commit so the build stays green)
  - Cases: parser returns `Share` for a valid body and `NotShare(reason)` for each of `not_json`, `not_object`, `nul_character`, `missing_/invalid_hearing_id`, `missing_/invalid_hearing_day`, `missing_/invalid_shared_time` (research R7, R8); the three raw identity strings kept as sent; share id golden vectors including `…706Z` vs `…7060Z` giving different ids and the fixed namespace `3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60`; SHA-256 hex over UTF-8 (empty, ASCII, multi-byte); London and UTC days either side of both clock changes; each `NonShareReason` maps to its status and metric tag.
  - Covers: FR-007–FR-012, FR-016, FR-017.
  - Done when: the five test classes green; the gate green.
  - RED: against compile-safe seams (signatures only, placeholder bodies), `./gradlew test` over the five
    classes plus `CanonicalUuidTest` and `HearingResultedEventListenerTest`: 23 tests completed, 12 failed
    (failFast), all assertion failures, e.g. `PayloadChecksumTest > "empty"`:
    `AssertionFailedError: expected: "e3b0c442…b855" but was: ""`; `CanonicalUuidTest`:
    `Expecting Optional to contain: 6f1f0c3e-… but was empty.`; `SharedDaysTest` (6 of 6).
  - GREEN: 89 tests, 0 failures (`ShareIdentityParserTest` 46, `NonShareReasonTest` 11, `CanonicalUuidTest`
    11, `ShareIdTest` 6, `SharedDaysTest` 6, `PayloadChecksumTest` 4, `HearingResultedEventListenerTest` 5);
    the gate exits 0.
  - Notes: the canonical-UUID check is shared by the parser and the extractor (T004), so it lives in
    `domain/CanonicalUuid.java` with `CanonicalUuidTest` (one file beyond the list above). PMD
    `ShortMethodName` names the day factory `SharedDays.from(Instant)`. `NonShareReason` also gives the
    metric's `status` tag (`statusTag()`). A non-string identity value counts as missing (R7's
    "missing / not a string"), and a blank body reads as `NOT_JSON`. The listener now logs a non-share's
    bounded reason instead of the parse exception's class.

- [X] T004 [P] [US1] [US5] Test first: table-driven `KeyDetailsExtractorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/KeyDetailsExtractorTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/KeyDetailsExtractor.java (`EXTRACTOR_VERSION = 1`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/Projection.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/KeyDetails.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/DefendantRef.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ExtractionFailureKind.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ProjectionStatus.java
  - Cases: every key-detail path (FR-018) read; a missing optional field → NULL and `Extracted`; wrong type and invalid UUID per field → `Failed` with a reason naming the path and the kind; a `RuntimeException` inside extraction → `Failed` `UNEXPECTED:<class>` (catch `RuntimeException`, never `Throwable`); defendants merged per (case, defendant); one defendant on two cases → two rows; `any_subject_is_youth` TRUE / FALSE / NULL including no defendants and an unstated `isYouth`; `youth_court_id` recorded as stated; the reason never contains payload text; each `ExtractionFailureKind` has its metric tag.
  - Covers: FR-018, FR-019, FR-026, FR-029–FR-031.
  - Done when: `KeyDetailsExtractorTest` green; the gate green.
  - RED: against compile-safe seams (the types, and `extract` returning `Failed("SEAM", UNEXPECTED)`),
    `./gradlew test --tests '*KeyDetailsExtractorTest'`: 6 tests completed, 4 failed (failFast), e.g.
    `failure_kind_should_have_its_lower_case_metric_tag [MISSING]`: `AssertionFailedError: expected: "missing"
    but was: ""`; and `--tests '*KeyDetailsExtractorTest*full_payload*'`: 1 failed,
    `AssertionFailedError: expected: Extracted[keyDetails=KeyDetails[courtCentreId=9d2e4f6a-…], …]
    but was: Failed[reason=SEAM, kind=UNEXPECTED]`.
  - GREEN: `KeyDetailsExtractorTest` 80 tests, 0 failures; the gate exits 0.
  - Notes: PMD 7.22 reports `AvoidCatchingGenericException` (errorprone), not only design.xml as research
    R17 assumed, so `extract` carries a site suppression with its reason (catch `RuntimeException` only, as
    the task requires). An optional parent of the wrong type (for example `hearing.courtCentre` as a string)
    reads as absent, leaving its key details empty; only the fields in data-model's validation table are
    type-checked. A repeated (case, defendant) keeps the first stated `masterDefendantId`. An anonymous
    exception class is named by its binary name without the package, bounded to 120 characters.

**Checkpoint**: phase-gate run 1 ends with code-reviewer, qa, spec-validator and Codex at PASS.

---

## Phase 2: Receipt, service, listener (T005–T007)

**Purpose**: the receipt transaction, the intake orchestration (with the store behind a port) and
the listener with its pause and settings. Depends on phase 1.

**Independent test**: `JdbcReceiptStoreIT` proves the receipt rules on Postgres; `IntakeServiceTest`
proves the flow with mocked ports; the listener and configuration tests prove the JMS edge.

- [ ] T005 [US2] [US3] [US6] Test first: `JdbcReceiptStoreIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcReceiptStoreIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcReceiptStore.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/EventReceipts.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ReceiptState.java
  - Cases: first arrival → `RECEIVED`, attempts 1; a redelivery → attempts + 1, status unchanged, `delivery_count` and `last_received_at` updated; a non-share goes straight to `UNREADABLE` / `NO_IDENTITY` with reason, text and `settled_at`; an end state never changes on a later arrival; `RETURNING` always gives the current status and `inserted`; `markStored` / `markDuplicate` act only from `RECEIVED`; a null `JMSMessageID` is keyed `sha256:<checksum>`.
  - Covers: FR-002–FR-005, FR-008, FR-009.
  - Done when: `JdbcReceiptStoreIT` green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T006 [US1] [US2] [US3] [US6] Test first: `IntakeServiceTest` (mocked ports, no Spring) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/IntakeServiceTest.java, `IntakeOutcomeTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeOutcomeTest.java, `IntakeFailureCauseTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeFailureCauseTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeService.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeCommand.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeResult.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeObserver.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareStore.java (port, `store` only; sweep methods come in T012), src/main/java/uk/gov/hmcts/cp/resultsstore/application/StoreRequest.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/StoreResult.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/RetryableIntakeException.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeOutcome.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeFailureCause.java
  - Cases: a non-share is recorded on its receipt and the store is never called; a redelivery whose receipt is settled short-circuits to `ALREADY_SETTLED`; stored path and duplicate path; extraction runs before the store call and a `Failed` projection still stores; receipt and store failures propagate as `RetryableIntakeException` and are counted once; the observer is called only after the template returns; `IntakeFailureCause.fromSqlState` maps `55P03`, `57014`, other, non-database; every outcome has its tag; single-exit shape (`OnlyOneReturn`).
  - Covers: FR-004, FR-006, FR-010, FR-021, FR-040.
  - Done when: the three test classes green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T007 [US1] [US6] [US7] Test first: `HearingResultedEventListenerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/HearingResultedEventListenerTest.java, `RedeliveryPauseTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/RedeliveryPauseTest.java, `PublicEventsConfigTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/PublicEventsConfigTest.java, `ConfigurationValidationTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java, and the subscription-shape update of src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/HearingResultedEventListenerIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/HearingResultedEventListener.java (rewrite), src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/RedeliveryPause.java, src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/Sleeper.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java, src/main/resources/application.yaml, src/test/resources/application-test.yaml
  - Cases: the listener builds `IntakeCommand(messageId, deliveryCount, text|null)` and a non-text body becomes a null text; MDC holds message id, share id and hearing id and is cleared in `finally`; on `RetryableIntakeException` it pauses then rethrows; logs hold ids only; pause is `min(2^deliveryCount s, cap)`, off when disabled, and an interrupt restores the flag and rethrows; the container factory is transacted, has no JMS transaction manager, shared durable subscription, concurrency 1; every rule in contracts/configuration.md refuses a bad value at start; the subscription name, topic and selector are unchanged; the context-load tests still start without a datasource (wiring note).
  - Covers: FR-001, FR-006, FR-041, FR-045, FR-046.
  - Done when: the four test classes and `HearingResultedEventListenerIT` green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

**Checkpoint**: phase-gate run 2 ends with every reviewer at PASS.

---

## Phase 3: Store transaction, chain, end to end (T008–T011)

**Purpose**: the one store transaction (day lock, share, payload, defendants, chain, youth,
receipt), its timeouts, and the end-to-end proof on the embedded broker. Depends on phase 2.
Must finish before phase 4: the sweep reads rows only this phase writes.

**Independent test**: `IntakeIT` publishes to the embedded Artemis broker and asserts the rows of
US1–US4 and US6 on Testcontainers Postgres.

- [ ] T008 [US1] [US2] Test first: `JdbcShareStoreIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStoreIT.java, `NulSafetyTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/NulSafetyTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java, src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/NulSafety.java (registered in src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java; `@MockitoBean ShareStore` stand-ins removed)
  - Cases: one share writes the share, payload (text, `text_bytes`, parsed copy), defendant rows, day row and receipt `STORED` in one transaction; identity conflict → `DUPLICATE` with the existing share's id looked up by identity; `\u0000` or an unpaired surrogate → `payload_json` NULL and the skip reported; a 2.4 MB payload stored byte for byte with a matching checksum; a failure part way leaves no share, payload, defendant or day change and the receipt still `RECEIVED`; extraction is never run inside the transaction; SQLSTATE classified into `RetryableIntakeException`.
  - Covers: FR-013–FR-017, FR-020; SC-008.
  - Done when: both test classes green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T009 [US4] [US1] Test first: `ShareChainIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/ShareChainIT.java, `YouthSeenIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/YouthSeenIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/ShareChain.java, src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/YouthFlags.java, called from src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java
  - Cases: first share of a day is latest with no predecessor; a newer share clears the old latest before it is set and points at it; T1, T3 then T2 → chain T1 ← T2 ← T3, T3 still latest, T2 `arrived_out_of_order`; `share_count` + 1 per stored share; youth three values: TRUE sticky, NULL if any share NULL, else FALSE; `day_youth_seen` set on every share of the day when the day flag changes.
  - Covers: FR-022–FR-028.
  - Done when: both ITs green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T010 [US6] Test first: `StoreTimeoutIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/StoreTimeoutIT.java; then the per-transaction `set_config(…, true)` timeouts in src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java
  - Cases: day row held by a second connection (latch) → the store gives up within lock timeout + 1 s with `lock_timeout` cause and no rows; the next transaction on the same pooled connection has the default timeouts (no leak).
  - Covers: FR-020; SC-009.
  - Done when: `StoreTimeoutIT` green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T011 [US1] [US2] [US3] [US4] [US6] Test first: `IntakeIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/IntakeIT.java, with support src/test/java/uk/gov/hmcts/cp/resultsstore/support/EmbeddedBrokerSupport.java, src/test/java/uk/gov/hmcts/cp/resultsstore/support/SampleShares.java, src/test/java/uk/gov/hmcts/cp/resultsstore/support/FailingFirstCommitConnectionFactory.java; then any production fix the IT finds (in the files of T005–T010)
  - Cases: a share is stored and acknowledged; store fails once then `STORED` with attempts 2; first `session.commit()` fails → one share, receipt `STORED` on redelivery; unreadable and no-identity bodies acknowledged and not redelivered; a persistent failure ends on the dead-letter address with its attempts on the receipt; two listener containers on the one shared subscription with 50 out-of-order shares of one day → one latest, gapless chain, count 50; the same share twice at once → one `STORED`, one `DUPLICATE`; a message with no message id stored under its `sha256:` key; every receipt ends in an end state.
  - Covers: US1–US4, US6; SC-001–SC-005, SC-007.
  - Done when: `IntakeIT` green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

**Checkpoint**: phase-gate run 3 ends with every reviewer at PASS. The MVP (US1) is complete here.

---

## Phase 4: Sweep, observability, gate (T012–T015)

**Purpose**: the extraction sweep, the metrics, the full quality gate and the container smoke.
Depends on phase 3.

**Independent test**: `ExtractionSweepIT` turns a `FAILED` row `OK`; `MicrometerIntakeObserverTest`
and `NoPayloadInLogsIT` prove the metric and log rules; the container smoke proves the real stack.

- [ ] T012 [US5] Test first: `ExtractionSweepTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweepTest.java, `ExtractionSweepIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/ExtractionSweepIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweep.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SweepRowOutcome.java, sweep methods on src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareStore.java and src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfig.java
  - Cases: selects `FAILED` rows only, retried when `projection_version` is older or the reason is `UNEXPECTED` below `max-attempts`; extraction outside the row transaction from `payload_text`, never `payload_json`; per row: day lock, share `FOR UPDATE`, still-`FAILED` re-check, else `SKIPPED`; success fills key details and defendant rows and sets `OK`; failure records the new reason, version and attempts + 1; one row's exception is counted `ERROR` and the round continues; `FAILED` → `OK` after a version raise; `UNEXPECTED` stops at 3 attempts; two sweeps at once (held day lock, latch) process each row once; dedicated `TaskScheduler`, bean absent when `resultsstore.sweep.enabled=false`; no ShedLock.
  - Covers: FR-033–FR-037; SC-006.
  - Done when: both test classes green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T013 [US7] Test first: `MicrometerIntakeObserverTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserverTest.java, `NoPayloadInLogsIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/NoPayloadInLogsIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserver.java (registered in src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java; `@MockitoBean IntakeObserver` stand-ins removed), `io.micrometer:micrometer-registry-prometheus` in build.gradle
  - Cases: every counter and the lag timer in contracts/metrics.md registered with exactly its tag sets against a `SimpleMeterRegistry`; lag `stored_at − shared_at` clamped at zero; a registry-wide check fails on any tag value outside the lists or matching a UUID or date pattern; `/actuator/prometheus` exposes `resultsstore_*`; a marker string inside a payload never appears in any captured log line across the store, duplicate, non-share and failure paths.
  - Covers: FR-038–FR-041; SC-010.
  - Done when: both test classes green; the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

- [ ] T014 Full quality gate: `flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew build pmdMain pmdTest jacocoTestReport jacocoTestCoverageVerification`; fix `OnlyOneReturn` / `AvoidDuplicateLiterals` and coverage gaps by code shape (or a reasoned per-site suppression) in the files of T002–T013; add any missing test before the code it covers
  - Covers: SC-011 (line ≥ 0.88, branch ≥ 0.85, PMD clean).
  - Done when: that command exits 0 with the coverage figures recorded below.
  - RESULT: _to be recorded_

- [ ] T015 [US1] [US2] [US3] Test first: extend scripts/container-smoke.sh so it fails on the pre-001 build: publish a real-shaped `hearing-resulted` message (identifiers only, synthetic values, shape from quickstart.md §4) to the compose Artemis with `CPPNAME`, the same message again, and an unreadable body; assert with `psql` receipts `STORED` / `DUPLICATE` (same `share_id`) / `UNREADABLE`, one `hearing_share` (latest, `OK`), one `hearing_share_payload`, its `share_defendant` rows and one `hearing_day_head` with `share_count = 1`; then any fix the smoke finds
  - Covers: FR-047; SC-001, SC-012.
  - Done when: `./scripts/container-smoke.sh` exits 0 locally against the compose stack (and fails on the commit before T015's production fixes, recorded as RED); the gate green.
  - RED: _to be recorded_
  - GREEN: _to be recorded_

**Checkpoint**: phase-gate run 4 ends with every reviewer at PASS.

---

## Final: Consistency and PR preparation

- [ ] T016 Run `/speckit-analyze` over specs/001-share-intake/spec.md, specs/001-share-intake/plan.md and specs/001-share-intake/tasks.md and resolve every CRITICAL/HIGH finding (including the FR-008 narrowing for raw U+0000 noted in plan.md); walk specs/001-share-intake/quickstart.md §3–§5 against the compose stack; prepare the PR description naming the principles touched (I, II, IV, V, VI, VIII, IX, X, XI, XII) and the success criteria evidence
  - Done when: `/speckit-analyze` reports no CRITICAL or HIGH finding; the quickstart steps give the expected rows and metrics; the PR text is drafted (no push without approval); the gate green.

---

## Dependencies & Execution Order

### Phase dependencies

- T001 (done) → Phase 1 → Phase 2 → Phase 3 → Phase 4 → T016. Each phase starts only after the
  previous phase-gate run has ended at PASS.
- Phase 2 needs T002 (receipt table), T003 (parser, reasons, statuses) and T004 (extractor).
- Phase 3 needs the ports and service of T005–T007.
- Phase 4 needs the rows phase 3 writes (sweep) and the full write path (metrics, smoke).

### Within phases

- Phase 1: T002, T003, T004 touch disjoint files and can run in any order.
- Phase 2: T005 → T006 (mocks the `EventReceipts` port T005 adds) → T007 (calls `IntakeService`).
- Phase 3: T008 → T009 → T010 (all touch `JdbcShareStore`) → T011.
- Phase 4: T012 → T013 (observer covers sweep outcomes) → T014 → T015.

### User story → tasks

| Story | Tasks | Independently proven by |
|---|---|---|
| US1 Store a share (P1, MVP) | T002, T003, T004, T006, T007, T008, T009, T011, T015 | `IntakeIT` stored path; smoke `STORED` |
| US2 Drop a duplicate (P2) | T002, T003, T005, T006, T008, T011, T015 | `IntakeIT` duplicate cases; smoke `DUPLICATE` |
| US3 Record a non-share (P3) | T002, T003, T005, T006, T011, T015 | `IntakeIT` unreadable / no-identity; smoke `UNREADABLE` |
| US4 Late share, concurrent shares (P4) | T002, T009, T011 | `ShareChainIT`; `IntakeIT` 50 shares on two consumers |
| US5 Extraction failure and sweep (P5) | T002, T004, T012 | `ExtractionSweepIT` |
| US6 Database outage mid-store (P6) | T005, T006, T007, T010, T011 | `IntakeIT` fail-once and first-commit cases; `StoreTimeoutIT` |
| US7 Operators can see what happened (P7) | T007, T013 | `MicrometerIntakeObserverTest`; `NoPayloadInLogsIT` |

## Parallel example: Phase 1

```text
Task: "T002 FlywayMigrationIT, then V2__reshape_event_receipt.sql and V3__create_share_store.sql"
Task: "T003 ShareIdentityParserTest / ShareIdTest / PayloadChecksumTest / SharedDaysTest / NonShareReasonTest, then the domain types"
Task: "T004 KeyDetailsExtractorTest, then KeyDetailsExtractor and the projection types"
```

No other phase has parallel tasks: each later task shares files with, or calls, the one before it.

## Implementation strategy

1. Phases 1–3 deliver the MVP: US1 end to end, with US2, US3, US4 and US6 proven in `IntakeIT`.
2. Phase 4 adds US5 (sweep) and US7 (metrics), then the full gate and the container smoke.
3. T016 checks consistency and prepares the PR; Sachin merges.

## Notes

- Ticks (`[X]`) and the RED / GREEN lines are written by the implementer in the commit that
  completes the task.
- A task's commit message follows Conventional Commits and names the task id.
- Where a task finds the design documents silent, the implementer takes the option that changes
  the least behaviour and records it under the task.
