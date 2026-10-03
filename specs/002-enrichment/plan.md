# Implementation Plan: Enrichment

**Branch**: `002-enrichment` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/002-enrichment/spec.md`

## Summary

Add one step to intake: before a share is stored, fill in the finalised application results that
results adds today, so the store keeps what results keeps. For each court application in
`hearing.courtApplications[]` whose `judicialResults` is missing, `null` or empty, the store asks
progression's application-only query, as its own system user. If progression says `FINALISED` and has
results, they are copied into a working copy of the payload, without `amendmentDate`,
`amendmentReason` and `amendmentReasonId`. The arrived text and its checksum stay exactly as they
arrived; the working copy goes into `payload_json`, which becomes permanent and is what the extraction
and the sweep read. `enrichment_applied` records whether anything was added.

The step runs between the receipt transaction and the store transaction, with no transaction open
(Principle VI). A read-only existence check first means a share already stored makes no calls. Any
progression failure, including a 404, an auth refusal and a malformed body, fails closed: nothing is
stored, the message rolls back after the capped pause, and the broker redelivers it. An enriched copy
the database cannot hold falls back once to the arrived copy. No schema change. Detail:
[research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/).

## Technical Context

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.1.1 (web, jdbc, flyway, artemis, actuator, opentelemetry);
Spring `RestClient` over `HttpComponentsClientHttpRequestFactory` (spring-web 7.0.9) and Apache
HttpClient 5 for the outbound call (research R11, amended after gate round 1);
Jackson 3.1.7 (`tools.jackson`) tree API with derived readers and writers (research R9); Micrometer;
one new dependency, `httpclient5`, version from the Boot BOM  
**Storage**: PostgreSQL 16 (local and tests); no migration; existing columns
`hearing_share.enrichment_applied` and `hearing_share_payload.payload_json` take on their 002 meaning
(data-model.md)  
**Testing**: JUnit 5, Mockito, AssertJ, Awaitility; JSONAssert 1.5.3 (already on the test classpath
through `spring-boot-starter-test`, research R22) for parity; WireMock standalone 3.13.2 (in-process
for client tests and ITs, the compose `wiremock` service for the smoke); Testcontainers `postgres:16`;
embedded Artemis  
**Target Platform**: Linux container on AKS (2 or more pods), Gradle build  
**Project Type**: single Spring Boot service (`uk.gov.hmcts.cp.resultsstore`)  
**Performance Goals**: no extra cost for shares without applications needing results (most shares):
no call, no extra read. A share needing N lookups adds N calls plus one indexed read; each call is
bounded by the 10 s whole-lookup deadline at the defaults, which is armed when the request is
created and so includes the connect; the 5 s connect timeout is subordinate to it (research R15)  
**Constraints**: no HTTP call inside a database transaction; no in-process retries; fail closed on
every unexpected answer; `payload_text` and `payload_sha256` byte for byte as arrived; logs hold ids
only (never a body or the system user id); bounded metric tags; JaCoCo 0.88 line / 0.85 branch; PMD
7.22.0 clean on main and test; no wildcard imports; V1 to V4 never edited  
**Scale/Scope**: about 4,800 shares a day; application-bearing shares are a minority and usually carry
one or two applications. About 9 new production classes, 15 changed, 6 new test classes and 10
changed; 10 tasks in three phases

Every point above is settled in [research.md](research.md); none is left open.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution 2.1.0 (Principle II reworded in T010, FR-035; the check below was made against 2.0.0 and still holds).

| Principle | How this feature satisfies it | Gate |
|---|---|---|
| I. Every share is an immutable version | `enrichment_applied` is bound in the share insert and never updated (`hearing_share_guard`); the payload row stays insert-only. The fallback re-runs a transaction that was rolled back, so nothing stored is ever changed. Pre-002 rows are never re-enriched. No new updatable column | PASS |
| II. The payload is the source of truth | `payload_text` and `payload_sha256` unchanged from 001 (byte for byte, SHA-256 over the arrived text). `payload_json` holds the arrived payload plus the finalised application results, which the principle already names. Every key-detail column is read from it, and can be rebuilt from it. **Wording changed in T010 (MINOR 2.0.0 → 2.1.0):** Principle II now names `payload_text` as the message as it arrived, with the SHA-256 checksum over it, and `payload_json` as the working copy, enriched at intake with finalised application results from progression with `amendmentDate`, `amendmentReason` and `amendmentReasonId` removed, held as jsonb, which keeps content but not key order, spacing or duplicate keys; indexed columns are read from the working copy and the read API serves it (research R25). This states what 2.0.0 already allowed; it reverses no rule, so it is a documented change, not a violation | PASS |
| III. Consumers search indexed columns | No read path in 002. The forward constraint for spec 003 (serve `payload_json`, ETag over the served bytes) concerns the payload endpoint only | PASS (not exercised) |
| IV. No business rules | The store copies what progression states, by results' own rule, and records nothing it derives. The three fields are removed for parity with results' stored payload (the page's wording), not by a rule of the store's own. `enrichment_applied` records what the store did, not a fact about the case. No derived fact is added | PASS |
| V. Never refuse to store | Inputs results would throw on (explicit `null`, a missing or invalid id, non-object result elements) are handled, not refused (FR-014). The database refusing the enriched copy falls back to the arrived copy. A progression failure does not refuse the share: the message is redelivered, and only after the broker's own attempts is it dead-lettered, with its receipt still `RECEIVED` for reconciliation, as the principle allows | PASS |
| VI. Idempotent, transactional intake | Receipt first, unchanged. The lookups run between the two transactions, never inside one, as the principle requires. One store transaction per attempt (a fallback re-run is a second, whole transaction after a full rollback). Ack after commit. Duplicates still by the unique key with `ON CONFLICT DO NOTHING`; the existence check only saves calls. Failures rethrown after the capped pause; no retry loop. Never stored half-enriched | PASS |
| VII. Default-deny authorisation | No endpoint added. The outbound call uses the store's own system user ("System Users") | PASS (not exercised) |
| VIII. Observability through Azure Monitor | `intake.failed{stage=enrich}` with six bounded causes feeds the *Progression lookups failing* alert; outcome counter, lookup timer, skipped and applied counters (contracts/metrics.md). Every fail-closed path moves a counter | PASS |
| IX. Artemis only for legacy integration | Subscription, name and selector unchanged; nothing published | PASS |
| X. Test-driven development | Every task below names its tests first; red run quoted before green (phase gate) | PASS |
| XI. Privacy in telemetry | Logs hold the MDC ids, the application id (a system identifier, not personal data), status and bounded cause; never a body, a Jackson message or the system user id (research R14). No id in a metric tag. `NoPayloadInLogsIT` gains malformed, HTML and 403 cases | PASS |
| XII. Estate conventions | Gradle, Java 25, Boot 4; constructor injection; records; explicit imports; typed validated properties; no hard-coded URL (base URL from `CP_BASE_URL`); secrets never defaulted (no default for the system user id); Conventional Commits; no AI attribution | PASS |
| Quality gates | `build pmdMain pmdTest jacocoTestReport` green per phase; reviewers code-reviewer, qa, spec-validator, and Codex | PASS |

**Initial gate: PASS.** No violation, so Complexity Tracking is empty.

**Re-check after Phase 1 design: PASS.** Points checked again:

- *Fail closed on 404, 401/403, other statuses and malformed bodies* (research R13). Principle VI
  names "database or progression unreachable" as retryable failures; a wrong route or user is fixed by
  configuration, not by waiting. Sending these back to the broker still keeps every share (V): the
  message is redelivered and, at worst, dead-lettered with its receipt `RECEIVED`, so reconciliation
  sees it and it can be redriven once fixed. Storing un-enriched instead would break parity silently
  (spec US5). Within the principles as written.
- *The existence check* is a read outside any transaction with no lock; it decides nothing the unique
  key does not also decide (VI).
- *Logging the application id* (XI): the principle's list of identifiers is not closed ("Log
  identifiers only"), and spec FR-032 allows it.
- *The working copy is jsonb, not the arrived text* (II): already true in 001 for the parsed copy; the
  T010 wording makes it explicit.

## Project Structure

### Documentation (this feature)

```text
specs/002-enrichment/
├── spec.md              # approved specification
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R25
├── data-model.md        # Phase 1: no DDL; the columns' 002 meaning; invariants
├── quickstart.md        # Phase 1: parity suite, compose stub, simulating failures
├── contracts/
│   ├── progression-lookup.md # the outbound call: request, transport, status and body table
│   ├── metrics.md            # delta: stage enrich, causes, new counters and timer
│   ├── configuration.md      # delta: enrichment switch, progression properties, no defaults
│   └── schema.md             # delta: no migration; new meaning of two columns
├── checklists/          # spec quality checklist
└── tasks.md             # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

