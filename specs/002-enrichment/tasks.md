---

description: "Task list for feature 002: enrichment"
---

# Tasks: Enrichment

**Input**: Design documents from `/specs/002-enrichment/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R25), data-model.md, contracts/ (progression-lookup,
metrics, configuration, schema), quickstart.md; constitution 2.0.0 (2.1.0 at the end of T010)

**Tests**: Mandatory (Principle X). Each task names its test classes and cases first, then the
production files. The implementer writes the tests, runs them, records the RED run under the task (a
failing assertion, never a compile error: land compile-safe seams first), then writes the minimum
production code and records the GREEN run. One commit per task, the test at or before the production
code. The task is ticked (`[X]`) in the commit that completes it.

**Organisation**: three phases (A, B, C), exactly as plan.md "Phase plan" and the orchestration plan's
Step 2. Each phase is one run of `.claude/workflows/phase-gate.js` over a contiguous task range. The
user stories cut across the phases (the step is built layer by layer: values and client, then
enricher, store and seam, then sweep and smoke), so every task carries the tags of the stories it
serves; the story-to-task map is under "Dependencies".

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (disjoint files, no dependency on an unfinished task)
- **[USn]**: the user story (spec.md) the task serves
- Paths are repository-relative and written out in full: `src/main/java/uk/gov/hmcts/cp/resultsstore/…`,
  `src/test/java/uk/gov/hmcts/cp/resultsstore/…`.

## Phase-gate invocations

One run per phase, in order. `baseCommit` is `git rev-parse HEAD` in the tree at the moment the phase
starts (for phase A, the commit that adds this file). Each run needs the previous phase's run to have
ended with every reviewer at PASS.

| Phase | Invocation (paths absolute) |
|---|---|
| A | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/002-enrichment", "tasks": ["T001","T002","T003"], "baseCommit": "<HEAD at phase A start>", "codex": true, "maxRemediations": 1}` |
| B | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/002-enrichment", "tasks": ["T004","T005","T006","T007"], "baseCommit": "<HEAD at phase B start>", "codex": true, "maxRemediations": 1}` |
| C | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/002-enrichment", "tasks": ["T008","T009","T010"], "baseCommit": "<HEAD at phase C start>", "codex": true, "maxRemediations": 1}` |

## Gate used by every task's "Done when"

`flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew build pmdMain pmdTest jacocoTestReport`
exits 0. `build` runs `check`, which already includes `jacocoTestCoverageVerification` (line 0.88,
branch 0.85; `config/**` excluded, gradle/test.gradle). Written below as **the gate**.

## Rules for every task (orchestration plan, "Test coverage rule")

- A unit test per production class; an IT on Testcontainers Postgres (`support/PostgresTestSupport`),
  the embedded Artemis broker (`support/EmbeddedBrokerSupport`) or in-process WireMock for every
  persistence, messaging and HTTP path.
- Latches or Awaitility, never `Thread.sleep` (`failFast` is on).
- No payload, message text or progression body in assertion messages or log output; ids only. Never
  the system user id in a log line.
- WireMock stubs and `verify()` match the per-id URL; no global counts, no `resetAll()` on a shared
  server (research R23).
- Explicit imports only; constructor injection; records; PMD clean (`OnlyOneReturn`,
  `AvoidCatchingGenericException`, `FieldDeclarationsShouldBeAtStartOfClass`,
  `AvoidDuplicateLiterals`: research R24).
- Migrations V1 to V4 are never edited; 002 adds none.

## Wiring notes

The `test` profile has no datasource, the listener off and (from T001) enrichment off, so context-load
tests (`ActuatorIntegrationTest`, `AuthzIT`) run without Docker or progression. That stays true:

