# Implementation Plan: Operations API

**Branch**: `004-operations-api` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/004-operations-api/spec.md`

## Summary

Build the four support endpoints under `/operations`: re-run extraction, extraction status, receipts and
the daily reconciliation, for "Second Line Support" only, audited, never returning a payload or a
message's text. The contract comes first, as in spec 003: the four operations go into the contract
repository `hmcts/api-cp-crime-results-store` (the read API's jar, tag `operations`, generated
`OperationsApi`; D-OPS-CONTRACT, pending Sachin), the service pins its draft and mirrors it, and the allow
rules and route entries land before any serving code. The contract is released as `v0.3.0` at the end.

A rerun is a **request, not a write**: the `POST` records a request and one item per matched share in
one transaction, with counts taken from the very statements that insert the items, and answers `202`.
Each sweep round then claims pending items (`SKIP LOCKED`, so pods share the queue), re-reads each
share's stored working copy and rewrites an `OK` share's key details **in place**. The share stays `OK`,
keeps its `storedSeq`, never loses a `true` youth subject, and is never overwritten by an older extractor;
a `false`-to-`true` youth change is held until consumers can be told. Migration V6 replaces the share
guard so the database refuses any such rewrite unless a pending item names the row, with each rule
named. Each pod records its last sweep round, so the status can tell "ran, nothing to do" from "not
running". Receipts and the reconciliation are read on demand from indexed columns.

004 builds on spec 003's web edge as built (`main` at `8ea9980`): route table, action filter, `415`
guard, bounded errors, the connector's error report, the rules test, the contract jar with its drift and
contract tests, and its names. Detail: [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/).

## Technical Context

**Language/Version**: Java 25
**Primary Dependencies**: Spring Boot 4.1 (webmvc, jdbc, flyway, actuator); `cp-auth-rules-filter` 1.0.7
and `cp-audit-filter-springboot` 1.0.5 (already dependencies); the contract jar
`uk.gov.hmcts.cp:api-cp-crime-results-store` (`0.2.0` today; a `rs-<sha7>` draft while 004 is built;
`0.3.0` at the end); Jackson 3 (`tools.jackson`); Micrometer. No new dependency
**Storage**: PostgreSQL 16 (local and tests); migration `V6__operations.sql` (three tables, guards, a
replaced share guard, three indexes; data-model.md)
**Testing**: JUnit 5, Mockito, AssertJ; a `@WebMvcTest` slice of `OperationsController`;
`@SpringBootTest(webEnvironment = RANDOM_PORT)` on a real server for `OperationsApiIT` and `AuditIT`, as
spec 003's `ReadApiIT` (the authorisation library's `sendError` reaches `/error` only on a real
container); Testcontainers `postgres:16` (`support/PostgresTestSupport`); in-process WireMock 3.13.2 for
usersgroups (`support/UsersGroupsStub`, matched on `CJSCPPUID`); embedded Artemis for the audit topic;
KIE for the rule tests; the compose smoke over `curl` and `jq`
**Target Platform**: Linux container on AKS (2 or more pods), Gradle build
**Project Type**: single Spring Boot service (`uk.gov.hmcts.cp.resultsstore`)
**Performance Goals**: a request of 200,000 shares written within its 120 s transaction (40 chunks of
5,000); each sweep round claims 200 items in one statement; about 57,600 items a day per pod at the
default pace, more with more pods; status, receipts and reconciliation are a few index scans each
**Constraints**: no SQL built from input; no operations query names `hearing_share_payload` or
`message_text`; the rerun reason and the operator id never logged, returned or tagged; bounded problem
bodies only; JaCoCo 0.88 line / 0.85 branch; PMD 7.22.0 clean on main and test (`OnlyOneReturn`: single
exit or a site suppression with a reason); no wildcard imports; V1 to V5 never edited; the share guard's
fixed-columns branch byte-identical to V3
**Scale/Scope**: about 4,800 shares a day; a 31-day rerun is about 150,000 items. About 40 new
production classes, 20 changed (the response types are the jar's generated models); 11 tasks in four
phases

Every point above is settled in [research.md](research.md) or is a row of spec.md *Decisions pending
Sachin* with its default applied.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution 2.2.0 is in this tree (spec 003's amendment: Principle VII's derived actions, counted
refusals and payload audit marker; Principle II's no-`_metadata` clause). The check below is made against
it. T011 amends it to 2.3.0 (MINOR; Principle I; research R21); the one deviation is recorded under
*Complexity Tracking*.

| Principle | How this feature satisfies it | Gate |
|---|---|---|
| I. Every share is an immutable version | A share's facts, payload and `stored_seq` never change (V3's fixed-columns branch, unchanged). Key details and `projection_*` change by the extraction sweep alone, re-read from the stored working copy, as I already says. **New freedom**: an `OK` share is re-read in place while a pending rerun item names it, and stays `OK` (V6 guard; 2.3.0 wording in T011) | PASS with the recorded deviation (T005 to T011) |
| II. The payload is the source of truth | The rerun re-reads `payloadForExtraction` (the working copy, or the text when it is empty), unchanged from 002. The rerun exists because the columns can be rebuilt from the payload | PASS |
| III. Consumers search indexed columns | Operations reads use indexed columns only (`OperationsQueriesPlanIT`); none names `hearing_share_payload` (`OperationsSqlTest`). The sweep's re-read is the extraction, not a consumer query | PASS |
| IV. No business rules | The store re-reads what the payload states. Holding a `false`-to-`true` youth change is about how a change reaches consumers, not about the case; the held share is listed and counted (research R9). Note: while held, that column lags its payload | PASS (with the note) |
| V. Never refuse to store | Intake unchanged. A rerun never moves a share to `FAILED` or drops it | PASS |
| VI. Idempotent, transactional intake | Intake unchanged. Rerun writes take the hearing-day lock first, as intake does (`RerunConcurrencyIT`) | PASS |
| VII. Default-deny authorisation | One allow rule per action, "Second Line Support" only, matching method and path; `deny-when-no-rules` true; actions derived by 003's filter; no operations response holds a payload. As 2.2.0 says: *every request that reaches an endpoint is audited; a request refused by a filter, by the connector or by authorisation is counted*. The audit marker covers the two payload actions only; operations events are the library's own, with no payload in them | PASS |
| VIII. Observability through Azure Monitor | Rerun requests, queued shares, refusals, every item outcome, abandoned and held items, finished requests, failed round records: all registered at start; R1 and R2 visible on demand. The "reconciliation does not run" alert waits for the nightly job (D-NIGHTLY) | PASS |
| IX. Artemis only for legacy integration | Nothing published by the store; the audit library publishes as it already does | PASS |
| X. Test-driven development | Every task names its tests first; red run quoted before green (phase gate) | PASS |
| XI. Privacy in telemetry | The reason and operator id are never logged or tagged (`NoPayloadInLogsIT` gains a rerun case); logs hold share ids and counts; problem bodies hold bounded codes. Request-body audit not verified (pinned by `AuditIT`) | PASS |
| XII. Estate conventions | Gradle, Java 25, Boot 4; constructor injection; records; explicit imports; typed validated properties; Conventional Commits; no attribution | PASS |
| Quality gates | `build pmdMain pmdTest jacocoTestReport` green per phase; the contract repository's operations, the mirrored document and the allow rules before the code that serves them (T001 before T008); no release on a draft (`validateApiSpecVersions`); reviewers code-reviewer, qa, spec-validator, and Codex | PASS |

**Initial gate: PASS**, with one recorded deviation (Principle I, the `OK` re-read), justified below.

**Re-check after Phase 1 design: PASS.** Points checked again:

- *The V6 guard* (I): it narrows V3's rule rather than dropping it; every `FAILED`-path write still
  passes (both existing statements raise attempts and set the time with the current version).
- *Youth hold* (IV): the share keeps the value it was stored with until the change can be shown; nothing
  is decided about what youth means.
- *Read-API rules still admit "Second Line Support"* (VII): as 2.2.0 says; 004 adds no read rule.
- *The status's `FAILED` counts use the serving pod's version* (VIII): stated in the contract; each
  pod's own version is shown beside them.

## Project Structure

### Documentation (this feature)

```text
specs/004-operations-api/
├── spec.md              # specification (Draft)
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R22
├── data-model.md        # Phase 1: V6 DDL in full; statements; types; invariants
├── quickstart.md        # Phase 1: gate, calling the API locally, watching a rerun
├── contracts/
│   ├── operations-api.md   # the support contract
│   ├── metrics.md          # delta: operations and rerun meters
│   ├── configuration.md    # delta: resultsstore.operations.*, three sweep settings, compose
│   └── schema.md           # delta: V6, rules added and changed
├── checklists/
│   └── requirements.md  # spec quality checklist
├── page-notes.md        # forward notes for the design page owner and the consumer teams
└── tasks.md             # Phase 2
```

### Source Code (repository root)

New (`+`), changed (`~`). Packages follow the design rules: nothing in `domain/` or `application/`
imports a JMS, JDBC or HTTP type. Spec 003's classes are named as built.

```text
src/main/java/uk/gov/hmcts/cp/resultsstore/
├── domain/
│   ├── + RouteEndpoint.java         # sealed: ReadEndpoint, OperationsEndpoint
│   ├── ~ ReadEndpoint.java          # implements RouteEndpoint
│   ├── + OperationsEndpoint.java    # rerun, status, receipts, reconciliation
│   ├── + SelectorKind.java, RerunSelector.java, RerunReason.java, OperatorId.java
│   ├── + RerunRowOutcome.java       # twelve outcomes; stored()
│   └── + ReconciliationWindow.java
├── application/
│   ├── + RerunRequests.java, OperationsQueries.java, SweepRounds.java       # ports
│   ├── + OperationsObserver.java, SweepObserver.java                        # ports
│   ├── + RerunRequest.java, RerunCreation.java, RerunAccepted.java, RerunCandidate.java,
│   │     ExtractionCounts.java, RerunOverview.java, RecentRerun.java, SweepRoundView.java,
│   │     SweepRoundRecord.java, ReceiptView.java, DailyCounts.java, R1Finding.java,
│   │     ExtractionStatus.java, DailyReconciliation.java, RoundResult.java
│   ├── + RerunService.java, ExtractionStatusService.java, ReceiptsService.java, ReconciliationService.java
│   ├── ~ ShareStore.java            # + claimRerunItems, recordRerun, recordRerunError, closeFinishedReruns
│   └── ~ ExtractionSweep.java       # works rerun items after FAILED rows; records the round
├── persistence/
│   ├── + JdbcRerunRequests.java     # writes only the rerun tables
│   ├── + JdbcOperationsQueries.java # read-only, own JdbcTemplate with query timeout
│   ├── + JdbcSweepRounds.java
│   └── ~ JdbcShareStore.java        # the rerun write path, INSERT_DEFENDANT_IF_ABSENT
├── filters/
│   └── ~ ApiRoute.java              # + four operations routes; a method per route; tag widened to RouteEndpoint
├── api/
│   ├── + OperationsController.java  # implements the jar's generated OperationsApi (all four operations)
│   ├── + OperationsParameters.java, OperationsParametersInterceptor.java   # strict query parameters
│   ├── + RerunBodyParser.java, RerunBodyAdvice.java   # strict rerun body, before Jackson binds the model
│   ├── + OperationsResponseMapper.java, OperatorMissingException.java
│   ├── ~ ProblemReason.java         # + the operations reasons
│   ├── ~ ShareParameters.java       # its route switch covers read routes only
│   ├── ~ ReadApiExceptionHandler.java  # the one advice: + 415 mapping, OperatorMissingException,
│   │                                   #   operations refusal count; no-handler read count for read routes only
│   └── ~ ReadMetricsInterceptor.java   # read routes only
└── config/
    ├── + OperationsProperties.java, OperationsConfig.java, MicrometerOperationsObserver.java
    ├── + OperationsWebMvcConfig.java # the operations interceptor on /operations/**
    ├── + SweepObserverConfig.java, MicrometerSweepObserver.java
    ├── ~ SweepProperties.java       # + rerun-batch-size, rerun-max-attempts, pod-name
    └── ~ SweepSchedulingConfig.java # SweepObserver, rerun settings, JdbcSweepRounds