New (`+`), changed (`~`). Packages follow `.claude/rules/design_rules.md`: nothing in `domain/` or
`application/` imports a JMS, JDBC or HTTP type.

```text
src/main/java/uk/gov/hmcts/cp/resultsstore/
├── domain/
│   ├── ~ IntakeStage.java              # + ENRICH ("enrich")
│   ├── ~ IntakeFailureCause.java       # + PROGRESSION_REJECTED, _REFUSED, _UNAVAILABLE, _UNREACHABLE,
│   │                                   #   _TIMEOUT, _MALFORMED; fromSqlState unchanged
│   ├── + ApplicationLookupOutcome.java # ENRICHED, NOT_FOUND, NOT_FINALISED, NO_RESULTS, INVALID_ID (+ tag)
│   └── + EnrichmentSkip.java           # DISABLED, ALREADY_STORED, UNSTORABLE_RESULTS (+ tag)
├── application/
│   ├── + ProgressionApplications.java  # port: ApplicationAnswer find(UUID); throws RetryableIntakeException(ENRICH, …)
│   ├── + ApplicationAnswer.java        # sealed: Found(JsonNode courtApplication) | NotFound
│   ├── + ApplicationResultsEnricher.java # pure: scan (ids by UUID, invalid count), outcome(answer),
│   │                                   #   enrich(body, answers) on a deep copy, serialise (R6)
│   ├── + Enrichment.java               # record: tree, parsedCopy, applied
│   ├── ~ IntakeService.java            # seam: scan → existence check → lookups (timed, counted) →
│   │                                   #   enrich → extract on the enriched tree → store → fallback re-run
│   ├── ~ StoreRequest.java             # + parsedCopy, enrichmentApplied; text stays the arrived text
│   ├── ~ StoreResult.java              # Stored + enrichmentApplied; + EnrichedCopyRefused
│   ├── ~ ShareStore.java               # + storedShareId(ShareIdentity); payloadText → payloadForExtraction
│   ├── ~ IntakeObserver.java           # + applicationLookedUp, lookupTimed, enrichmentSkipped, enrichmentApplied
│   ├── ~ ExtractionSweep.java          # reads payloadForExtraction; never calls progression
│   ├── ~ ShareIdentityParser.java      # reader + USE_BIG_DECIMAL_FOR_FLOATS, STRIP_TRAILING_BIGDECIMAL_ZEROES off
│   └── ~ RetryableIntakeException.java # + constructor with the failed class name and no chained cause;
│                                       #   javadoc: thrown by the persistence and progression adapters
├── adapter/progression/
│   ├── + ProgressionApplicationClient.java # RestClient exchange(); headers; status and body classifier
│   ├── + NoRedirectRequestFactory.java # Apache HttpClient 5 factory: no redirects, no retries, deadline (research R11 amendment)
│   └── + DeadlineInputStream.java      # whole-response deadline for the slow drip (R15)
├── persistence/
│   └── ~ JdbcShareStore.java           # INSERT_SHARE binds :enrichmentApplied; payload insert takes the
│                                       #   parsed copy; enriched copy refused → setRollbackOnly +
│                                       #   EnrichedCopyRefused; storedShareId; payloadForExtraction (COALESCE)
└── config/
    ├── + EnrichmentProperties.java     # resultsstore.enrichment.enabled
    ├── + ProgressionProperties.java    # resultsstore.progression.* (contracts/configuration.md)
    ├── + ProgressionConfig.java        # RestClient + client bean when publicevents and enrichment are on;
    │                                   #   blank base URL / user id fail start
    ├── ~ Rules.java                    # + absoluteHttpUrl, uuid
    ├── ~ IntakeConfig.java             # registers the new properties; enricher bean; intakeService wiring
    └── ~ MicrometerIntakeObserver.java # new meters, pre-registered

src/main/resources/application.yaml       # ~ resultsstore.enrichment.*, resultsstore.progression.*
src/test/resources/application-test.yaml  # ~ resultsstore.enrichment.enabled: false
src/test/resources/progression/           # + results' eight fixtures (R4) + the several-applications pair
docker-compose.yml                        # ~ app: RESULTS_STORE_SYSTEM_USER_ID (synthetic)
docker/wiremock/mappings/progression-application.json  # + the compose stub (quickstart §3)
scripts/container-smoke.sh                # ~ enriched-share case

src/test/java/uk/gov/hmcts/cp/resultsstore/
├── domain/        ~ IntakeFailureCauseTest; + IntakeStageTest, ApplicationLookupOutcomeTest, EnrichmentSkipTest
├── application/   + ApplicationResultsEnricherTest, ApplicationResultsParityTest;
│                  ~ IntakeServiceTest, ExtractionSweepTest, ShareIdentityParserTest (decimals)
├── adapter/progression/ + ProgressionApplicationClientTest
├── adapter/publicevents/ ~ IntakeIT (progression stub)
├── persistence/   ~ JdbcShareStoreIT, ExtractionSweepIT
├── config/        ~ ConfigurationValidationTest, IntakeConfigTest, MicrometerIntakeObserverTest
├── integration/   ~ NoPayloadInLogsIT
└── support/       + ProgressionStub; ~ SampleShares (an application-bearing sample)
```