- **T001 registers the properties.** `ConfigurationValidationTest` builds its context from
  `IntakeConfig`, so `EnrichmentProperties` and `ProgressionProperties` are added to `IntakeConfig`'s
  `@EnableConfigurationProperties` in T001 (plan.md puts the registration in T003; it moves forward
  because T001's own tests need it). `application-test.yaml` gets `resultsstore.enrichment.enabled:
  false` in T001.
- **T002 builds no bean.** `ProgressionApplicationClientTest` constructs the client directly over an
  in-process WireMock; nothing in the context needs `ProgressionApplications` yet.
- **T003 adds the bean with no consumer.** `ProgressionConfig` creates the `RestClient` and the
  `ProgressionApplications` bean only when `resultsstore.publicevents.enabled` and
  `resultsstore.enrichment.enabled` are both true. Nothing injects it until T006, so no stand-in is
  needed.
- **T005 changes shapes `IntakeService` uses.** `StoreRequest` gains the parsed copy and the flag;
  `StoreResult` gains `EnrichedCopyRefused`; `ShareStore` gains `storedShareId` and
  `payloadForExtraction`. In T005 `IntakeService` gets only the change that keeps it compiling and its
  behaviour as in 001: the arrived text as the parsed copy, flag false; an `EnrichedCopyRefused` it
  cannot yet receive throws `IllegalStateException`. T006 replaces both, test-first. `payloadText`
  stays on `ShareStore` until T008, so the sweep keeps reading the text until then.
- **T006 injects the port optionally.** `IntakeConfig` passes `ObjectProvider<ProgressionApplications>
  .getIfAvailable()`; when it is absent (enrichment off, as in the test profile) `IntakeService` runs
  with enrichment off and counts `skipped{disabled}` for a share needing lookups. Existing ITs with
  `resultsstore.publicevents.enabled=true` (`IntakeIT`, `NoPayloadInLogsIT`, `JdbcShareStoreIT` and
  friends) keep running with enrichment off until T007 switches `IntakeIT` and `NoPayloadInLogsIT` on
  against `support/ProgressionStub` with `@DynamicPropertySource`. No `@MockitoBean` stand-in is
  needed at any point; if the implementer finds one is, it is removed in the task that lands the real
  bean.
- **T006 widens `IntakeObserver`.** The four new methods are abstract; every implementer
  (`MicrometerIntakeObserver`, and any recording double in tests) is updated in the same commit.

---

## Before phase A (done, no task id)

- Step 0 of the orchestration plan: the 404 check on the local stack is recorded in research.md R3
  (`200 {}` for an unknown id; 404 only for a wrong sub-path or an unrouted prefix). The error table
  stands as written; FR-022's fallback clause does not apply.
- Branch `002-enrichment`; spec.md approved; plan.md, research.md, data-model.md, quickstart.md and
  contracts/ written.

---

## Phase A: values, client, wiring (T001–T003)

**Purpose**: the bounded values, the settings and their start-up checks, and the progression client
behind its port, with every row of the status and body table pinned. Nothing calls the client yet.
Blocks phase B.

**Independent test**: `ConfigurationValidationTest` and `IntakeConfigTest` prove the settings and the
conditional bean; `ProgressionApplicationClientTest` proves every row of
contracts/progression-lookup.md against an in-process WireMock.

- [X] T001 [US2] [US4] [US5] [US8] Test first: `IntakeStageTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeStageTest.java, `IntakeFailureCauseTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeFailureCauseTest.java, `ApplicationLookupOutcomeTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ApplicationLookupOutcomeTest.java, `EnrichmentSkipTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/EnrichmentSkipTest.java, `ConfigurationValidationTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeStage.java (+ `ENRICH`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/IntakeFailureCause.java (+ six `PROGRESSION_*`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ApplicationLookupOutcome.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/EnrichmentSkip.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/EnrichmentProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/ProgressionProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/Rules.java (+ `absoluteHttpUrl`, `uuid`), registration in src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java, src/main/resources/application.yaml (block from contracts/configuration.md), src/test/resources/application-test.yaml (`resultsstore.enrichment.enabled: false`)
  - Cases: `IntakeStage.ENRICH` tags `enrich`; the six causes tag `progression_rejected`, `progression_refused`, `progression_unavailable`, `progression_unreachable`, `progression_timeout`, `progression_malformed`; `fromSqlState` never returns a `progression_*` cause (every SQLSTATE row of the existing table plus a non-database failure); `ApplicationLookupOutcome` tags `enriched`, `not_found`, `not_finalised`, `no_results`, `invalid_id`; `EnrichmentSkip` tags `disabled`, `already_stored`, `unstorable_results`; every tag lower case and from the fixed list. `ConfigurationValidationTest`: defaults bind (enabled true, connect 5 s, read 10 s) when a base URL and user id are supplied; start stops for base URL `localhost:8080`, `ftp://x`, `http://x/path`, `http://x?q=1`, `http://x#f`; connect `0s` and `31s`; read `0s` and `61s`; user id `not-a-uuid`; `http://x/` and `https://x:8443` accepted; blank base URL and blank user id accepted with enrichment off; the failure names the property, never its value.
  - Covers: FR-024, FR-025, FR-026 (shape rules), FR-027 (values), FR-028 (values); contracts/configuration.md, contracts/metrics.md tag lists.
  - Done when: the five test classes green; the gate green.
  - RED (seams: the new enum constants, `ApplicationLookupOutcome`/`EnrichmentSkip` with `tag()` returning
    `name()`, the two property records with no defaults, no checks and no registration):
    `ApplicationLookupOutcomeTest` → `every_tag_should_come_from_the_fixed_list() FAILED`
    `Expecting actual: ["ENRICHED", "NOT_FOUND", …] to contain exactly (and in same order): ["enriched", "not_found", …]`;
    `EnrichmentSkipTest` → `reason_should_have_its_lower_case_tag … [1] reason = "DISABLED" FAILED`
    `expected: "disabled" but was: "DISABLED"` (4 of 4 failed);
    `ConfigurationValidationTest` → `progression_value_at_a_boundary_should_be_accepted … base-url=http://x FAILED`
    `to have a single bean of type: <…config.ProgressionProperties> but found no beans of that type`.
    `IntakeStageTest` and the extended `IntakeFailureCauseTest` were green at the seam: their tag is the
    existing `name().toLowerCase` shared by every constant, so adding the constant is the whole change
    (and `fromSqlState` was already free of `progression_*` causes; the rows pin both).
    Red evidence re-established after gate round 1 (Principle X): the two classes as committed in
    28fa716 were run against the pre-T001 code (4a5575f, extracted outside the tree; in
    `IntakeFailureCauseTest` the one method that names the new constants in code,
    `from_sql_state_should_never_name_a_progression_cause`, was left out so the class compiles):
    `IntakeStageTest` → `every_tag_should_come_from_the_fixed_list() FAILED`
    `Expecting actual: ["receipt", "store"] to contain exactly (and in same order): ["receipt", "store", "enrich"]`;
    `IntakeFailureCauseTest` → `every_tag_should_come_from_the_fixed_list() FAILED`
    `Expecting actual: ["lock_timeout", "statement_timeout", "database", "other"] to contain exactly … but could
    not find the following elements: ["progression_rejected", "progression_refused", …]`. Both fail on the
    assertion, so the fixed-list tests would have caught a missing constant. For later enum extensions the
    fixed-list test is run before the constant is added and that failure quoted as the RED line.
  - GREEN: the five classes, 97 tests, 0 failures (`IntakeStageTest` 4, `IntakeFailureCauseTest` 23,
    `ApplicationLookupOutcomeTest` 6, `EnrichmentSkipTest` 4, `ConfigurationValidationTest` 60);
    `pmdMain pmdTest` clean.
  - Note: `ProgressionProperties.toString()` leaves out the system user id and, since gate round 1, the
    base URL (never logged).
    Full suite (gate round 1): the GREEN lines above count the task's own classes only. The full
    `./gradlew test` was red at 28fa716, 91dc7dc and 52104c2 (the 001 `MicrometerIntakeObserverTest`,
    `Expected size: 15 but was: 30`, fixed in f9571ad below T001), so the tick was early for those
    commits. After the gate round 1 fixes the full build (`build pmdMain pmdTest jacocoTestReport`)
    passes: 819 tests, 0 failures, 0 skipped; JaCoCo line 99.5 %, branch 98.9 %. From here a task is
    ticked only on a green full-suite run, quoted under its GREEN line.
  - Follow-up found by the phase gate: `MicrometerIntakeObserver` pre-registers `intake.failed` for every
    stage × cause, so T001's new stage and causes added 22 pairs the contract never lists (for example
    `stage=receipt,cause=progression_timeout`), and the 001 `MicrometerIntakeObserverTest` failed. Fixed
    test-first with `IntakeFailureCause.belongsTo(IntakeStage)` (database causes with receipt and store,
    `progression_*` with enrich only, `other` everywhere), the observer pre-registering only those 15 pairs.
    RED (seam `belongsTo` always true): `IntakeFailureCauseTest` →
    `cause_should_belong_to_the_stages_the_contract_pairs_it_with … [1] cause = "LOCK_TIMEOUT" … FAILED`
    `[enrich]` expected false; `MicrometerIntakeObserverTest` →
    `every_meter_should_be_registered_at_start_with_exactly_its_tag_sets() FAILED`
    `Expected size: 15 but was: 30`. GREEN: `IntakeFailureCauseTest` 33, `MicrometerIntakeObserverTest` 20,
    0 failures. The enrichment meters themselves (`applications`, `lookup`, `skipped`, `applied`) stay
    with T006.
  - Follow-up from gate round 1 (settings in logs): `toString()` was unpinned and still printed the
    base URL, an internal connection detail, and a base URL could carry user info
    (`http://user:secret@host`). `toString()` now prints only the two timeouts, and the base URL rule
    refuses user info (contracts/configuration.md updated). `ConfigurationValidationTest` gains the
    `toString` case and two base-URL rows: user info, and `http://[::1`, not a URI at all (the
    `URISyntaxException` branch, refused already, so green at once).
    RED: `bad_progression_value_should_stop_the_service_starting_without_naming_the_value … [6] setting =
    "resultsstore.progression.base-url=http://user:secret@x" … FAILED` `to have failed but context started
    successfully`; then `settings_should_print_their_timeouts_but_neither_the_base_url_nor_the_system_user_id()
    FAILED` `Expecting actual: "ProgressionProperties[baseUrl=http://progression.example, …]" not to contain:
    "progression.example"`.
    GREEN: `ConfigurationValidationTest` 63, `ProgressionConfigTest` 9, `IntakeConfigTest` 8, 0 failures;
    `pmdMain pmdTest` clean.

- [X] T002 [US1] [US3] [US4] [US5] [US8] Test first: `ProgressionApplicationClientTest` (in-process `WireMockServer`, dynamic port) in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/progression/ProgressionApplicationClientTest.java, `DeadlineInputStreamTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/progression/DeadlineInputStreamTest.java, `NoRedirectRequestFactoryTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/progression/NoRedirectRequestFactoryTest.java, the new constructor's case in a `RetryableIntakeExceptionTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/RetryableIntakeExceptionTest.java; with support src/test/java/uk/gov/hmcts/cp/resultsstore/support/ProgressionStub.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ProgressionApplications.java (port: `ApplicationAnswer find(UUID)`, throws `RetryableIntakeException(ENRICH, cause)`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ApplicationAnswer.java (sealed: `Found(JsonNode courtApplication)` | `NotFound`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/RetryableIntakeException.java (+ constructor with the failed class name and no chained cause; javadoc names both adapters), src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/progression/ProgressionApplicationClient.java, src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/progression/NoRedirectRequestFactory.java, src/main/java/uk/gov/hmcts/cp/resultsstore/adapter/progression/DeadlineInputStream.java
  - Cases (one test per row of contracts/progression-lookup.md): GET on `/progression-query-api/query/api/rest/progression/applications/{id}` with `Accept: application/vnd.progression.query.application-only+json` and `CJSCPPUID`, the id sent as a path variable, no other store-set header, no body; `FINALISED` with results → `Found`; `200 {}` and `{"courtApplication": null}` → `NotFound`; `courtApplication` with any status or no results → `Found` (classification of the application is the enricher's, T004); 404 → `progression_rejected`; 302 with a `Location` → `progression_rejected` and WireMock sees no request at the target; 201, 204 → `progression_rejected`; 400, 405, 406, 410, 415 and an unlisted status (e.g. 418) → `progression_rejected`; 401, 403 → `progression_refused`; 408, 429, 500, 502, 503, 504 → `progression_unavailable`; closed port and unknown host → `progression_unreachable`; `Fault.CONNECTION_RESET_BY_PEER` → `progression_unreachable`; fixed delay past the read timeout → `progression_timeout`; chunked dribble past the deadline with each read under the socket timeout → `progression_timeout`; `Fault.MALFORMED_RESPONSE_CHUNK` on a 200, an HTML 200, an empty 200, an array body, trailing tokens, `courtApplication` a string, `judicialResults` an object → `progression_malformed`; exactly one request on a 503 (no retry); decimals `1.10` and `12345678901234567890.123` in a result read with value and written precision kept; captured log (`support/CapturedLog`) holds no body marker and no user id; every thrown `RetryableIntakeException` has stage `ENRICH`, the stated cause, the failed class name and `getCause() == null`, and its message holds no body or Jackson text. `DeadlineInputStreamTest`: reads pass before the deadline; a read after it throws `SocketTimeoutException`; `close` delegates. `NoRedirectRequestFactoryTest`: the prepared connection has `getInstanceFollowRedirects() == false` and the configured timeouts.
  - Covers: FR-007, FR-008, FR-009, FR-015 (reading), FR-021, FR-022, FR-023, FR-032 (logs); SC-003, SC-008 (client half).
  - Done when: the four test classes green; the gate green.
  - RED (seams: the port and `ApplicationAnswer` as specified; the new `RetryableIntakeException`
    constructor with the plain message and no class name kept; `NoRedirectRequestFactory` passing straight
    to `super`; `DeadlineInputStream` a bare `FilterInputStream`; the client's `find` answering `NotFound`
    without a request). The suite runs with `failFast`, so each class was run alone:
    `RetryableIntakeExceptionTest` → `with_a_failed_class_name_should_chain_no_cause_and_name_the_class() FAILED`
    `Expecting Optional to contain: "UnexpectedEndOfInputException" but was empty.`;
    `NoRedirectRequestFactoryTest` → `prepared_connection_should_not_follow_redirects_and_should_carry_the_timeouts() FAILED`
    `Expecting value to be false but was true`;
    `DeadlineInputStreamTest` → `buffer_read_after_the_deadline_should_time_out() FAILED`, `read_that_ends_past_the_deadline_should_time_out() FAILED`
    `Expecting code to raise a throwable.` (the read-before-deadline and close cases passed at the seam);
    `ProgressionApplicationClientTest` → `not answered > connection_reset_before_the_status_line_should_be_unreachable() FAILED`
    `Expecting code to raise a throwable.` (first failure; the rest skipped by `failFast`).
  - GREEN: the four classes, 62 tests, 0 failures (`ProgressionApplicationClientTest` 54 across its
    nested groups: request 2, answered 11, statuses 22, not answered 5, malformed 13, logging 1;
    `DeadlineInputStreamTest` 5; `NoRedirectRequestFactoryTest` 1; `RetryableIntakeExceptionTest` 2);
    `pmdMain pmdTest` clean.
  - Notes: where no exception was thrown, the "failed class name" is a bounded label: `status 404` for a
    status row, and the JSON node's class (`MissingNode` for an empty body, `ArrayNode`, `StringNode`, …)
    for a wrong-shaped 200. Failures log one WARN line (application id, cause tag, that label); an
    answered lookup logs at DEBUG. The body is read whole through the deadline guard before it is
    parsed, so a deadline or socket timeout is never mistaken for a Jackson failure.
  - Follow-up from gate round 1 (no transport retry): `HttpURLConnection` resends a `GET` once when
    the connection fails before the status line, and no setting turns that off, so FR-009's one request
    per lookup did not hold. The factory now builds an Apache HttpClient 5 (`httpclient5`, version from
    the Boot BOM) with automatic retries, redirects, content compression, cookies and protocol upgrades
    off and no connection reuse; `NoRedirectRequestFactoryTest` pins the connect and socket timeouts on
    its `ConnectionConfig` with distinct values (3 s, 7 s).
    RED: `ProgressionApplicationClientTest` →
    `connection_reset_before_the_status_line_should_be_unreachable_and_sent_once() FAILED`
    `Expected size: 1 but was: 2`.
    GREEN: `ProgressionApplicationClientTest` 54, `NoRedirectRequestFactoryTest` 1, `ProgressionConfigTest` 8,
    0 failures (the closed-port row now reads `HttpHostConnectException`, a `ConnectException`);
    `pmdMain pmdTest` clean.
  - Follow-up from gate round 1 (whole-exchange deadline): the deadline guarded only the body, so a
    status line and headers sent a byte at a time ran past it, and a late non-200 was classified by its
    status instead of as `progression_timeout`. The factory now takes the deadline as a third argument
    (`ProgressionConfig` passes the read timeout) and cancels each request, closing its connection,
    once the deadline has passed since it was created; the client reads any I/O failure after its own
    deadline (set a moment earlier, at the start of `find`) as `progression_timeout`, whatever the
    exception (`SocketException` from a cancelled read). Test support: `support/DribblingServer`, which
    sends the whole response, head included, one byte every 60 ms. The fixed-delay row now uses a 5 s
    deadline so it proves the socket read timeout on its own (`SocketTimeoutException`).
    RED: `ProgressionApplicationClientTest` →
    `status_and_headers_dribbled_past_the_deadline_should_time_out(String) > [1] response = "HTTP/1.1 200 OK…" FAILED`
    `Expecting actual: 4.433635056S to be less than: 2.5S`; run alone, the 503 row →
    `expected: PROGRESSION_TIMEOUT but was: PROGRESSION_UNAVAILABLE`.
    GREEN: `ProgressionApplicationClientTest` 56 (not answered 7), `NoRedirectRequestFactoryTest` 1,
    `DeadlineInputStreamTest` 5, `ProgressionConfigTest` 8, 0 failures; `pmdMain pmdTest` clean.
  - The contract's "connect `SocketTimeoutException` → `progression_timeout`" row has no wire test (a
    connect that times out cannot be produced deterministically on CI). It is covered by construction:
    a connect timeout reaches the same `ResourceAccessException` catch and `timedOut` check as the read
    timeout row, which is pinned; the connect timeout's value is pinned on the factory's
    `ConnectionConfig` (`NoRedirectRequestFactoryTest`, `ProgressionConfigTest`).
    Full suite (gate round 1): the GREEN lines above count the task's own classes only. The full
    `./gradlew test` was red at 28fa716, 91dc7dc and 52104c2 (the 001 `MicrometerIntakeObserverTest`,
    `Expected size: 15 but was: 30`, fixed in f9571ad below T001), so the tick was early for those
    commits. After the gate round 1 fixes the full build (`build pmdMain pmdTest jacocoTestReport`)
    passes: 819 tests, 0 failures, 0 skipped; JaCoCo line 99.5 %, branch 98.9 %. From here a task is
    ticked only on a green full-suite run, quoted under its GREEN line.
  - Ruling 1 (orchestrator, phase A close-out: no drain on a non-200): closing an HttpClient 5 response
    drains its body (`EntityUtils.consume`), so a 503 whose body dribbled in was held until the deadline
    cancelled it. On any non-200 the client now aborts the exchange (`ProgressionExchange.abort()`,
    cancelling the transport request and closing its connection) before it throws, so nothing of the
    body is read or drained, and the status alone gives the cause. The factory puts each request's
    `ProgressionExchange` in the Spring request's attributes. The WARN line is written in `find` once the
    exchange has settled (the response closed), no longer while building the exception.
    RED: `ProgressionApplicationClientTest` →
    `answered with another status > unavailable_status_should_not_wait_for_a_dribbled_body() FAILED`
    `Expecting actual: 1.001059866S to be less than: 0.5S` (503, body dribbled in chunks over 3 s, read
    timeout and deadline 1 s).
    GREEN: `ProgressionApplicationClientTest` 57 (statuses 23; the new row ends in about 0.1 s with
    `progression_unavailable` and one WARN line naming no timeout), `ProgressionExchangeTest` 3,
    `NoRedirectRequestFactoryTest` 1, `ProgressionConfigTest` 9, 0 failures; `pmdMain pmdTest` clean.
  - Ruling 2 (orchestrator, phase A close-out: one absolute deadline): the client set its own deadline
    at the start of `find`, a moment before the factory armed its cancellation, and called any I/O
    failure past that instant a timeout. Now the factory fixes one instant when it creates the request
    and puts it in the request's `ProgressionExchange`; the scheduled cancellation (`expire()`) and the
    body guard (`DeadlineInputStream`) both use it, the exchange records that the scheduler cancelled
    it, and the client calls a failure `progression_timeout` only for a socket timeout (the guard's
    included) or an exchange the scheduler expired. The client no longer takes a deadline of its own
    (`ProgressionConfig` updated); contracts/progression-lookup.md "Transport" updated, with a row for
    the non-200 body of ruling 1.
    RED (seam: the exchange holding a deadline, `isExpired()` always false and `expire()` only
    cancelling; the factory fixing the deadline on `System.nanoTime()` rather than its clock; the client
    calling any failure past the exchange's deadline a timeout):
    `NoRedirectRequestFactoryTest` → `request_should_carry_one_absolute_deadline_fixed_when_it_is_created() FAILED`
    `expected: 16000000000L but was: 1066859564837548L`, and
    `deadline_should_cancel_the_request_and_record_that_it_did() FAILED` `ConditionTimeoutException: … was not
    fulfilled within 5 seconds`; `ProgressionExchangeTest` →
    `expiry_should_cancel_the_transport_request_and_be_recorded() FAILED` `Expecting value to be true but was
    false`; `ProgressionApplicationClientTest` →
    `not answered > failure_after_the_deadline_not_caused_by_it_should_not_be_a_timeout() FAILED`
    `expected: PROGRESSION_UNREACHABLE but was: PROGRESSION_TIMEOUT`, and
    `failure_after_the_scheduler_cancelled_the_request_should_be_a_timeout() FAILED`
    `expected: PROGRESSION_TIMEOUT but was: PROGRESSION_UNREACHABLE` (both over a stub transport whose
    request fails with a plain `SocketException`). `answer_after_a_pause_inside_the_deadline_should_be_found`
    (WireMock fixed delay of half the 1 s deadline) and `abort_should_cancel_the_transport_request_without_counting_as_the_deadline`
    were green at the seam; they pin that a late-but-in-time answer and the client's own abort are not timeouts.
    GREEN: `ProgressionApplicationClientTest` 60 (not answered 10), `NoRedirectRequestFactoryTest` 3,
    `ProgressionExchangeTest` 4, `DeadlineInputStreamTest` 5, `ProgressionConfigTest` 9, `IntakeConfigTest` 8,
    0 failures; `pmdMain pmdTest` clean.
  - Ruling 3 (orchestrator, phase A close-out: transport logs): HttpClient 5 logs every request header
    (`org.apache.hc.client5.http.headers`, `CJSCPPUID` with the system user id) and the raw bytes of each
    exchange (`org.apache.hc.client5.http.wire`, the body) at DEBUG, so raising the root level would put
    both in the logs. src/main/resources/logback.xml pins the two loggers `OFF` (there is no separate
    test logback; the tests load this one). `support/CapturedLog` gains `everyLogger()` (an appender on
    the root).
    RED: `ProgressionApplicationClientTest` →
    `logging > debug_logging_everywhere_should_hold_neither_the_system_user_nor_the_body() FAILED`
    `Expecting actual: "http-outgoing-4 >> CJSCPPUID: 0b0e7f5c-…" not to contain: "0b0e7f5c-1d2e-4f3a-9b8c-7d6e5f4a3b2c"`,
    and on the wire logger `"http-outgoing-4 << " "judicialResults": [{"label": "BODY-MARKER-7f3c"}]}}[\r][\n]""
    not to contain: "BODY-MARKER-7f3c"` (root at DEBUG, one 200 lookup with a body, every logger captured).
    GREEN: `ProgressionApplicationClientTest` 61 (logging 2), 0 failures; the DEBUG lines of the
    client's own and HttpClient's other loggers stay and hold neither value; `pmdMain pmdTest` clean.

- [X] T003 [US1] [US2] Test first: `IntakeConfigTest` (extended, `ApplicationContextRunner`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfigTest.java, `ProgressionConfigTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ProgressionConfigTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/config/ProgressionConfig.java (imported from src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java); confirm specs/002-enrichment/contracts/configuration.md matches what was built
  - Cases: `ProgressionApplications` bean present with publicevents and enrichment on; absent with either off; the built client carries the base URL (a WireMock stub at that host answers) and both timeouts (a WireMock fixed delay past a 1 s read timeout gives `progression_timeout`); with enrichment on, start fails with an `IllegalArgumentException` naming `resultsstore.progression.base-url` when it is blank and `resultsstore.progression.system-user-id` when it is blank, never the value; with enrichment off both may be blank and start succeeds; `ActuatorIntegrationTest` still green with the test profile.
  - Covers: FR-024, FR-025, FR-026; SC-009.
  - Done when: both test classes and `ActuatorIntegrationTest` green; the gate green.
  - RED (seam: `ProgressionConfig` with no `@Bean`, not imported, its method building a client with the
    base URL only, no user, no timeouts and no blank check):
    `ProgressionConfigTest` → `blank_base_url_should_stop_the_client_being_built(String) > [1] baseUrl = null FAILED`
    `Expecting code to raise a throwable.` (first failure; the rest skipped by `failFast`);
    `IntakeConfigTest` → `enabled_subscription_and_enrichment_should_build_the_progression_client() FAILED`
    `to have a single bean of type: <…application.ProgressionApplications> but found no beans of that type`.
  - GREEN: `ProgressionConfigTest` 8, `IntakeConfigTest` 7, `ActuatorIntegrationTest` 4 (test profile,
    enrichment off), with `ConfigurationValidationTest` 60 and `SweepSchedulingConfigTest` 4 still green;
    0 failures; `pmdMain pmdTest` clean.
  - Notes: the bean is conditional on `resultsstore.publicevents.enabled=true` and
    `resultsstore.enrichment.enabled=true` (missing counts as true, as the record's default does), so
    `SweepSchedulingConfigTest`, which starts `IntakeConfig` with the subscription on and no
    application.yaml, now sets enrichment off; the 001 wiring test of `IntakeConfigTest` supplies a base
    URL and a user id. The `RestClient` is built with `RestClient.builder()`, not Boot's customised
    builder, so nothing beyond the request factory, the base URL and the two headers shapes the request.
    The connect timeout is passed to the factory with the read timeout (`NoRedirectRequestFactoryTest`
    pins both on the connection); only the read timeout is proved over the wire.
  - contracts/configuration.md checked against what was built: matches (properties, defaults, no
    default for the base URL and user id, where the rules run, the conditional bean); no edit needed.
  - Follow-up from gate round 1 (connect timeout unpinned): the old over-the-wire test could not tell
    the connect timeout from the read timeout, so swapping them passed. The request factory is now a
    bean of its own (`progressionRequestFactory`, same two conditions; the context closes it), and
    `ProgressionConfigTest` and `IntakeConfigTest` build it from distinct values (connect 3 s, read 7 s)
    and assert the connect timeout, the socket timeout and the deadline on it.
    RED (seam: the factory bean built with the connect and read timeouts swapped):
    `ProgressionConfigTest` → `request_factory_should_carry_the_connect_timeout_and_the_read_timeout_as_read_and_deadline() FAILED`
    `expected: 3 SECONDS but was: 7 SECONDS`; `IntakeConfigTest` →
    `enabled_subscription_and_enrichment_should_build_the_progression_client() FAILED`
    `expected: 3 SECONDS but was: 7 SECONDS`.
    GREEN: `ProgressionConfigTest` 9, `IntakeConfigTest` 7, `ConfigurationValidationTest` 60,
    `SweepSchedulingConfigTest` 4, 0 failures; `pmdMain pmdTest` clean.
  - Follow-up from gate round 1 (FR-024 "on by default" unpinned at the bean): every test that expected
    the client set `resultsstore.enrichment.enabled=true`. `IntakeConfigTest` now starts with the
    subscription on and no enrichment property and expects the factory and the client.
    RED (mutation: `matchIfMissing` removed from both beans):
    `enrichment_should_be_on_when_its_switch_is_not_set() FAILED`
    `Expecting: <Started application …> to have a single bean of type: <…NoRedirectRequestFactory>`.
    GREEN (restored): `IntakeConfigTest` 8, 0 failures; `pmdTest` clean.
    Full suite (gate round 1): the GREEN lines above count the task's own classes only. The full
    `./gradlew test` was red at 28fa716, 91dc7dc and 52104c2 (the 001 `MicrometerIntakeObserverTest`,
    `Expected size: 15 but was: 30`, fixed in f9571ad below T001), so the tick was early for those
    commits. After the gate round 1 fixes the full build (`build pmdMain pmdTest jacocoTestReport`)
    passes: 819 tests, 0 failures, 0 skipped; JaCoCo line 99.5 %, branch 98.9 %. From here a task is
    ticked only on a green full-suite run, quoted under its GREEN line.
  - Ruling 4 (orchestrator, phase A close-out; one line of T009 brought forward): enrichment is on by
    default and the compose app had no system user id, so the bean refused to start and the compose app
    and scripts/container-smoke.sh no longer came up. docker-compose.yml gives the app service
    `RESULTS_STORE_SYSTEM_USER_ID: 00000000-0000-0000-0000-000000000000` (`CP_BASE_URL` already points at
    the WireMock container). T009 still adds the progression stub mapping; the smoke's messages carry no
    court application, so enrichment makes no call for them.
    Smoke (`flock … ./scripts/container-smoke.sh`): `PASS: readiness reported UP within the 60s budget`,
    `PASS: intake stored the share, dropped its duplicate and recorded the unreadable message`, no WARN or
    ERROR line, exit 0.
  - Phase A close-out full gate (`build pmdMain pmdTest jacocoTestReport jacocoTestCoverageVerification`,
    after rulings 1 to 3): 830 tests, 0 failures, 0 skipped; JaCoCo line 99.5 %, branch 98.7 %; exit 0.

---

## Phase B: enricher, persistence, seam, end to end (T004–T007)

**Purpose**: the pure enricher with parity on results' fixtures, the store binding the flag and the
working copy with its one fallback, the seam in `IntakeService` with its metrics, then the whole path
through the broker, Postgres and a progression stub. Depends on phase A (port, answer type, causes,
properties, bean).

**Independent test**: `ApplicationResultsParityTest` proves parity with results; `JdbcShareStoreIT`
proves what is stored; `IntakeServiceTest` proves the order and the counting; `IntakeIT` proves
US1–US7 end to end and `NoPayloadInLogsIT` the log half of US8.

- [X] T004 [P] [US1] [US2] [US3] Test first: `ApplicationResultsEnricherTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ApplicationResultsEnricherTest.java, `ApplicationResultsParityTest` (JSONAssert `STRICT`) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ApplicationResultsParityTest.java, `ShareIdentityParserTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ShareIdentityParserTest.java; fixtures copied unchanged from `cpp-context-results@063472490:results-event/results-event-processor/src/test/resources/testdata/application-final-results-enricher/` into src/test/resources/progression/ (`app1_adjourned_hearing.json`, `app1_progression_finalised.json`, `app1_adjourned_hearing_enriched.json`, `app2_adjourned_hearing.json`, `app2_progression_finalised_resultsamended.json`, `app2_adjourned_hearing_enriched.json`, `app3_resulted_hearing.json`, `app_progression_listed.json`) plus the authored several-applications pair src/test/resources/progression/multi_application_hearing.json and src/test/resources/progression/multi_application_hearing_enriched.json (research R4); then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ApplicationResultsEnricher.java (scan, outcome, enrich on a deep copy, serialise), src/main/java/uk/gov/hmcts/cp/resultsstore/application/Enrichment.java (record: tree, parsedCopy, applied), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareIdentityParser.java (reader: `USE_BIG_DECIMAL_FOR_FLOATS`, `STRIP_TRAILING_BIGDECIMAL_ZEROES` off)
  - Cases (`ApplicationResultsEnricherTest`): no `hearing`, no `courtApplications`, `courtApplications` not an array → no lookup; `judicialResults` missing, `null`, `[]` → lookup; non-empty array or another type → none; repeated id, also in mixed case, looked up once and applied to every occurrence; ids returned in array order; missing id and non-UUID id skipped and counted `invalid_id`; outcome of an answer: `FINALISED` with results → `enriched`, other or non-string or missing status → `not_finalised`, `FINALISED` with missing/`null`/`[]` results → `no_results`, `NotFound` → `not_found`; `amendmentDate`, `amendmentReason`, `amendmentReasonId` removed at a result's top level and every other field kept (incl. `isNewAmendment`, `fourEyesApproval`, `approvedDate`), progression's order kept; nested results (`courtApplicationCases[].offences[].judicialResults`) never copied or changed; only `judicialResults` copied from the answer; a replaced `null` or `[]` keeps its place and an added key goes last (asserted with `fieldNames()`); a non-object element copied unchanged; `applied` false and the parsed copy equal to the arrived text when nothing was added; the arrived body tree not changed; serialisation compact with `ESCAPE_NON_ASCII`, `\udc00` and non-ASCII escaped (research R6). `ApplicationResultsParityTest`, the six cases of research R4: app1 enriched; app2 amendment fields removed; app3 no lookup; app1 with the listed answer unchanged; app1 with `200 {}` unchanged; several applications (app1's, app2's and a `LISTED` third) against the authored expected file built from results' enriched fixtures. `ShareIdentityParserTest`: `1.10`, `1e3`, `12345678901234567890.123` survive parse and enriched serialisation with value and written precision.
  - Covers: FR-002, FR-003, FR-004, FR-010, FR-011, FR-012, FR-013, FR-014, FR-015; SC-001.
  - Done when: the three test classes green; the gate green.
  - RED (seams: `Enrichment` as specified; `ApplicationResultsEnricher` whose `scan` gives no lookups,
    whose `outcome` answers `NOT_FOUND` and whose `enrich` returns the arrived body and text unapplied;
    the share reader unchanged). Each class run alone (`failFast`):
    `ApplicationResultsEnricherTest` → `the scan > application_with_a_missing_or_invalid_id_should_be_skipped_and_counted() FAILED`
    `expected: Scan[lookups=[0a1b2c3d-…], invalidIds=5] but was: Scan[lookups=[], invalidIds=0]`;
    `ApplicationResultsParityTest` → `app2_results_should_lose_the_three_amendment_fields_as_results_removes_them() FAILED`
    `Expecting actual: [] to contain exactly (and in same order): [47663a56-…]` (3 of 3 run failed alike);
    `ShareIdentityParserTest` → `decimals_should_keep_their_value_and_written_precision_through_parse_and_enriched_serialisation() FAILED`
    `expected: 1.10 but was: 1.1`.
  - GREEN: `ApplicationResultsEnricherTest` 46, `ApplicationResultsParityTest` 6, `ShareIdentityParserTest` 102,
    0 failures; `pmdMain pmdTest` clean. Full gate (`build pmdMain pmdTest jacocoTestReport`): 883 tests,
    0 failures, 0 skipped; JaCoCo line 99.5 %, branch 98.8 %; exit 0.
  - Notes: fixtures copied byte for byte from `cpp-context-results@063472490` (checked with `cmp`). The
    several-applications pair was built with `jq` from results' files: app1's share with app1's, app2's and a
    third application (app2's, given the listed fixture's id `18299a7a-…`); the expected file takes the two
    enriched applications from results' own enriched fixtures. The enriched copy is written with
    `ESCAPE_NON_ASCII`, so `\udc00` comes out as `\uDC00` and `é` as `\u00E9`; `1e3` is written `1E+3`.
    The mocked-reader case of `ShareIdentityParserTest` stubs the two new reader features. `scan` counts an
    application with a missing or invalid id only when it needs results (one already resulted is left
    alone, whatever its id).
  - Gate round 1: the fixtures are results' own test data, copied unchanged from its test resources
    (`application-final-results-enricher/`); the party values in them (names, dates of birth, phone
    numbers, postcodes) are that repo's synthetic test values, not taken from a live system. The parity
    check that the parsed copy reads back as the enriched tree is now a boolean assertion, so a failure
    prints no hearing content (JSONAssert `STRICT` still reports field-level differences only).

- [X] T005 [P] [US1] [US6] [US7] Test first: `JdbcShareStoreIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStoreIT.java, with an application-bearing sample in src/test/java/uk/gov/hmcts/cp/resultsstore/support/SampleShares.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/StoreRequest.java (+ `parsedCopy`, `enrichmentApplied`; text stays the arrived text), src/main/java/uk/gov/hmcts/cp/resultsstore/application/StoreResult.java (`Stored` + `enrichmentApplied`; + `EnrichedCopyRefused`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareStore.java (+ `storedShareId(ShareIdentity)`, + `payloadForExtraction(UUID)`; `payloadText` kept until T008), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java (binds `:enrichmentApplied`; payload insert takes the parsed copy; enriched copy refused before or inside the transaction → `setRollbackOnly` + `EnrichedCopyRefused`; `storedShareId` as one autocommit read of `EXISTING_SHARE` with no lock; `payloadForExtraction` = `COALESCE(payload_json::text, payload_text)`), and the compile-only change in src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeService.java (see "Wiring notes")
  - Cases: an enriched share stores `payload_text` and `payload_sha256` byte for byte as arrived, `payload_json` equal to the enriched copy, `enrichment_applied` true, and `Stored.enrichmentApplied` true; an un-enriched share stores `CAST(payload_text AS jsonb)` and flag false (replaces the 001 assertion); unsafe arrived text → `payload_json` NULL and flag false (001 path unchanged); enriched copy holding `\u0000` → `EnrichedCopyRefused`, no transaction opened, nothing written; a class-22 refusal of the enriched copy inside the transaction → `EnrichedCopyRefused`, nothing written (share, payload, defendant, day rows all absent), and the next call on the same pooled connection works; a duplicate of an enriched share keeps the first payload and returns `Duplicate`; `storedShareId` finds a stored share and is empty otherwise, and returns while another transaction holds that hearing day's lock; `payloadForExtraction` prefers `payload_json` and falls back to the text when it is NULL; data-model invariants 2 and 4 hold over every row the suite wrote (the two SQL checks). `FlywayMigrationIT` unchanged and green.
  - Covers: FR-006 (the read), FR-016, FR-017, FR-018, FR-019 (store half); SC-006, SC-007.
  - Done when: `JdbcShareStoreIT` and `FlywayMigrationIT` green; the gate green.
  - RED (seams: `StoreRequest` with `parsedCopy` and `enrichmentApplied`, plus a seven-argument
    constructor for a share stored as it arrived (parsed copy = text, flag false), which the 001 call sites
    keep using; `StoreResult.Stored` + `enrichmentApplied` and `EnrichedCopyRefused`; `ShareStore` +
    `storedShareId` and `payloadForExtraction`; `JdbcShareStore` binding no flag, writing the text as the
    parsed copy, `storedShareId` always empty and `payloadForExtraction` reading `payloadText`;
    `IntakeService` throwing `IllegalStateException` on `EnrichedCopyRefused` (wiring notes). Run with
    `failFast`, then case by case:
    `JdbcShareStoreIT` → `stored_share_id_should_find_a_stored_share_and_be_empty_otherwise() FAILED`
    `Expecting Optional to contain: 3b7b5af2-… but was empty.`;
    `enriched_share_should_keep_the_arrived_text_and_checksum_and_store_the_working_copy_and_flag() FAILED`
    `expected: Stored[…, enrichmentApplied=true] but was: Stored[…, enrichmentApplied=false]`;
    `enriched_copy_holding_an_escaped_nul_should_be_refused_before_any_transaction_and_write_nothing() FAILED`
    `expected: EnrichedCopyRefused[] but was: Stored[…]`;
    `enriched_copy_the_database_refuses_should_roll_back_and_leave_the_connection_usable() FAILED`
    `expected: EnrichedCopyRefused[] but was: Stored[…]`;
    `payload_for_extraction_should_prefer_the_working_copy() FAILED` (the tree read back lacked the added
    results; the assertion now compares booleans so a failure prints no payload).
  - GREEN: `JdbcShareStoreIT` 25 (11 new), `FlywayMigrationIT` 121, 0 failures; `pmdMain pmdTest` clean.
    Full gate (`build pmdMain pmdTest jacocoTestReport`): 894 tests, 0 failures, 0 skipped; JaCoCo line
    99.4 %, branch 98.4 %; exit 0.
  - Notes: the enriched copy is refused before the transaction when `NulSafety` fails it (a counting
    `TransactionOperations` proves no transaction opened), and inside it on a class-22 refusal of its payload
    insert, which takes no savepoint: `setRollbackOnly()` and `EnrichedCopyRefused`, so nothing is written.
    The connection case runs both calls through one pooled connection (`SingleConnectionDataSource`): the
    refused call, then the re-run with the arrived copy, which stores with the flag false. An `@AfterEach`
    runs data-model invariants 2 and 4 over every row each case wrote (invariant 4 guarded with `CASE`, so
    a text `jsonb` cannot hold is never cast). `storedShareId` is proved not to wait on a held day lock by
    calling it from another thread while a transaction holds `FOR UPDATE` on the day row, and a failed read
    of either new method is classified at `store` (a `DataSource` refusing connections). `ShareChainIT`'s
    exhaustive switch gains the new result.
  - Gate round 1: `enriched_copy_failure_that_is_not_a_data_exception_should_throw_retryable_and_leave_nothing`
    pins that a non-class-22 failure of the enriched payload insert (a test-only trigger raising 57014)
    escapes as `RetryableIntakeException` at `store` with cause `statement_timeout`, never as
    `EnrichedCopyRefused`, and writes nothing (the trigger is shared with the 001 parsed-copy case through
    one helper). RED, taken against a mutant whose catch swallowed every failure: `share store >
    enriched_copy_failure_that_is_not_a_data_exception_should_throw_retryable_and_leave_nothing() FAILED`
    `Expecting code to raise a throwable.` GREEN with the code as built: `JdbcShareStoreIT` 26, 0 failures.

- [X] T006 [US1] [US2] [US3] [US4] [US5] [US6] [US7] [US8] Test first: `IntakeServiceTest` (extended, mocked ports, no Spring) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/IntakeServiceTest.java, `MicrometerIntakeObserverTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserverTest.java, `IntakeConfigTest` (extended: `IntakeService` built with and without the port) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfigTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeService.java (scan → existence check → lookups, timed and counted → enrich → extract on the enriched tree → store → one fallback re-run), src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeObserver.java (+ `applicationLookedUp`, `lookupTimed`, `enrichmentSkipped`, `enrichmentApplied`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserver.java (new meters, pre-registered), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java (enricher bean; `IntakeService` wiring with the optional port); confirm specs/002-enrichment/contracts/metrics.md matches what was built
  - Cases (`IntakeServiceTest`): no application needing a lookup → no existence check, no call, nothing counted; settled receipt → no scan, no call; stored share → no call, store gives `DUPLICATE`, `skipped{already_stored}`; enrichment off (port absent) → no call, `skipped{disabled}`; with `InOrder`: receipt commit, settled check, existence check, lookups one at a time in array order, extraction, store; extraction reads the enriched tree; the `StoreRequest` carries the arrived text, its checksum and size, the enriched parsed copy and the flag; a retryable lookup failure is counted once at `enrich` with its cause and rethrown with no store call; an unexpected runtime failure in the step counts `enrich`/`other` and is rethrown; an existence-check failure counts `store` with its database cause; each answered lookup reports its outcome and duration on the injected clock; `invalid_id` counted with no call and no timer; `enrichment.applied` only after `Stored` with `enrichmentApplied` true; `EnrichedCopyRefused` → exactly one re-run with the arrived copy and flag false, `skipped{unstorable_results}`, no `applied`; a second `EnrichedCopyRefused` is not re-run again (counted, thrown); single-exit shape. `MicrometerIntakeObserverTest`: `resultsstore.enrichment.applications{outcome}`, `resultsstore.enrichment.lookup{outcome}` (incl. `failed`, no `invalid_id`), `resultsstore.enrichment.skipped{reason}` and `resultsstore.enrichment.applied` pre-registered with every tag value; `intake.failed` accepts `stage=enrich` with each `progression_*` cause; the whole-registry guard (no tag outside the lists, none matching a UUID or date) still passes.
  - Covers: FR-001, FR-005, FR-006, FR-018 (counted from the stored flag), FR-019 (service half), FR-020, FR-027, FR-028, FR-029, FR-030, FR-031, FR-032; SC-002 (unit half), SC-007.
  - Done when: the three test classes green; the gate green.
  - RED (seams: `IntakeObserver` + the four methods, `MicrometerIntakeObserver` implementing them as no-ops
    and registering nothing new; `IntakeService` taking the enricher, the nullable port and a nanosecond clock
    but ignoring them, with `enrichesFromProgression()` answering `false`; `IntakeConfig` passing `null` for
    both). Each class run alone (`failFast`):
    `IntakeServiceTest` → `enrichment > enriched_counter_should_move_only_after_a_store_whose_stored_flag_is_true() FAILED`
    `Wanted but not invoked: observer.applicationLookedUp(ENRICHED);` (also
    `enrichment_off_should_make_no_check_no_call_and_count_disabled() FAILED` `Wanted but not invoked`, and the
    refusal case ended in the seam's `IllegalStateException`);
    `MicrometerIntakeObserverTest` → `every_meter_should_be_registered_at_start_with_exactly_its_tag_sets() FAILED`
    `Expecting actual: {…} to contain only following keys: […, "resultsstore.enrichment.applications", …]`, and
    `lookup_should_record_its_duration_under_its_outcome_or_failed() FAILED`
    `MeterNotFoundException: … No meter with name 'resultsstore.enrichment.lookup' was found.`;
    `IntakeConfigTest` → `enabled_subscription_and_enrichment_should_build_the_progression_client() FAILED`
    `to have a single bean of type: <…ApplicationResultsEnricher>`.
  - GREEN: `IntakeServiceTest` 36 (15 new, in `enrichment`), `MicrometerIntakeObserverTest` 26,
    `IntakeConfigTest` 8, 0 failures; `pmdMain pmdTest` clean. Full gate (`build pmdMain pmdTest
    jacocoTestReport`): 915 tests, 0 failures, 0 skipped; JaCoCo line 99.5 %, branch 98.7 %; exit 0.
  - Notes: the step is `enrich` → existence check (counted at `store`) → `lookUpAndEnrich` inside
    `counted(ENRICH, …)`, so a progression failure counts its own cause once and any other runtime failure
    in the step counts `enrich`/`other`. Each call is timed on the injected monotonic clock around
    `ProgressionApplications.find` (request, answer and the adapter's classification); a failed call records
    the timer's `failed` outcome before it is rethrown. `invalid_id` is counted once per skipped application
    when enrichment is on, before the existence check and whether or not the share is already stored
    (contracts/metrics.md: "when the scan skips the application"); with enrichment off nothing but
    `skipped{disabled}` is counted, and only when an application with a valid id needs results. After
    `EnrichedCopyRefused` the key details are read again from the arrived body for the re-run, so what is
    indexed matches the copy stored; a second refusal counts `store`/`other` and throws
    `IllegalStateException`. The lookup timer's outcome is passed as `Optional<ApplicationLookupOutcome>`
    (empty = `failed`), so no new enum was needed. contracts/metrics.md checked against what was built:
    matches; no edit needed.
  - Gate round 1: the fallback case now runs on the mocked extractor and pins, in order, the extraction
    of the enriched tree, the refused store, `skipped{unstorable_results}`, a second extraction of the
    arrived body (no `judicialResults` added) and the second store, whose `projection` is that second
    extraction's. RED, against a mutant that reused the first projection: `IntakeServiceTest > enrichment >
    enriched_copy_refused_should_be_stored_once_more_with_the_arrived_copy_and_counted() FAILED`
    `Wanted but not invoked`. Added `enrichment_off_with_only_invalid_ids_should_count_nothing_and_store_the_flag_false`
    (pins the existing branch: no `skipped`, no `invalid_id`, no existence check, flag false; green at once,
    no behaviour change). `enrichesFromProgression()` javadoc now says it exists for the wiring tests.

- [X] T007 [US1] [US2] [US3] [US4] [US5] [US6] [US7] [US8] Test first: `IntakeIT` (extended; static `support/ProgressionStub` started before the context; `@DynamicPropertySource` sets `resultsstore.enrichment.enabled=true`, the stub's base URL and a synthetic system user id) in src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/IntakeIT.java, `NoPayloadInLogsIT` (extended, same stub) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/NoPayloadInLogsIT.java; then any production fix the ITs find, in the files of T001–T006
  - Cases (`IntakeIT`, each test with its own application ids; stubs and `verify()` per id): an application missing results → stored enriched, `enrichment_applied` true, `payload_json` holds the results without the three fields, `payload_text` equals the published text and `payload_sha256` its checksum, one GET carrying the system user; `"judicialResults": []` → enriched the same way; two applications → two GETs in array order; `LISTED` → un-enriched, `not_finalised` counted; `FINALISED` with no results → un-enriched, `no_results`; `200 {}` → un-enriched, `payload_json` equal to the arrived payload, `not_found` counted; two applications, one enriched → flag true; 503 then 200 (a WireMock scenario scoped to one id) → nothing stored after the first delivery, receipt `RECEIVED`, then one share, enriched, receipt `STORED` with 2 attempts; 503 every time → dead-lettered, no share, receipt `RECEIVED`, `intake.failed{stage=enrich,cause=progression_unavailable}` moved; 404 → redelivered, nothing stored, `progression_rejected`; 403 → `progression_refused`; HTML 200 → `progression_malformed`; the same share under a new message id → one GET in total, second receipt `DUPLICATE`, `skipped{already_stored}` = 1; a share with no applications and one already resulted → 0 requests; a result holding `\u0000` → stored with the arrived copy, flag false, `skipped{unstorable_results}`. `NoPayloadInLogsIT`: a malformed body, an HTML 500 and a 403 body each carrying a marker string; no captured log line holds the marker or the system user id.
  - Covers: FR-038; US1–US8 end to end; SC-002, SC-003 (end-to-end rows), SC-004, SC-005, SC-006, SC-007, SC-008.
  - Done when: both ITs green; the dead-letter case runs on the test profile's no-pause setting and the embedded broker's low max-delivery count (no sleeps); the gate green.
  - RED (the extended `IntakeIT` with the stub registered by `@DynamicPropertySource` but the switch left
    at the test profile's `false`): `intake end to end > application_missing_results_should_be_stored_enriched_with_the_arrived_text_kept() FAILED`
    `Expecting map: {…, "enrichment_applied"=false, …} to contain entries: ["enrichment_applied"=true]`.
    With the switch on, every case passed with no production change: T001 to T006 hold end to end, so the
    task's "any production fix the ITs find" found none. `NoPayloadInLogsIT`'s new case was green at once:
    the client never logs a body or the user id (T002, ruling 3), and the client and the step log only ids.
  - GREEN: `IntakeIT` 23 (14 new: enriched; `[]` enriched; two applications in array order, one enriched is
    enough; `LISTED`, `FINALISED` with no results and `200 {}` stored as arrived and counted; 503 then 200;
    503 every time, 404, 403 and HTML 200 dead-lettered and counted; re-publish under a new message id; no
    application and one already resulted; a `\u0000` result), `NoPayloadInLogsIT` 3, 0 failures;
    `pmdMain pmdTest` clean. Full gate (`build pmdMain pmdTest jacocoTestReport`): 930 tests, 0 failures,
    0 skipped; JaCoCo line 99.5 %, branch 98.7 %; exit 0.
  - Notes: the stub (`support/ProgressionStub`, static, started with the broker before the context) is shared
    by the class; every stub, scenario and request check names its own application id. The 503-then-200
    scenario delays the second answer by 5 s so the state between the deliveries can be read (attempts 2,
    receipt `RECEIVED`, no share) before the share is stored once, enriched, with 2 attempts. Lookup order
    is read from WireMock's serve events filtered to the test's two paths. "0 requests" for a share with no
    application is shown by the lookup timer's total not moving (no id exists to ask the stub about); for
    the resulted application, by its own path. The fail-closed rows wait for the dead letter (3 deliveries,
    no pause in the test profile) and check one request per delivery and `intake.failed{stage=enrich}` moved
    by 3. `NoPayloadInLogsIT` runs with enrichment on: four progression answers carrying a marker (a
    malformed 200, an HTML 200, an HTML 500, a 403) are dead-lettered after 2 deliveries each, and no
    captured line holds the marker or the synthetic system user id; the 001 counts it asserts are not
    moved by these failures. `SampleShares.shareWithApplications` builds a share with given application
    elements.

---

## Phase C: sweep, smoke, documents (T008–T010)

**Purpose**: the sweep reads the working copy, the compose stack proves an enriched share end to end,
and the documents are reconciled. Depends on phase B (the store writes the working copy;
`payloadForExtraction` exists).

**Independent test**: `ExtractionSweepIT` re-extracts from `payload_json`; `scripts/container-smoke.sh`
gives `PASS` with the enriched case; `/speckit-analyze` reports no CRITICAL or HIGH finding.
  - Gate round 1: the 503-then-200 scenario's second answer now waits 5 s (was 1.5 s), so the state
    between deliveries is read with a wide margin on a slow runner and still well inside the 30 s bound and
    the 10 s read timeout. Test-only change; green.

- [X] T008 [P] [US1] Test first: `ExtractionSweepTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweepTest.java, `ExtractionSweepIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/ExtractionSweepIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweep.java (reads `payloadForExtraction`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareStore.java (`payloadText` removed; javadoc of `payloadForExtraction`), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java (`payloadText` removed)
  - Cases: `ExtractionSweepTest`: extracts from `payloadForExtraction`; a missing payload row still counts its own failure; a database failure reading the copy counts `error`. `ExtractionSweepIT`: a `FAILED` row whose `payload_json` carries a defendant the `payload_text` lacks (rows inserted directly, as the suite's fixtures do) is re-extracted from `payload_json` and indexes that defendant; a `FAILED` row with NULL `payload_json` is re-extracted from the text; the sweep makes no progression request (no `ProgressionApplications` bean in the sweep context, and a WireMock verify of 0 requests when one is present); `EXTRACTOR_VERSION` unchanged.
  - Covers: FR-033, FR-034.
  - Done when: both test classes green; the gate green.
  - RED (the three production files at phase C's base, 5c08ab3, where the sweep still reads
    `payloadText`; the tests as committed here; `failFast`, so case by case):
    `ExtractionSweepTest` → `readable_row_should_be_recorded_with_the_details_read_from_its_working_copy_and_counted_fixed() FAILED`
    `WantedButNotInvoked: store.payloadForExtraction(…)` (the sweep read `payloadText`, which the mock
    leaves unanswered, so the row became an `UNEXPECTED` failure; the round's other cases fail the same way);
    `ExtractionSweepIT` → `failed_row_should_be_re_extracted_from_its_working_copy_not_its_arrived_text() FAILED`
    `Expecting actual: [[c1c1…0001, d1d1…0001, e1e1…0001]] to contain exactly in any order: […0001], [c1c1…0001, d1d1…0002, e1e1…0002]] but could not find […0002]`
    (the defendant only the working copy names was not indexed). Already green on the base, as they pin
    behaviour that does not change: `row_with_no_parsed_copy_should_be_read_from_its_stored_text` (NULL
    `payload_json` falls back to the text: `COALESCE` of T005), `sweep_should_make_no_progression_request_and_keep_the_extractor_version`
    (no `ProgressionApplications` bean in the sweep's context, 0 requests on the stub, `EXTRACTOR_VERSION`
    1), `payload_read_that_fails_should_leave_the_projection_stamp_the_try_and_count_an_error` (now
    through `payloadForExtraction`).
  - GREEN: `ExtractionSweepTest` 19, `ExtractionSweepIT` 18, `JdbcShareStoreIT` 26, 0 failures.
    `payloadText` is gone from `ShareStore` and `JdbcShareStore`; the sweep reads `payloadForExtraction`
    (`COALESCE(payload_json::text, payload_text)`), so the T005 stop-gap duplication is removed.

- [X] T009 [P] [US1] [US2] Test first: extend scripts/container-smoke.sh so it fails on the pre-002 build: publish a share whose application lacks results (synthetic ids, the application id of quickstart §3); assert with `psql` `t|1|f|t` (`enrichment_applied`, number of results in `payload_json`, any amendment field present, `payload_sha256` equal to the SHA-256 of `payload_text`); `POST /__admin/requests/count` filtered by the progression path gives 1 with the smoke's `CJSCPPUID`; the 001 cases unchanged; then docker/wiremock/mappings/progression-application.json (quickstart §3), docker-compose.yml (app service: synthetic `RESULTS_STORE_SYSTEM_USER_ID`; `CP_BASE_URL` already `http://wiremock:8080`; `RESULTSSTORE_ENRICHMENT_ENABLED` left unset), and any fix the smoke finds
  - Covers: FR-039; SC-010.
  - Done when: `scripts/container-smoke.sh` prints `PASS` with every check `ok` (the RED run quoted: the new checks fail on the build without T001–T008's wiring or without the mapping); the gate green.
  - RED (2026-10-03, branch `002-enrichment-docs` at 30fcf3b + this task's script and mapping, i.e. phase A
    only; `flock -w 7200 /tmp/resultsstore-gradle.lock ./scripts/container-smoke.sh`, no wait on the lock).
    The share now carries two court applications: `a1a1a1a1-…-0001` with no `judicialResults` (stub:
    `FINALISED`, one result with the three amendment fields) and `a1a1a1a1-…-0002` with `[]` (stub:
    `200 {}`). Every 001 check passed; the new checks failed, exit 1:
    `ok: receipts, one of each` … `ok: one day row naming the share as latest, one share, youth seen`
    (all eight 001 rows), `ok: enrichment: the arrived text unchanged and its checksum the published text's`,
    `ok: metric` for the four 001 lines;
    `FAIL: enrichment: flag, one result added, no amendment field, other fields kept, not-found left as it arrived: expected 'true|1|false|b1b1b1b1-0000-4000-8000-000000000001|Synthetic result|0', found ''`
    (no `judicialResults` in the working copy, so the concatenation is NULL);
    `FAIL: progression asked once for the enriched application: expected 1 request(s), found 0`, the same
    for the not-found application, and `FAIL: progression asked twice in all: expected 2 request(s), found 0`
    (counts filtered by `urlPathPattern`, `Accept` and the compose `CJSCPPUID`, not global);
    `FAIL: metric line missing:` `resultsstore_enrichment_applied_total 1.0`,
    `resultsstore_enrichment_applications_total{outcome="enriched"} 1.0`, `{outcome="not_found"} 1.0`,
    `resultsstore_enrichment_skipped_total{reason="already_stored"} 1.0`;
    `FAIL: 8 intake check(s) failed`; teardown clean.
    The mapping itself was checked in a throwaway WireMock 3.13.2: the first id answers 200 with the
    `FINALISED` body, the second `200 {}`, a request without the headers 404.
    GREEN (2026-10-03, branch `002-enrichment` at a6ef7ca, i.e. T001–T008 with the script and mapping of
    f86e8ff unchanged; `flock -w 7200 /tmp/resultsstore-gradle.lock ./scripts/container-smoke.sh`, Docker
    29.7.2), exit 0: `PASS: readiness reported UP within the 60s budget`; the eight 001 checks `ok`;
    `ok: enrichment: flag, one result added, no amendment field, other fields kept, not-found left as it arrived`;
    `ok: enrichment: the arrived text unchanged and its checksum the published text's`;
    `ok: progression asked once for the enriched application`, `… once for the not-found application`,
    `… twice in all`; `ok: metric` for the four 001 lines and for
    `resultsstore_enrichment_applied_total 1.0`, `resultsstore_enrichment_applications_total{outcome="enriched"} 1.0`,
    `{outcome="not_found"} 1.0`, `resultsstore_enrichment_skipped_total{reason="already_stored"} 1.0`;
    `PASS: intake stored the share enriched, dropped its duplicate and recorded the unreadable message`;
    teardown clean. No fix to the script or the code was needed.

- [X] T010 Documents, analysis and Deferred list: test first, the review grep for `exactly as received`, `parsed copy`, `unread in 001`, `never the parsed copy`, `can be dropped`, `same way the validation`, `always false in 001` across specs/, .specify/memory/constitution.md, .claude/agents/code-reviewer.md and specs/002-enrichment/page-notes.md (RED: the hits it lists before the edits); then
  - .specify/memory/constitution.md: Principle II reworded to research R25's text; version 2.0.0 → 2.1.0 (MINOR: the working-copy clause added, the amendment-field removal named, no rule reversed); Sync Impact Report at the top updated (modified principle, templates checked, follow-ups); **Last Amended** date set;
  - specs/001-share-intake/spec.md: FR-015, FR-016, FR-036 and Key Entities (*Payload*, *Share*) brought in line with spec 002 "Changes to spec 001", each with a "Changed by 002 (see specs/002-enrichment/spec.md, *Changes to spec 001*)" cross-reference; FR-019 marked still deferred;
  - specs/001-share-intake/data-model.md and specs/001-share-intake/contracts/metrics.md, contracts/configuration.md, contracts/schema.md: the 002 deltas folded in, or a pointer to the 002 contract where the 001 text is kept as history;
  - .claude/agents/code-reviewer.md:23 (*Payload altered*): the rule binds `payload_text` (exact text received, checksum over it); `payload_json` is the working copy, enriched at intake, without the three amendment fields; a re-serialised tree in place of `payload_text` is still a finding;
  - specs/002-enrichment/page-notes.md (new): forward notes for the page owner, same meaning as Principle II 2.1.0, for the design page's *Data model* bullet 2, the `hearing_share_payload` row and the read API's payload row (FR-037), with the spec-003 constraint of FR-041 (serve `payload_json`, `ETag` over the exact bytes served, `payload_text` when `payload_json` is NULL). The page itself is not edited;
  - specs/002-enrichment/plan.md: the constitution reference updated to 2.1.0;
  - `/speckit-analyze` (`.claude/skills/speckit-analyze/SKILL.md`, read-only) over spec.md, plan.md and tasks.md, with the constitution, research, data-model and contracts as context; every CRITICAL and HIGH finding resolved, MEDIUM fixed or listed below with a reason; the result recorded under this task;
  - the Deferred list below, completed with anything the phases left open.
  - Covers: FR-035, FR-036, FR-037, FR-040, FR-041; SC-011.
  - Progress (2026-10-03): docs part done on `002-enrichment-docs`: constitution 2.1.0 (Principle II
    working copy, Principle VI widened), "Amended by spec 002" notes in spec 001 (FR-015, FR-016,
    FR-036, Key Entities), `.claude/agents/` and `.claude/rules/` aligned with the working-copy role,
    page-notes.md, and the metrics and configuration contracts checked against phase A's code.
    Not yet done: spec 001 data-model and contracts deltas, the plan.md constitution reference,
    `/speckit-analyze` and the final tick, after phase C.
  - RED (review grep, `git grep -n -i` of the seven phrases over specs/, the constitution and
    `.claude/agents/code-reviewer.md` at f86e8ff, before the docs commit): 49 hits, among them spec 001
    FR-015 ("a parsed copy"), FR-036 ("never the parsed copy"), *Payload* ("a parsed copy that may be
    empty") with no 002 note, and the code reviewer's *Payload altered* rule on the text alone.
  - Remainder (phase C, 2026-10-03): spec.md *Assumptions* and research R11 and R15 now describe the
    transport as built (Apache HttpClient 5 through `HttpComponentsClientHttpRequestFactory`, subclassed
    as `NoRedirectRequestFactory`; retries and redirects off; a fresh connection per lookup; one absolute
    deadline per lookup; abort on any status other than 200), the first draft kept as history; plan.md
    names constitution 2.1.0; spec 001 `data-model.md` and `contracts/schema.md` carry a one-line
    *Amended by spec 002* delta for `payload_json`'s role; contracts/metrics.md gains *As built*:
    `lookupTimed(Optional)` (empty = a call ending in a `progression_*` cause, timer outcome `failed`)
    and the `invalid_id` counting rule (enrichment on only, before the existence check, never timed);
    spec.md status set to Implemented.
  - GREEN (review grep at the docs commit): every remaining hit is reworded, carries an *Amended by
    spec 002* note (spec 001 FR-015, FR-016, FR-036, Key Entities; 001 data-model and schema contract
    headers covering the V3 DDL comments, kept stale on purpose), is 001 history (001 plan, research,
    tasks, metrics contract), is the page's current wording quoted in page-notes.md, or uses "parsed
    copy" as the 002 documents' name for the `payload_json` column.
  - `/speckit-analyze` (hooks `before_analyze` / `after_analyze` disabled in `.specify/extensions.yml`):
    41 FRs and 11 SCs, each covered by at least one task and named in plan.md; every task maps to a
    story or to FR-035 to FR-041; no TODO, TBD or unresolved marker; no constitution conflict against
    2.1.0. CRITICAL 0, HIGH 0. MEDIUM 3, all fixed here: (I1) spec.md *Assumptions* and research
    R11/R15 contradicted the transport and deadline as built; (I2) plan.md cited constitution 2.0.0;
    (U1) FR-028 did not say whether `invalid_id` counts with enrichment off or for a share already
    stored (stated in contracts/metrics.md *As built*). LOW 2: spec.md status still Draft (fixed); the
    tasks.md header names constitution 2.0.0 as the phase-A baseline (kept: accurate history).
    `check-prerequisites.sh --require-tasks --include-tasks --json` exits 0.
  - Gate (`flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew clean build pmdMain pmdTest
    jacocoTestReport jacocoTestCoverageVerification`, JDK 25): exit 0; 934 tests, 0 failures, 0
    skipped; JaCoCo line 99.6 %, branch 98.9 %. No wildcard import under src/; the attribution wording
    grep finds policy text only.
  - Done when: the review grep shows no hit that is not reworded or marked historical; `/speckit-analyze` reports no CRITICAL or HIGH finding; `.specify/scripts/bash/check-prerequisites.sh --require-tasks --include-tasks --json` succeeds; the gate green.
  - Deferred (not in 002):
    - indexing defendants who appear only as court-application parties (spec 001 FR-019): still deferred, no change to the defendant index;
    - re-enriching shares stored before 002: never done; a backfill would need a migration relaxing `hearing_share_guard` and the payload guard (data-model.md, *Rows stored before 002*);
    - deploy values in `cpp-aks-deploy` (`ansible/group_vars/resultsstore-service_values.yaml.j2` per environment with `CP_BASE_URL`, the Artemis and database bindings, and the `secretProvider` entry for Key Vault `RESULTS-STORE-SYSTEM-USER-ID`): a separate task; it blocks STE, not 002;
    - the push alternative: subscribing to `public.progression.hearing-resulted-application-updated` instead of the query (research R1); it would need a second subscription and a join with the share, and the page names the query;
    - the future self-derivation option: deriving application results from the store's own history (research R1); only once that history is complete and Principle IV is amended to allow it;
    - the V3 inline comments on `payload_json` and `enrichment_applied` stay stale on purpose (data-model.md; no `COMMENT ON` migration for wording alone);
    - the *Progression lookups failing* alert rules themselves (contracts/metrics.md, *Alert input*);
    - review note: `NoRedirectRequestFactory` hands the `ProgressionExchange` it creates to the Spring
      request built around it through a `ThreadLocal` (`CREATED`). It is correct while the request is
      created and wrapped on one thread, as `RestClient` does today; a reviewer may prefer a hand-off
      that does not rely on that;
    - renaming `NoRedirectRequestFactory`: it now also turns off retries, sets the deadline and makes a
      fresh connection per lookup, so a name such as `ProgressionRequestFactory` would fit better; a
      rename only, left for a later change.

---

## Dependencies & Execution Order

### Phase dependencies

- Before phase A (done) → Phase A → Phase B → Phase C. Each phase starts only after the previous
  phase-gate run has ended at PASS.
- Phase B needs from phase A: `ProgressionApplications`, `ApplicationAnswer`, the `ENRICH` stage and
  the six causes (T001, T002), the outcome and skip enums (T001), the properties and the conditional
  bean (T001, T003), `support/ProgressionStub` (T002).
- Phase C needs from phase B: the working copy in `payload_json` and `payloadForExtraction` (T005),
  the full intake path with enrichment (T006, T007).

### Within phases

- Phase A: T001 → T002 (uses the causes and stage of T001) → T003 (builds the client of T002 from the
  properties of T001).
- Phase B: T004 and T005 touch disjoint files and may run in either order; T006 needs both (the
  enricher of T004; the store shapes of T005, and replaces T005's compile-only `IntakeService` change);
  T007 needs T006.
- Phase C: T008 and T009 touch disjoint files and may run in either order; T010 last (it records the
  analysis of the finished range).

### User story → tasks

| Story | Tasks | Independently proven by |
|---|---|---|
| US1 A share whose application lacks results is stored enriched (P1, MVP) | T002, T003, T004, T005, T006, T007, T008, T009 | `ApplicationResultsParityTest`; `IntakeIT` enriched cases; smoke `t\|1\|f\|t` |
| US2 No lookup when none is needed (P2) | T001, T003, T004, T006, T007, T009 | `IntakeServiceTest` no-call cases; `IntakeIT` 0 requests |
| US3 Progression has nothing to add (P3) | T002, T004, T006, T007 | `ApplicationResultsEnricherTest` outcomes; `IntakeIT` `LISTED`, no results, `200 {}` |
| US4 Progression is unavailable (P4) | T001, T002, T006, T007 | `ProgressionApplicationClientTest` 5xx/timeout/unreachable rows; `IntakeIT` 503-then-200 and dead-letter |
| US5 A misroute or refusal fails closed (P5) | T001, T002, T006, T007 | client 404/3xx/403/malformed rows; `IntakeIT` 404, 403, HTML |
| US6 A share already stored makes no lookups (P6) | T005, T006, T007 | `JdbcShareStoreIT` `storedShareId`; `IntakeIT` re-publish under a new message id |
| US7 Results the database cannot hold (P7) | T005, T006, T007 | `JdbcShareStoreIT` `EnrichedCopyRefused`; `IntakeIT` `\u0000` result |
| US8 Operators can see enrichment outcomes (P8) | T001, T002, T006, T007 | `MicrometerIntakeObserverTest`; `NoPayloadInLogsIT` |

## Parallel examples

```text
Phase B: "T004 ApplicationResultsEnricherTest / ApplicationResultsParityTest / ShareIdentityParserTest, then the enricher"
         "T005 JdbcShareStoreIT, then StoreRequest / StoreResult / ShareStore / JdbcShareStore"
Phase C: "T008 ExtractionSweepTest / ExtractionSweepIT, then ExtractionSweep reads payloadForExtraction"
         "T009 container-smoke.sh enriched case, then the WireMock mapping and compose variable"
```

Phase A has no parallel tasks: each task uses the one before it. Within a phase-gate run there is
one implementer, so `[P]` marks independence (the order is free), not concurrent agents in one tree.

## Implementation strategy

1. Phases A and B deliver the MVP: US1 end to end in `IntakeIT`, with US2–US8 proven alongside it
   (the step is one seam; the stories are its branches).
2. Phase C makes the sweep read the working copy, proves the enriched share in the compose stack, and
   reconciles the constitution, spec 001, the reviewer rule and the page notes.
3. After phase C: the final gate, then delivery as the orchestration plan's Step 3 says.

## Notes

- Ticks (`[X]`) and the RED / GREEN lines are written by the implementer in the commit that completes
  the task.
- A task's commit message follows Conventional Commits and names the task id.
- Where a task finds the design documents silent, the implementer takes the option that changes the
  least behaviour and records it under the task.