src/main/resources/
├── ~ results-store-openapi.yaml     # mirrors the contract jar's four operations (T001)
├── ~ acl/results-store-rules.drl    # four allow rules (T001)
├── + db/migration/V6__operations.sql
└── ~ application.yaml               # resultsstore.operations.*, three sweep settings

gradle/libs.versions.toml            # ~ api-results-store: the draft (T001, T009 if the contract changes), 0.3.0 (T011)
docker-compose.yml                   # ~ short sweep delays (T010)
scripts/container-smoke.sh           # ~ operations checks (T010); 003's identity-second-line.json is reused

hmcts/api-cp-crime-results-store (contract repository, T001 and T011)
└── the four operations under tag `operations`; RerunRequest, RerunAccepted, ExtractionStatus, Receipts,
    DailyReconciliation schemas; ProblemDetail.reason widened; CHANGELOG; release v0.3.0

src/test/java/uk/gov/hmcts/cp/resultsstore/
├── acl/          ~ ResultsStoreRulesTest
├── api/          + OperationsControllerTest, ReceiptsControllerTest, ReconciliationControllerTest (slices of
│                   OperationsController), RerunBodyParserTest, RerunBodyAdviceTest, OperationsParametersTest,
│                   OperationsParametersInterceptorTest, OperationsResponseMapperTest;
│                   ~ OpenApiDocumentTest, OpenApiContractTest, OpenApiContractDriftTest, ProblemReasonTest,
│                   ReadApiExceptionHandlerTest, ReadMetricsInterceptorTest, ShareParametersInterceptorTest,
│                   ContentNegotiationTest
├── filters/      ~ ApiRouteTest, ActionHeaderFilterTest, UnsupportedContentTypeFilterTest
├── domain/       + OperationsEndpointTest, RerunSelectorTest, RerunReasonTest, OperatorIdTest,
│                   RerunRowOutcomeTest, ReconciliationWindowTest, SelectorKindTest
├── application/  + RerunServiceTest, ExtractionStatusServiceTest, ReceiptsServiceTest,
│                   ReconciliationServiceTest; ~ ExtractionSweepTest
├── persistence/  + OperationsSchemaIT, JdbcRerunRequestsIT, JdbcOperationsQueriesIT, OperationsSqlTest,
│                   OperationsQueriesPlanIT, RerunSweepIT, RerunConcurrencyIT, JdbcSweepRoundsIT;
│                   ~ FlywayMigrationIT
├── config/       + MicrometerOperationsObserverTest, MicrometerSweepObserverTest, OperationsConfigTest,
│                   SweepObserverConfigTest; ~ ConfigurationValidationTest, SweepSchedulingConfigTest
├── support/      ~ SampleShares (rerun fixtures), ApiRouteSamples (operations sample paths)
└── integration/  + OperationsApiIT; ~ AuditIT, NoPayloadInLogsIT, ReadApiIT and AuthzIT (read routes only)
```

**Structure Decision**: one Spring Boot service, packages as in the design rules. Decisions (selector
checks, outcomes, windows, parameter parsing) live outside `config/`, so the coverage gate measures
them. The rerun write path stays in `JdbcShareStore` beside the `FAILED` path, so the sweep keeps one lock
order and one youth recompute. `ApiRoute` remains the single source for the filter, the rules test and
the OpenAPI contract test.

## Phase plan

Four phase-gate phases, exactly as the rulings (C11). The *Covers* column is a summary; the *Covers*
lines in tasks.md are the full list and win where the two differ. Each task is test first; each phase is
green on its own before the next starts. Any change to the contract is made in the contract repository
first and taken as a new draft, as in spec 003.

### Phase A: contract, schema, types

| Task | Test first | Then | Covers |
|---|---|---|---|
| T001 | `ResultsStoreRulesTest`, `OpenApiDocumentTest`, `OpenApiContractTest`, `OpenApiContractDriftTest`, `ApiRouteTest`, `ActionHeaderFilterTest`, `UnsupportedContentTypeFilterTest`, `ReadMetricsInterceptorTest`, `ReadApiExceptionHandlerTest`, `OperationsEndpointTest` | api repo pull request → `team/rs` draft → the service pins the draft → the service's document mirrors it, four rules, four routes, `RouteEndpoint`, `OperationsEndpoint`; the `ApiRoute` iterations narrowed to read routes | FR-001, FR-042–FR-044, FR-054 (contract half) |
| T002 | `OperationsSchemaIT`, `FlywayMigrationIT` | `V6__operations.sql` | FR-020, FR-021 (guard), FR-029, FR-030, FR-048; SC-002 |
| T003 | `RerunSelectorTest`, `SelectorKindTest`, `RerunReasonTest`, `OperatorIdTest`, `RerunRowOutcomeTest`, `ReconciliationWindowTest` | domain values, application ports and records | FR-006–FR-010, FR-015 (canonical form), FR-018 (names), FR-038 (window) |

### Phase B: persistence and the sweep

| Task | Test first | Then | Covers |
|---|---|---|---|
| T004 | `JdbcRerunRequestsIT`, `JdbcOperationsQueriesIT`, `OperationsSqlTest`, `OperationsQueriesPlanIT` | `JdbcRerunRequests`, `JdbcOperationsQueries` | FR-004, FR-007, FR-012–FR-016, FR-031, FR-033 (data half), FR-034–FR-036, FR-038–FR-041; SC-004, SC-005, SC-006 (data half) |
| T005 | `ExtractionSweepTest`, `RerunSweepIT`, `RerunConcurrencyIT`, `MicrometerSweepObserverTest`, `SweepObserverConfigTest`, `ConfigurationValidationTest`, `SweepSchedulingConfigTest` | the rerun path in the sweep and the store; `SweepObserver` | FR-017–FR-028; SC-001, SC-003 |
| T006 | `JdbcSweepRoundsIT`, `ExtractionSweepTest`, `MicrometerSweepObserverTest`, `ConfigurationValidationTest` | `JdbcSweepRounds`, the round record | FR-032 (FR-033's data half is in T004) |

### Phase C: services, web, end to end

| Task | Test first | Then | Covers |
|---|---|---|---|
| T007 | `RerunServiceTest`, `ExtractionStatusServiceTest`, `ReceiptsServiceTest`, `ReconciliationServiceTest`, `MicrometerOperationsObserverTest`, `OperationsConfigTest`, `ConfigurationValidationTest` | services, observer, `OperationsProperties`, `OperationsConfig` | FR-005–FR-016 (service half), FR-031, FR-033, FR-035, FR-037–FR-041, FR-047, FR-049, FR-050; SC-009, SC-010 |
| T008 | `RerunBodyParserTest`, `RerunBodyAdviceTest`, `OperationsParametersTest`, `OperationsParametersInterceptorTest`, `OperationsResponseMapperTest`, the `OperationsController` slices, `ReadApiExceptionHandlerTest`, `ProblemReasonTest`, `OpenApiContractTest` | `OperationsController implements OperationsApi`, the parameter interceptor, the body advice, the mapper, advice changes | FR-002–FR-016 (web half), FR-034, FR-037, FR-046, FR-054 (served half) |
| T009 | `OperationsApiIT`, `AuditIT`, `NoPayloadInLogsIT` | fixes found | US1–US6 end to end; FR-045; SC-006–SC-008 |

### Phase D: smoke and documents

| Task | Test first | Then | Covers |
|---|---|---|---|
| T010 | smoke operations checks first (red on the old build) | compose sweep delays; spec 003's `identity-second-line.json` reused | FR-053; SC-011 |
| T011 | review grep for the old wording (red before the edits) | constitution 2.3.0, design rules, spec 001 notes, spec 003 contract §5.5, contracts reconciled, page notes, `/speckit-analyze`, Deferred; contract Release `v0.3.0` and the bump from the draft | FR-051, FR-052, FR-054 (release half); SC-012 |

Rules for every task: a unit test per class; an IT on Testcontainers Postgres, embedded Artemis or
WireMock for every persistence, messaging and HTTP path; latches or Awaitility, never sleeps; no payload,
message text, reason or operator id in assertion or log output; one commit per task, red run quoted
before green.

## Risks

1. **Silent change to rows consumers already pulled.** A rerun rewrites key details with no new
   `storedSeq`. YOT batches by court centre and `dayYouthSeen`; probation's bridge assumes "everything
   returned is final". The `false`-to-`true` youth case is held; unknown-to-known and `false`-to-unknown
   are written and documented (003 contract §5.5, page notes). Consumers must re-read.
2. **Day-lock contention.** Each rerun item takes its hearing day's lock briefly; intake for that day
   waits up to its 5 s lock timeout (spec 003's E3 default). Batch size bounds the load per round; very large reruns are best
   run off-peak.
3. **Slow large reruns.** About 2.6 days for a 31-day range on one pod at the default pace; the request
   stays `OPEN` that long. More pods help (claims do not collide).
4. **No sweep, no progress.** A request accepted where the sweep is off on every pod never progresses;
   only a stale `lastFinishedAt` shows it.
5. **The guard proves less than "an operator asked".** Any session with insert rights on the item table
   can open a window for a share (D-RERUN-GUARD).
6. **Index builds without `CONCURRENTLY`.** `event_receipt_first_received_ix`, `event_receipt_stale_ix`
   and `hearing_share_stored_at_ix` block writes to their tables while they build: negligible before
   go-live, significant against months of data (production sizes unknown, D-PG-VERSION). Fallback:
   `CREATE INDEX CONCURRENTLY` in a migration with Flyway's `executeInTransaction=false`.
7. **Request-body audit not verified.** If the library records request bodies, the free-text reason
   lands in the audit store; staff could type personal data into it. Mitigation: the contract and OpenAPI
   description, the length bound; `AuditIT` pins the behaviour.
8. **Data with no purge.** The rerun tables grow with no retention; `reason` and `requested_by` are
   staff data. A later purge deletes items before requests and before shares (D-RERUN-ERASURE).
9. **Stale defendant rows** stay after a rerun (add-only). Harmless until a defendant view exists.
10. **The R1 window is a guess** (1 hour) until the broker's give-up time is known: too short flags work
    in flight; too long reports late.
11. **Status cost.** Outcome counts for 20 requests of up to 200,000 items each scan up to 4 million item
    entries; bounded by the 10 s query timeout and the pool's 10 s server-side backstop (`503` beyond
    it).
12. **A pod that dies mid-item** leaves it pending with no attempt counted; a payload that kills the JVM
    every time would loop. No such payload is known; the item's claim time keeps it behind the others.
13. **Clock steps.** A database clock stepping back would make the version guard refuse a `FAILED`-path
    write that uses plain `clock_timestamp()` (counted `error` until the clock passes the old time). The
    rerun path uses `GREATEST(clock_timestamp(), projected_at)`.
14. **Iterations over every route.** Ten 003 test classes iterate over `ApiRoute.values()` (rules,
    filters, contract, `ShareParametersInterceptorTest`, `ContentNegotiationTest`, `ReadApiIT`,
    `AuthzIT`), and `ShareParameters` and `support/ApiRouteSamples` switch over it. T001 narrows every
    read-only case to the read routes (the route, rule and filter tests gain operations cases of their
    own), or they start calling operations routes as if they were reads.
15. Not verified: whether the audit library records request bodies; the broker's redelivery give-up
    time; production table sizes at deploy; the production PostgreSQL version.
16. **Contract draft.** The service builds on a `rs-<sha7>` draft until T011's release;
    `validateApiSpecVersions` refuses a release on a draft, so `v0.3.0` must be published before 004
    ships. Under D-OPS-CONTRACT's alternative (a second repository) T001 and T011 change, not the rest.

## Complexity Tracking

| Deviation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Constitution I (2.2.0) and V3's guard comment say an `OK` share's key details are final ("OK is final in 001"); from T002 the schema, and from T005 the sweep, rewrite an `OK` share in place while a pending rerun item names it. The 2.3.0 wording lands in T011 | An extractor fix must reach shares already stored `OK`; that is the purpose of the rerun (spec 001: "marking rows for a rerun is spec 004"). Reviewers of T002 to T010 read this row as the written justification the constitution asks for | Moving rows to `FAILED` to reuse the existing retry: blanks their key details (`hearing_share_failed_is_empty_ck`) and drops them from consumers' searches while they wait. Amending the constitution first, in T001: the wording should describe what was built and tested; T011 checks it against the code |
