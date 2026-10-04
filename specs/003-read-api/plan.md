# Implementation Plan: Read API

**Branch**: `003-read-api` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/003-read-api/spec.md`

## Summary

Build the internal read API under `/results-store/v1`: pull, search, one share, one share's payload,
and every version of one hearing day, plus the arrived text as phase D (D-RAW accepted, E2). No response
carries the message envelope's metadata (`_metadata`, E8).
The contract comes first: the OpenAPI paths, the allow rules and the route table land before any serving
code (constitution, *Development Workflow*).

Pull is safe by a **visibility bound**: it returns only shares numbered at or below the highest number
stored more than the lag ago (90 s at the defaults), both clocks from the database. The 90 s is provable
because 003 lowers the intake statement timeout to 10 s and the lock timeout to 5 s, and every pooled
connection starts with a server-side `statement_timeout` from the same property (E3). A V5 trigger sets
`stored_at` after the number is taken, which the proof needs; an intake-side counter alerts when a
store transaction outlives the lag. The pull response carries a high-water cursor that moves over
filtered ranges and a `visibleUpTo` time for YOT's 18:00 barrier.

The action filter is rewritten so the action always comes from method and path through Spring's own
path matcher, vendor media types are neutralised (the authorisation library would otherwise take the
action from them), unknown paths are refused `404` and wrong methods `405`. Each action has one allow
rule for "System Users" and "Second Line Support", also matching method and path. Every refusal and
error is the same four-field problem body. The payload is served as the exact bytes of the working copy
without `_metadata`, with a strong SHA-256 `ETag` over them and `304` support; the audit event holds a
fixed marker instead of the payload (D-AUDIT option 4, E1). Detail: [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/).

## Technical Context

**Language/Version**: Java 25
**Primary Dependencies**: Spring Boot 4.1.1 (webmvc, jdbc, flyway, actuator); Spring MVC `PathPattern`
matching (`PathPatternParser.defaultInstance`, `RequestPath`); `cp-auth-rules-filter` 1.0.7 and
`cp-audit-filter-springboot` 1.0.5 (already dependencies); Jackson 3 (`tools.jackson`) for responses;
Micrometer. One new dependency, the contract jar (research R23): `apiSpec`
`uk.gov.hmcts.cp:api-cp-crime-results-store`, pinned to the draft **`rs-2c5bc08`** (api commit `2c5bc08`)
until the release `0.2.0` (T012), in an `apiSpec` configuration that `implementation` extends, with
`gradle/apispec-validation.gradle` applied. It resolves from the Azure Artifacts `hmcts-lib` repository
already in `gradle/repositories.gradle`, read anonymously (no credentials):

```groovy
maven {
  url = 'https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/maven/v1'
}
```

swagger-parser 2.1.20 (already a test dependency) for the OpenAPI tests and the drift test.
**Storage**: PostgreSQL 16 (local and tests); migration `V5__read_api.sql` (one trigger, three partial
indexes; data-model.md). No table or column change. Intake store timeouts (spec 001 settings, changed by
T008): transaction 60 s, statement 10 s (was 20 s), lock 5 s (was 10 s), idle-in-transaction 10 s;
visibility lag 90 s = 60 + 2 × 10 + 10. Hikari's connection-init SQL sets `statement_timeout` on every
pooled connection from `resultsstore.intake.store.statement-timeout` (a backstop, never part of the bound)
**Testing**: JUnit 5, Mockito, AssertJ; `@WebMvcTest` slices for the controllers; MockMvc with
`@SpringBootTest` for `ReadApiIT`, `AuditIT`, `AuthzIT`, `FilterOrderIT`; Testcontainers `postgres:16`
(`support/PostgresTestSupport`); WireMock 3.13.2 for usersgroups (matched on `CJSCPPUID`); embedded
Artemis for the audit topic (`support/EmbeddedBrokerSupport`); KIE for the rule tests; the compose smoke
over `curl`
**Target Platform**: Linux container on AKS (2 or more pods), Gradle build
**Project Type**: single Spring Boot service (`uk.gov.hmcts.cp.resultsstore`)
**Performance Goals**: pull and search are one index range scan each, stopping after `limit + 1` rows
(SC-007); the visibility bound is a backward scan over the few rows inside the lag; a payload read is
two primary-key look-ups. A payload response holds about three copies of the body in memory while the
audit filter buffers it (8 MB for a 2.4 MB payload)
**Constraints**: no SQL built from input; no read query but the payload's touches `hearing_share_payload`
(III); bounded problem bodies only; no id or date in a metric tag; no payload or exception text in a log
line; JaCoCo 0.88 line / 0.85 branch; PMD 7.22.0 clean on main and test (`OnlyOneReturn`: single exit or
a site suppression with a reason); no wildcard imports; V1 to V4 never edited
**Scale/Scope**: about 4,800 shares a day; consumers: YOT (nightly), probation (bridge, `limit=200`
every 30 s), court register. About 38 new production classes, 14 changed; 13 tasks in four phases

Every point above is settled in [research.md](research.md) or is a decision in spec.md *Decisions taken
with Sachin (2026-10-03)*. The production PostgreSQL version stays a risk (D-PG-VERSION, *Risks*).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution 2.1.0. T012 amends it to 2.2.0 (MINOR; research R20); the check below holds against both.

| Principle | How this feature satisfies it | Gate |
|---|---|---|
| I. Every share is an immutable version | 003 writes nothing to a share. V5's trigger sets `stored_at` **at insert** only (`BEFORE INSERT`); `hearing_share_guard` still refuses any update of it. No new updatable column | PASS |
| II. The payload is the source of truth | `/payload` serves the working copy, and the text when the working copy is empty, each **without `_metadata`** (E8); `ETag` over the bytes served (002 FR-041 as amended by 003). Phase D adds the arrived text, likewise without `_metadata`, on its own endpoint (E2). Nothing stored changes: `payload_text` and the working copy keep `_metadata`; only what is served omits it. **Wording change in T012 (2.2.0)**: *The read API serves the working copy without the message envelope's metadata (`_metadata`), and the text, likewise without it, when the working copy is empty*, plus the arrived-text clause (spec FR-057); T013 touches no constitution. Until then the deviation is recorded in Complexity Tracking | PASS with the recorded deviation |
| III. Consumers search indexed columns | Pull, search, one share and the day's versions read `hearing_share` only, on V5 and V3 indexes; the payload query is the only one naming `hearing_share_payload`. Proved on the constants (`JdbcShareQueriesTest`) and on the plans (`ReadQueriesPlanIT`) | PASS |
| IV. No business rules | The store exposes the columns as stored and filters only as asked. `dayYouthSeen=notFalse` keeps unknown days visible (001's rule); `courtCentreId` is an exact match on the stored column (E5), so a `FAILED` row with no court is not matched. `versionNumber` is a position, not a derived fact about the case | PASS |
| V. Never refuse to store | Intake unchanged; the overrun counter only observes | PASS |
| VI. Idempotent, transactional intake | The store transaction is unchanged; the overrun measure reads a clock before the insert and after the commit, outside any decision | PASS |
| VII. Default-deny authorisation | One allow rule per action, `deny-when-no-rules` true, no rule allows everything; action derived from method and path for every request; unknown paths refused; vendor media types neutralised; rules also match method and path. **Wording change in T012 (2.2.0)**: the derived-action sentence made stricter; read rules name "Second Line Support" beside "System Users" (VII already says support staff read payloads through the read API); *every request that reaches an endpoint is audited; a request refused by a filter or by authorisation is counted* (E13); the payload body replaced by a marker in the audit event (E1). Until then the deviation is recorded in Complexity Tracking | PASS with the recorded deviation |
| VIII. Observability through Azure Monitor | Read requests, refusals, durations, page and payload sizes, and the overrun counter, every one registered at start; refusals that are not audited are counted | PASS |
| IX. Artemis only for legacy integration | Nothing published by the store. The audit library publishes to the estate's audit topic, as it already would | PASS |
| X. Test-driven development | Every task names its tests first; red run quoted before green (phase gate) | PASS |
| XI. Privacy in telemetry | Logs hold ids and exception class names only; problem bodies hold bounded codes; no tag holds an id or date; `NoPayloadInLogsIT` gains a read case. Audit bodies: the payload body is replaced by a marker (E1); no response carries `_metadata` (E8) | PASS |
| XII. Estate conventions | Gradle, Java 25, Boot 4; constructor injection; the generated models of the contract jar for responses (research R23); explicit imports; typed validated properties; Conventional Commits; no attribution | PASS |
| Quality gates | `build pmdMain pmdTest jacocoTestReport` green per phase; OpenAPI and allow rules before the code that serves them (T001 before T010); reviewers code-reviewer, qa, spec-validator, and Codex | PASS |

**Initial gate: PASS**, with two recorded deviations (VII audit wording; II served form without `_metadata`), justified below.

**Re-check after Phase 1 design: PASS.** Points checked again:

- *The trigger on `stored_at`* (I): an insert-time value, set once; the column stays fixed. Not an update.
- *Admitting "Second Line Support" to read rules* (VII): VII says "Every read-API action's rule admits
  the System Users group" and "Support staff read payloads through the read API under its own rules".
  Admitting a second group is within the first sentence and is what the second needs; 2.2.0 names it.
- *The unknown-row obligation* (IV): it tells consumers how to read mutable columns; it applies no rule
  to what is stored.
- *The overrun counter touching 001 code* (VI): it adds a measurement and a counter; no outcome changes
  (D-OVERRUN = yes, E4).
- *Lowering the intake statement and lock timeouts* (VI): the store transaction is unchanged in shape;
  a statement that used to have 20 s now has 10 s. A timeout is a database error and the message is
  redelivered, never a lost share (V).

## Project Structure

### Documentation (this feature)

```text
specs/003-read-api/
├── spec.md              # specification (Draft)
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R23
├── data-model.md        # Phase 1: V5 DDL in full; item fields; query shapes; invariants
├── quickstart.md        # Phase 1: gate, calling the API locally, checking the lag and the ETag
├── contracts/
│   ├── read-api.md      # the consumer contract (gate G2)
│   ├── metrics.md       # delta: read meters, refusals, overrun counter
│   ├── configuration.md # delta: resultsstore.read.*, rollout order, authz required, constants
│   └── schema.md        # delta: V5, rules added
├── checklists/
│   └── requirements.md  # spec quality checklist
├── page-notes.md        # forward notes for the design page owner and the consumer teams
└── tasks.md             # Phase 2
```

### Source Code (repository root)

New (`+`), changed (`~`). Packages follow the design rules: nothing in `domain/` or `application/`
imports a JMS, JDBC or HTTP type.

```text
src/main/java/uk/gov/hmcts/cp/resultsstore/
├── domain/
│   ├── + ReadEndpoint.java        # pull, search, share, payload, day_versions (+ arrived_payload, phase D)
│   ├── ~ SharedDays.java          # + London day range → [from, to) instants
│   ├── + RouteRefusal.java        # route_not_found, method_not_allowed, unsupported_content_type,
│   │                              #   unauthenticated, forbidden, connector_rejected
│   ├── + ReadOutcome.java         # ok, not_modified, bad_request, not_found, unavailable, failed
│   ├── + ShareView.java           # the item's values; keyDetails null when FAILED
│   ├── + DayYouthFilter.java      # ANY, NOT_FALSE, TRUE, FALSE; fromValue
│   ├── + SearchCursor.java        # (shared_at, share_id); encode / strict decode
│   ├── + StoredPayload.java       # identity, flag, text, form
│   ├── + EnvelopeMetadata.java    # removes `_metadata` from a JSON text (Jackson 3; numbers kept exact)
│   ├── + PayloadForm.java         # WORKING_COPY, ARRIVED_TEXT
│   └── ~ PayloadChecksum.java     # + sha256Hex(byte[])
├── application/
│   ├── + ShareQueries.java        # port: pull, search, share, dayVersions, payload (+ arrivedText)
│   ├── + ReadObserver.java        # port: request, page items, payload bytes, duration
│   ├── + RefusalObserver.java     # port: refused(RouteRefusal)
│   ├── + PullQuery.java, SearchQuery.java, PullPage.java, SearchPage.java, ServedPayload.java
│   ├── + BadParameterException.java, NotFoundException.java   # carry a ProblemReason code only
│   ├── + ShareReadService.java    # limits, ranges, cursors, `_metadata` strip of the text form,
│   │                              #   ETag over the served bytes, observer
│   ├── ~ IntakeObserver.java      # + visibilityOverrun()
│   ├── ~ IntakeService.java       # compares Stored.insertToCommit with the threshold
│   └── ~ StoreResult.java         # Stored + insertToCommit
├── persistence/
│   ├── + JdbcShareQueries.java    # fixed SQL constants; own JdbcTemplate with query timeout
│   └── ~ JdbcShareStore.java      # measures insert-send to commit-return on an injected clock
├── filters/
│   ├── + ApiRoute.java            # route table (spec 004 adds to it)
│   ├── ~ ActionHeaderFilter.java  # rewritten; no longer a @Component
│   ├── + ActionRequestWrapper.java
│   ├── + UnsupportedContentTypeFilter.java
│   ├── + RefusalWriter.java
│   └── + PayloadBodyFreeAuditPayloadGenerationService.java   # D-AUDIT option 4
├── api/
│   ├── + ProblemReason.java       # reason → status and code (single table)
│   ├── + BoundedErrorAttributes.java
│   ├── + BoundedErrorController.java   # /error for every media type; counts 401 and 403
│   ├── + InstantFormat.java       # six fraction digits, UTC
│   ├── + SharesController.java    # the one `implements SharesApi` (generated, contract jar; R23)
│   ├── + ShareResponseMapper.java # read types → generated ShareSummary, KeyDetails, PullPage,
│   │                              #   SearchPage, DayVersions
│   ├── + PayloadResponses.java    # ServedPayload → ResponseEntity<byte[]>, ETag and headers
│   ├── + ShareParameters.java     # strict query-parameter parsing (unknown, repeated, conflicting)
│   ├── + ShareParametersInterceptor.java  # runs ShareParameters on the raw request before binding
│   ├── + ReadMetricsInterceptor.java    # requests and duration, by route and status
│   └── + ReadApiExceptionHandler.java   # extends ResponseEntityExceptionHandler
└── config/
    ├── + ApiWebConfig.java        # filter registrations, ErrorAttributes, authz-required check,
    │                              #   refusal observer, (option 4) audit payload bean
    ├── + MicrometerRefusalObserver.java
    ├── + ReadApiProperties.java, ReadApiConfig.java, MicrometerReadObserver.java
    ├── ~ Rules.java               # + atLeast(name, value, boundName, bound)
    ├── ~ IntakeConfig.java        # overrun threshold (T008 derived; T009 effective lag)
    ├── ~ IntakeProperties.java    # statement 10 s, lock 5 s defaults (T008)
    ├── + StatementTimeoutBackstop.java  # Hikari connection-init SQL from the intake statement timeout (T008)
    └── ~ MicrometerIntakeObserver.java  # + resultsstore.intake.visibility.overrun