`IntakeProperties` is not changed: the orchestration plan listed it, but the switch has its own prefix
(`resultsstore.enrichment`), so it gets its own record.

**Structure Decision**: one Spring Boot service, packages as in the design rules. The outbound client
is an adapter (`adapter/progression/`) behind an application port, as the subscription is
(`adapter/publicevents/`). Branching logic (the classifier, the enricher, the seam) lives outside
`config/` so the coverage gate measures it (research R24).

## Phase plan

Mirrors the orchestration plan's Step 2 (T001–T010). Each task is test first; each phase passes the
phase gate (001 quickstart §7) before the next starts. Phase B needs the port and the properties from
Phase A; Phase C needs the store changes from Phase B.

### Phase A: values, client, wiring

| Task | Test first | Then | Covers |
|---|---|---|---|
| T001 | `IntakeStageTest` (`enrich` tag); `IntakeFailureCauseTest` (six `progression_*` tags; `fromSqlState` never returns one); `ApplicationLookupOutcomeTest`, `EnrichmentSkipTest` (bounded lower-case tags); `ConfigurationValidationTest`: defaults bind (enabled true, 5 s, 10 s) when a base URL and user id are supplied; bad values stop start: base URL `localhost:8080`, `ftp://x`, `http://x/path`, `http://x?q=1`; connect 0 s and 31 s; read 0 s and 61 s; user id `not-a-uuid`; blank base URL and blank user id allowed with enrichment off; `absoluteHttpUrl` and `uuid` rules covered through these rows | `IntakeStage`, `IntakeFailureCause`, `ApplicationLookupOutcome`, `EnrichmentSkip`, `EnrichmentProperties`, `ProgressionProperties`, `Rules`, `application.yaml`, `application-test.yaml` | FR-024–FR-027 |
| T002 | `ProgressionApplicationClientTest` (in-process WireMock): GET on the path with `Accept` and `CJSCPPUID`; id as a path variable; one test per row of contracts/progression-lookup.md (found, `{}`, `null` application, 404, 3xx not followed with no request at the target, other 2xx, 400/405/406/410/415, 401/403, 408/429/500/502/503/504, closed port, `CONNECTION_RESET_BY_PEER`, fixed delay past the read timeout, chunked dribble past the deadline, `MALFORMED_RESPONSE_CHUNK` on a 200, HTML 200, empty 200, array body, trailing tokens, `courtApplication` a string, `judicialResults` an object); one request only on 503; decimals `1.10` and `12345678901234567890.123` read exactly; captured log holds no body and no user id; the thrown exception has no chained cause | `ProgressionApplications`, `ApplicationAnswer`, `RetryableIntakeException` (constructor), `ProgressionApplicationClient`, `NoRedirectRequestFactory`, `DeadlineInputStream`, `support/ProgressionStub` | FR-007–FR-009, FR-021–FR-023 |
| T003 | `IntakeConfigTest`: client bean present with publicevents and enrichment on; absent with either off; the built client carries the base URL and both timeouts (a WireMock delay proves the read timeout); start fails naming `resultsstore.progression.system-user-id` or `.base-url` when blank with enrichment on (`ApplicationContextRunner`); `ActuatorIntegrationTest` still green with enrichment off | `ProgressionConfig`, `IntakeConfig` (properties registration); confirm contracts/configuration.md | FR-024–FR-026; SC-009 |