src/main/resources/
├── ~ results-store-openapi.yaml   # paths, parameters, headers, schemas (T001); kept identical to the
│                                  #   contract jar's openapi/openapi-spec.yml (drift test, T009)
├── ~ acl/results-store-rules.drl  # one allow rule per action (T001)
├── + db/migration/V5__read_api.sql
└── ~ application.yaml             # intake statement 10s / lock 5s (T008); resultsstore.read.*,
                                   #   server.error.whitelabel.enabled=false

src/test/resources/application-test.yaml   # ~ header comment (T003)
docker-compose.yml                          # ~ short intake timeouts for the smoke (T012)
docker/wiremock/mappings/identity-stub.json # ~ lower priority default (T012)
docker/wiremock/mappings/identity-no-group.json  # + header-matched caller in neither group (T012)
scripts/container-smoke.sh                  # ~ HTTP checks (T012)
build.gradle                                # ~ apiSpec configuration and dependency; apispec-validation applied (T009)
gradle/libs.versions.toml                   # ~ api-cp-crime-results-store: rs-<sha7> draft, then 0.2.0 (T012), 0.3.0 (T013)
.github/workflows/ci-released.yml           # ~ validate-api-spec-version job (T009)

src/test/java/uk/gov/hmcts/cp/resultsstore/
├── acl/          ~ ResultsStoreRulesTest
├── api/          + OpenApiDocumentTest, OpenApiContractTest, OpenApiContractDriftTest, ProblemReasonTest,
│                   BoundedErrorAttributesTest, BoundedErrorControllerTest, InstantFormatTest, ShareParametersTest,
│                   ShareParametersInterceptorTest, ShareResponseMapperTest, SharesControllerTest,
│                   SharePayloadControllerTest, HearingDaySharesControllerTest, ContentNegotiationTest,
│                   ReadApiExceptionHandlerTest, ReadMetricsInterceptorTest
├── filters/      + ApiRouteTest, ActionRequestWrapperTest, UnsupportedContentTypeFilterTest,
│                   PayloadBodyFreeAuditPayloadGenerationServiceTest; ~ ActionHeaderFilterTest
├── domain/       + ReadEndpointTest, ReadOutcomeTest, RouteRefusalTest, ShareViewTest, DayYouthFilterTest,
│                   SearchCursorTest, EnvelopeMetadataTest; ~ PayloadChecksumTest, SharedDaysTest
├── application/  + ShareReadServiceTest; ~ IntakeServiceTest
├── persistence/  + JdbcShareQueriesIT, JdbcShareQueriesTest, ReadQueriesPlanIT;
│                   PooledStatementTimeoutIT; ~ FlywayMigrationIT, JdbcShareStoreIT
├── config/       + ReadApiConfigTest, ApiWebConfigTest, MicrometerReadObserverTest,
│                   MicrometerRefusalObserverTest, StatementTimeoutBackstopTest; ~ ConfigurationValidationTest,
│                   IntakeConfigTest,
│                   SweepSchedulingConfigTest, MicrometerIntakeObserverTest
└── integration/  + ReadApiIT, AuditIT, FilterOrderIT; ~ AuthzIT, ActuatorIntegrationTest, NoPayloadInLogsIT
```

**Structure Decision**: one Spring Boot service, packages as in the design rules. Branching logic (route
matching, parameter parsing, cursors, limits, the `ETag`) lives outside `config/` so the coverage gate
measures it. `ApiRoute` is the single source for the filter, `OpenApiContractTest` and
`ResultsStoreRulesTest`; spec 004 adds its operations routes to it. The HTTP surface is the generated
`SharesApi` from the contract jar; `SharesController` is its only implementation (research R23).

## Phase plan

Four phase-gate phases, exactly as the rulings (B11). The *Covers* column below is a summary; the
*Covers* lines in tasks.md are the full list and win where the two differ. Each task is test first; each phase is green on its
own before the next starts. Phase B needs the route table and refusal port of phase A; phase C needs the
schema, ports and service of phase B; phase D needs everything (D-RAW accepted, E2).

### Phase A: contract and edge

| Task | Test first | Then | Covers |
|---|---|---|---|
| T001 | `ResultsStoreRulesTest` (rewritten): each read action allowed for "System Users" and for "Second Line Support"; refused for a caller in neither; refused with the right name and the wrong method or path; unknown action refused; exactly one rule per action. `OpenApiDocumentTest`: parses; paths exactly the `ApiRoute` templates; path parameters declared; problem body on every `4xx`/`5xx`; payload headers declared; the audit glob resolves exactly one document. `ApiRouteTest`: templates match their samples and nothing Spring would not route; pull and search told apart by `storedAfterSeq` | `results-store-openapi.yaml`, `results-store-rules.drl`, `ApiRoute`, `ReadEndpoint`, `RouteRefusal` | FR-001, FR-046, FR-049, FR-053 |
| T002 | `ActionHeaderFilterTest` (rewritten), `ActionRequestWrapperTest`: derived action whatever was sent; vendor `Content-Type`/`Accept` neutralised; `404`/`405` refusals with no path echo; actuator and `/error` pass with `CPP-ACTION` removed; every refusal counted through a recording `RefusalObserver` | `ActionHeaderFilter` (no longer `@Component`), `ActionRequestWrapper`, `RefusalWriter`, `ProblemReason`, `RefusalObserver` | FR-047, FR-048 |
| T003 | `UnsupportedContentTypeFilterTest`, `BoundedErrorAttributesTest`, `BoundedErrorControllerTest` (also `Accept: text/html`; `401`/`403` counted), `ProblemReasonTest`, `MicrometerRefusalObserverTest`, `ApiWebConfigTest` (authz required), `FilterOrderIT`; `AuthzIT` and `ActuatorIntegrationTest` moved onto Postgres | `UnsupportedContentTypeFilter`, `BoundedErrorAttributes`, `BoundedErrorController`, `ApiWebConfig`, `MicrometerRefusalObserver`, `application.yaml`, `application-test.yaml` comment | FR-042, FR-043, FR-048, FR-050 |

### Phase B: data and application

| Task | Test first | Then | Covers |
|---|---|---|---|
| T004 | `FlywayMigrationIT`: V5 objects (trigger, youth pull, court pull and search indexes); trigger sets `stored_at` after a clock read taken before the insert; supplied `stored_at` overridden; sequence cache 1; V1–V4 unchanged | `V5__read_api.sql` | FR-019, FR-054 |
| T005 | `SearchCursorTest`, `DayYouthFilterTest`, `ShareViewTest`, `ReadOutcomeTest`, `PayloadChecksumTest`, `EnvelopeMetadataTest`, `SharedDaysTest`, `MicrometerReadObserverTest` | domain read types, `_metadata` strip, London day range, ports, `MicrometerReadObserver` | FR-006, FR-011, FR-027 (day translation), FR-029, FR-033 (strip), FR-039, FR-055 |
| T006 | `JdbcShareQueriesIT` (race, bound, high-water, `visibleUpTo`, filters, exact court, both search forms, day, payload without `_metadata`), `JdbcShareQueriesTest`, `ReadQueriesPlanIT` on the constants | `JdbcShareQueries` | FR-009–FR-016, FR-026–FR-033, FR-038, FR-039; SC-001, SC-002, SC-007 |
| T007 | `ShareReadServiceTest` (plain mocks) | `ShareReadService` and its records and exceptions | FR-010, FR-013, FR-014, FR-026–FR-029, FR-033, FR-034 |
| T008 | `IntakeServiceTest`, `JdbcShareStoreIT`, `MicrometerIntakeObserverTest`, `IntakeConfigTest` (overrun), `ConfigurationValidationTest` (new intake defaults), `StatementTimeoutBackstopTest`, `PooledStatementTimeoutIT` | the overrun measure and counter; intake statement 10 s and lock 5 s defaults; the Hikari backstop | FR-020, FR-061, FR-062; SC-009, SC-015 |

### Phase C: serving, wiring, end to end, documents

| Task | Test first | Then | Covers |
|---|---|---|---|
| T009 | `ConfigurationValidationTest`, `ReadApiConfigTest`, `IntakeConfigTest`, `SweepSchedulingConfigTest` (stub data source), `OpenApiContractDriftTest` | `ReadApiProperties`, `ReadApiConfig`, `Rules` overload, `application.yaml`; overrun threshold from the effective lag (90 s by default); the `apiSpec` dependency on `rs-2c5bc08`, `apispec-validation.gradle` applied, the `validate-api-spec-version` job in `ci-released.yml` | FR-017, FR-018, FR-045, FR-056, FR-063; SC-008 |
| T010 | api repo first: the payload body as `byte[]` (pull request, `rs-<sha7>` draft, pin); then `ShareParametersTest`, `ShareParametersInterceptorTest`, `InstantFormatTest`, `ShareResponseMapperTest`, `SharesControllerTest`, `SharePayloadControllerTest`, `HearingDaySharesControllerTest`, `ContentNegotiationTest`, `ReadApiExceptionHandlerTest`, `ReadMetricsInterceptorTest`, `OpenApiContractTest` | `SharesController implements SharesApi`, the mapper to the generated models, `PayloadResponses`, the parameter interceptor, advice, metrics interceptor, `304` | FR-002–FR-008, FR-031–FR-037, FR-042–FR-044, FR-053, FR-063 |
| T011 | `ReadApiIT`, `AuditIT`, `NoPayloadInLogsIT`, `PayloadBodyFreeAuditPayloadGenerationServiceTest` | fixes found; D-AUDIT option 4 (E1) | US1–US7; FR-012, FR-022–FR-030, FR-039, FR-051, FR-052; SC-003–SC-006, SC-011 |
| T012 | smoke HTTP checks first (red on the old build); review grep for the old pull-safety and audit wording | compose and WireMock changes; documents: constitution 2.2.0, design rules, spec 001 pointers, contracts reconciled, page-notes, `/speckit-analyze`, Deferred; Release `v0.2.0` of the api repo and the bump from the draft | FR-057–FR-060, FR-063; SC-012, SC-013 |

### Phase D: arrived text (D-RAW accepted, E2)

| Task | Test first | Then | Covers |
|---|---|---|---|
| T013 | api repo first: the arrived operation (pull request, `rs-<sha7>` draft, pin); then rules, OpenAPI, drift and route tests gain the route (red); `JdbcShareQueriesIT`, `ShareReadServiceTest`, `SharePayloadControllerTest`, `ReadApiIT`, `AuditIT` arrived cases | the route end to end, served without `_metadata`, `ETag` over the served bytes; Release `v0.3.0` and the bump | FR-001 (arrived route), FR-041, FR-046 (arrived action), FR-063; US8; SC-014 |

Rules for every task: a unit test per class; an IT on Testcontainers Postgres, embedded Artemis or
WireMock for every persistence, messaging and HTTP path; latches or Awaitility, never sleeps; no payload
text in assertion or log output; one commit per task, red run quoted before green.

## Risks

1. The lag bound is not airtight: a WAL flush stall longer than the lag, a crash or failover during a
   commit, or a clock step can still present a lower number behind a consumer's cursor (research R4).
   Mitigations: the overrun counter; consumer reconciliation.
2. Consumers wait about 90 seconds for every share; a raised transaction timeout raises the lag, capped
   at 10 minutes. The pool-wide `statement_timeout` of 10 s (FR-062) also applies to the sweep and the
   receipt transaction; a job on a pooled connection that needs longer sets its own `SET LOCAL
   statement_timeout` inside its transaction. Flyway is not bound by it: it migrates on its own unpooled
   connection (a `SimpleDriverDataSource`) with `statement_timeout` lifted
   (`RESULTSSTORE_FLYWAY_STATEMENTTIMEOUT`, default `0`, no limit; contracts/configuration.md *Flyway's own
   connection and statement timeout*). The lower intake statement (10 s) and lock (5 s) timeouts
   end a slow store transaction sooner; that is a database error and the message is redelivered (V).
3. Read-time filter semantics will surprise consumers (out-of-order shares, changing `versionNumber`,
   rows rewritten in place). The contract's normative text and the page notes carry this; YOT's redesign
   assumptions (v3 request id, overlap re-read, latest-only skip) need updating.
4. Audit body capture (D-AUDIT, E1): option 4 relies on the library's public `generatePayload` signature
   and its `contextPath` form, pinned by `AuditIT`; a library upgrade that changes them fails the test.
   Option 2 (the library's own switch) is asked for in parallel.
5. Refused requests (`401`, `403`, `404`, `405`, `415`) are not audited, only counted; constitution
   VII's current wording needs the 2.2.0 change (accepted, E13).
6. `jsonb::text` is not documented as byte-stable across PostgreSQL major versions; an upgrade can change
   `ETag`s for unchanged content (D-JSONB-PROMISE, E7). The text form and the arrived endpoint are written
   by the service's JSON library; an upgrade of it can do the same. Consumers verify each response against
   its own `ETag`.
7. A proxy that compresses or re-encodes `/payload` would break the strong-`ETag` promise; proxies are
   outside this repository.
8. Memory: about three copies of each payload while the audit filter buffers; no streaming possible.
9. The day's versions are unpaged; the largest day in production is not measured.
10. Unconditional wiring puts every Spring context test on Postgres: slower suites.
11. A gateway that rewrites paths, or a change of servlet mapping, must be re-tested against the route
    table; the `404`-before-`401` order on unknown paths is intentional.
12. V5's plain `CREATE INDEX` blocks intake inserts while it builds if it deploys after live capture
    starts; then use `CONCURRENTLY` with Flyway's `executeInTransaction=false`.
13. Spec 004 edits the same route table, filter, rule file and OpenAPI document; 003 lands first. Its
    operations API is expected to be published from the same contract repository, api repo first
    (research R23 C4).
14. Not verified: PostgreSQL internals behind the `pg_stat_activity` verdict; that `statement_timeout`
    applies to `COMMIT` and to a synchronous-replication wait; the `AuditFilter`'s effective order in
    Boot 4 (`FilterOrderIT` checks it); the `ResponseInfo.contextPath` form (`AuditIT` pins it); the
    production PostgreSQL version and whether it uses synchronous replication (D-PG-VERSION / HA, E11:
    a recorded risk, not a decision; PostgreSQL 17's `transaction_timeout` is a later tightening).
15. Phase C builds on a draft contract (`rs-<sha7>`); each later `team/rs` commit publishes a new draft
    while phase C runs. The service pins one exact draft, and T012 moves it to the release `0.2.0`
    before anything is released; `validateApiSpecVersions` refuses a release that still names a draft.

## Complexity Tracking

| Deviation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Constitution VII says "every request is audited … with the library's default settings"; 003 leaves refused requests (`401`, `403` from authorisation; `404`, `405`, `415` from its own filters) unaudited (counted instead) and replaces the payload body in the audit event (D-AUDIT option 4). Constitution II 2.1.0 says "the read API serves the working copy, and the text when the working copy is empty"; 003 serves both without `_metadata` (E8) | The library cannot audit a request its own authorisation filter refused before the audit filter runs, and our `404`/`405`/`415` refusals happen before it too. Reviewers of T011 (which lands the departure) and T012 (which ends it) read this row as the written justification the constitution asks for. Copying whole payloads (youth and special-category data) into audit events moves them outside this service's retention. No legacy CP query service returns the message envelope, so the store does not either (E8) | Auditing refusals needs a filter of our own before authorisation that duplicates the library's event; keeping the default body copy is option 1, rejected (E1); serving `_metadata` breaks the estate convention (E8). Resolved by the 2.2.0 wording in T012 (E1, E8, E13) |