### Phase B: enricher, persistence, seam, end to end

| Task | Test first | Then | Covers |
|---|---|---|---|
| T004 | `ApplicationResultsEnricherTest`: no applications / not an array → no lookup; missing, `null`, `[]` → lookup; non-empty or other type → none; repeated id (also mixed case) looked up once and applied to every occurrence; array order; not finalised, no results, not found → unchanged; amendment fields removed and every other field kept (incl. `isNewAmendment`, `fourEyesApproval`, `approvedDate`); nested results never copied; only `judicialResults` copied; replaced `[]` keeps its place and an added key goes last (asserted with `fieldNames()`); non-object element copied unchanged; `applied` false when nothing added; arrived body not changed; invalid id skipped; serialisation escapes `\udc00` and non-ASCII (R6). `ApplicationResultsParityTest` (JSONAssert `STRICT`): the six cases of research R4. `ShareIdentityParserTest`: `1.10`, `1e3`, `12345678901234567890.123` survive parse and enriched serialisation | `ApplicationResultsEnricher`, `Enrichment`, `ShareIdentityParser` reader; copy the eight fixtures and author the several-applications pair | FR-002–FR-004, FR-010–FR-015; SC-001 |
| T005 | `JdbcShareStoreIT`: enriched share stores arrived text and checksum byte for byte, enriched `payload_json`, flag true; un-enriched share stores `CAST(text)` and flag false (replaces the 001 assertion); unsafe arrived text → NULL and flag false; enriched copy with `\u0000` → `EnrichedCopyRefused`, nothing written; class-22 on the enriched copy → `EnrichedCopyRefused`, nothing written, the next call on the same pooled connection works; duplicate of an enriched share keeps the first payload; `storedShareId` finds a stored share and is empty otherwise, takes no lock (a held day lock does not block it); `payloadForExtraction` prefers `payload_json` and falls back to the text. `FlywayMigrationIT` unchanged and green | `StoreRequest`, `StoreResult`, `ShareStore`, `JdbcShareStore`, `support/SampleShares` | FR-006, FR-016–FR-019; SC-006, SC-007 |
| T006 | `IntakeServiceTest` (mocks): no applications → no existence check, no call; settled receipt → no call; stored share → no call, `DUPLICATE`, `skipped{already_stored}`; enrichment off → no call, `skipped{disabled}`; lookups after the receipt commit and before extraction (`InOrder`); extraction reads the enriched tree; the request carries the arrived text, checksum, enriched copy and flag; a retryable lookup failure is counted once at `enrich` and rethrown with no store call; an unexpected client failure counts `enrich`/`other`; an existence-check failure counts `store`; each answered lookup reports its outcome and duration; `enrichment.applied` only after `Stored` with the flag true; `EnrichedCopyRefused` → one re-run with the arrived copy and flag false, `skipped{unstorable_results}`, no `applied`. `MicrometerIntakeObserverTest`: new meters pre-registered with every tag; `intake.failed` accepts `enrich` and the causes; timer per outcome; whole-registry tag guard | `IntakeService`, `IntakeObserver`, `MicrometerIntakeObserver`, `IntakeConfig` (wiring); confirm contracts/metrics.md | FR-001, FR-005, FR-006, FR-020, FR-027–FR-032; SC-002 |
| T007 | `IntakeIT` (static `ProgressionStub`, per-id stubs and `verify`): application missing results → stored enriched, `payload_text` equals the published text, one GET with the system user; not finalised → un-enriched; `200 {}` → un-enriched, `not_found` counted; 503 then 200 → redelivered, then one share, enriched, receipt `STORED` with 2 attempts; failing every time → dead-lettered, no share, receipt `RECEIVED`; 404 → redelivered, nothing stored, `progression_rejected`; 403 → `progression_refused`; same share under a new message id → one GET in total, `DUPLICATE`; shares with no applications or already resulted → 0 requests. `NoPayloadInLogsIT`: malformed body, HTML 500 and 403 cases log no marker and no user id | fixes found | US1–US8; FR-038; SC-002–SC-005, SC-008 |

### Phase C: sweep, smoke, documents

| Task | Test first | Then | Covers |
|---|---|---|---|
| T008 | `ExtractionSweepTest`: extracts from `payloadForExtraction`; a missing payload row still counts its own failure; a database failure reading the copy counts `error`. `ExtractionSweepIT`: a `FAILED` enriched row is re-extracted from `payload_json` (fixture where `payload_json` has a field the text lacks); a row with NULL `payload_json` from the text; the sweep makes no progression request | `ExtractionSweep`, `ShareStore` javadoc | FR-033, FR-034 |
| T009 | extend `scripts/container-smoke.sh` first so it fails on the old build: publish a share whose application lacks results (synthetic ids); `psql` gives `t|1|f|t` (flag, result count, amendment field absent, text checksum intact); WireMock `/__admin/requests/count` filtered by the progression path gives 1 with the smoke's user id; the 001 cases unchanged | `docker/wiremock/mappings/progression-application.json`, `docker-compose.yml` (synthetic `RESULTS_STORE_SYSTEM_USER_ID`), smoke script | FR-039; SC-010 |
| T010 | review gate: grep `exactly as received`, `parsed copy`, `unread in 001`, `never the parsed copy`, `can be dropped`, `same way the validation` across `specs/`, the constitution, `.claude/agents/code-reviewer.md` and the page notes; each hit reworded or marked historical. `./gradlew check` green | constitution Principle II and Sync Impact Report (2.1.0); spec 001 FR-015, FR-016, FR-036, Key Entities (with a "changed by 002" note); 001 `data-model.md` and contracts; `code-reviewer.md` (*Payload altered*: the rule binds `payload_text`; `payload_json` is the working copy); forward notes for the page owner (Data model bullet 2, `hearing_share_payload` row, read API payload row); `/speckit-analyze` | FR-035–FR-037, FR-040, FR-041; SC-011 |

Rules for every task: a unit test per class; an IT on Testcontainers Postgres, the embedded broker or
WireMock for every persistence, messaging and HTTP path; latches or Awaitility, never sleeps; no
payload text in assertion or log output; one commit per task, red run quoted before green.

## Complexity Tracking

No constitution principle is violated, so nothing needs justifying here.
