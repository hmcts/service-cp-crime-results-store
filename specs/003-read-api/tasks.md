---

description: "Task list for feature 003: read API"
---

# Tasks: Read API

**Input**: Design documents from `/specs/003-read-api/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R23), data-model.md, contracts/ (read-api, metrics,
configuration, schema), quickstart.md; constitution 2.1.0 (2.2.0 at the end of T012)

**Tests**: Mandatory (Principle X). Each task names its test classes and cases first, then the
production files. The implementer writes the tests, runs them, records the RED run under the task (a
failing assertion, never a compile error: land compile-safe seams first), then writes the minimum
production code and records the GREEN run. One commit per task, the test at or before the production
code. A task is ticked (`[X]`) only on a green full-suite run, in the commit that completes it.

**Organisation**: four phases (A, B, C, D), exactly as plan.md *Phase plan* and the orchestrator's ruling
B11. Each phase is one run of the repository's phase-gate workflow over a contiguous task range, and each
phase's range must be green on its own. Phase D builds the arrived-text endpoint (D-RAW accepted, E2)
after phases A to C. The user stories
cut across the phases (the API is built layer by layer: contract and edge, then data and service, then
serving), so every task carries the tags of the stories it serves; the story-to-task map is under
*Dependencies*.

**Decisions**: every task applies spec.md *Decisions taken with Sachin (2026-10-03)* (E1–E13); nothing
is pending. The tasks they shape say so: T004 and T006 (E5 exact court, E6 search ranges and index), T005
and T007 (E6, E8), T008 (E3 timeouts and pool backstop, E4 overrun), T010 and T011 (E1 audit marker, E8
no `_metadata`), T012 (E1, E2, E8, E13 constitution wording; spec 001 and 002 notes), T013 (E2 with E8).

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
| A | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/003-read-api", "tasks": ["T001","T002","T003"], "baseCommit": "<HEAD at phase A start>", "codex": true, "maxRemediations": 1}` |
| B | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/003-read-api", "tasks": ["T004","T005","T006","T007","T008"], "baseCommit": "<HEAD at phase B start>", "codex": true, "maxRemediations": 1}` |
| C | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/003-read-api", "tasks": ["T009","T010","T011","T012"], "baseCommit": "<HEAD at phase C start>", "codex": true, "maxRemediations": 1}` |
| D (D-RAW accepted) | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/003-read-api", "tasks": ["T013"], "baseCommit": "<HEAD at phase D start>", "codex": true, "maxRemediations": 1}` |

## Gate used by every task's "Done when"

`flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew build pmdMain pmdTest jacocoTestReport`
exits 0 (JDK 25). `build` runs `check`, which includes `jacocoTestCoverageVerification` (line 0.88,
branch 0.85; `config/**` excluded). Written below as **the gate**.

## Rules for every task

- A unit test per production class; an IT on Testcontainers Postgres (`support/PostgresTestSupport`),
  the embedded Artemis broker (`support/EmbeddedBrokerSupport`) or in-process WireMock for every
  persistence, messaging and HTTP path.
- Latches or Awaitility, never `Thread.sleep` (`failFast` is on).
- No payload, message text or problem body content beyond the four fields in assertion messages or log
  output; ids only. Payload assertions compare hashes or booleans, so a failure prints no content.
- WireMock stubs and `verify()` match the exact path and the `CJSCPPUID` header; no global counts, no
  `resetAll()` on a shared server.
- Explicit imports only; constructor injection; the generated models of the contract jar for response bodies (research R23), records for any other value; PMD clean. **`OnlyOneReturn`**:
  every filter refusal, early `304`, cursor decoder branch, `fromValue` and reason look-up uses
  single-exit style or a site suppression with a reason (research R22).
- Migrations V1 to V4 are never edited. 003 adds V5 only.
- No SQL is built from input; every query is a fixed constant with bound parameters.

## Wiring notes

Each phase must be green on its own, so the beans arrive in this order:

- **T001 adds the rules before any filter derives their actions.** `AuthzIT` still calls the unmapped
  `/results-store/v1/anything`; the library resolves `"GET /results-store/v1/anything"`, which no rule
  names, so its `403` and `401` cases stay green until T003 rewrites it.
- **T002 takes `@Component` and `@Order` off `ActionHeaderFilter`.** Its constructor now needs a
  `RefusalObserver`, which has no bean yet, so the filter is not registered until T003. The tests build
  it directly with a recording observer.
- **T003 registers the filters** (`ApiWebConfig`) with `MicrometerRefusalObserver`, and moves `AuthzIT`
  and `ActuatorIntegrationTest` onto `PostgresTestSupport` now, so phase C's unconditional read beans
  break no context. The `application-test.yaml` header comment is corrected in the same task.
- **T005 and T006 build no bean.** `MicrometerReadObserver` and `JdbcShareQueries` are constructed in
  their tests; nothing injects them until T009.
- **T008 changes `StoreResult.Stored`** (+ `insertToCommit`). Every call site and exhaustive switch
  (`ShareChainIT`, `IntakeServiceTest`, `JdbcShareStoreIT`, …) is updated in the same commit.
  `IntakeConfig` passes the threshold derived from `IntakeProperties` (transaction + 2 × statement +
  idle-in-transaction; 90 s at the new defaults). T009 replaces it with the effective lag.
- **T008 also lowers two intake defaults** (statement 20 s → 10 s, lock 10 s → 5 s) and adds the pool
  backstop (`StatementTimeoutBackstop`). Every existing test that names the old defaults (`20s` statement, `10s`
  lock, or the lag derived from them) is updated in the same commit; `StoreTimeoutIT` keeps its own short values.
- **T009 wires the read beans unconditionally** (`ReadApiConfig`). The `ApplicationContextRunner` tests
  that load it get a stub `DataSource` bean (a `DriverManagerDataSource` with an unused URL: building a
  `JdbcTemplate` opens no connection).
- **T009 takes the contract jar** (`apiSpec`, research R23) before any class uses it, so T010 compiles
  against `SharesApi` and the generated models from its first commit. The jar is on the classpath from
  T009; nothing implements `SharesApi` until T010, so no mapping is registered early.
- **T010 adds one component-scanned controller** (`SharesController implements SharesApi`; three classes
  implementing the interface would register its four mappings three times). Every context stays green
  because T009's beans exist. The `@WebMvcTest` slices mock `ShareReadService` and `ReadObserver` with
  `@MockitoBean` and run with `@AutoConfigureMockMvc(addFilters = false)`; if a slice pulls in
  `ApiWebConfig`, its dependencies are mocked the same way.
- **Authorisation and audit are switched on per test class.** `FilterOrderIT` and `AuthzIT` (T003),
  `ReadApiIT` and `AuditIT` (T011) switch them on for themselves; the `test` profile keeps them off.

---

## Before phase A (done, no task id)

- Branch `003-read-api` from main `c21a901`; `.specify/feature.json` points at `specs/003-read-api`.
- spec.md (Draft), plan.md, research.md, data-model.md, contracts/, quickstart.md and page-notes.md
  written. The decisions taken with Sachin on 2026-10-03 (rulings section E) are applied to every 003
  document and listed in spec.md *Decisions taken with Sachin (2026-10-03)*.

---

## Phase A: contract and edge (T001–T003)

**Purpose**: the OpenAPI paths, the allow rules and the route table first (constitution, *Development
Workflow*: the contract before the code that serves it); then the action filter that derives every
action from method and path; then the `415` guard, the bounded `/error` page and the filter
registrations. Nothing serves a share yet. Blocks phase B.

**Independent test**: `ResultsStoreRulesTest`, `OpenApiDocumentTest` and `ApiRouteTest` prove the
contract; `ActionHeaderFilterTest` proves derivation and refusals; `FilterOrderIT` and `AuthzIT` prove
the order and the bounded `401`/`403`/`404` bodies in a running context.

- [X] T001 [US5] Test first: `ResultsStoreRulesTest` (rewritten) in src/test/java/uk/gov/hmcts/cp/resultsstore/acl/ResultsStoreRulesTest.java, `OpenApiDocumentTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiDocumentTest.java, `ApiRouteTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRouteTest.java, `ReadEndpointTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ReadEndpointTest.java, `RouteRefusalTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/RouteRefusalTest.java; then src/main/resources/results-store-openapi.yaml (the four path templates as full paths, `/results-store/v1/shares` documented as two modes with both parameter sets, search with its two range forms (`sharedDayFrom`/`sharedDayTo` or `sharedFrom`/`sharedTo`, E6); the payload described as the working copy without `_metadata` (E8); every path parameter declared; schemas `ShareSummary`, `KeyDetails`, `PullPage`, `SearchPage`, `DayVersions`, `ProblemDetail`; the payload's response headers; `info.version` 0.2.0), src/main/resources/acl/results-store-rules.drl (five allow rules, research R3), src/main/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRoute.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReadEndpoint.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/RouteRefusal.java
  - Cases (`ResultsStoreRulesTest`, KIE as today, parameterised over `ApiRoute`): `every_read_action_should_be_allowed_for_system_users`; `every_read_action_should_be_allowed_for_second_line_support`; `every_read_action_should_be_refused_for_a_caller_in_neither_group` ("Other Group" and no group); `the_right_action_name_with_another_method_should_be_refused` (`POST`); `the_right_action_name_with_another_routes_path_should_be_refused`; `an_unknown_action_should_be_refused`; `there_should_be_exactly_one_rule_per_action`; `every_rule_should_name_exactly_the_two_groups` (no rule admits everything). `OpenApiDocumentTest`: `the_document_should_parse`; `the_paths_should_be_exactly_the_api_route_templates`; `every_templated_path_should_declare_its_path_parameters`; `every_operation_should_declare_the_problem_body_for_4xx_and_5xx`; `the_payload_operation_should_declare_the_etag_and_results_store_headers`; `the_audit_glob_should_resolve_exactly_one_document` (the suffix glob of `audit.http.openapi-rest-spec`). `ApiRouteTest`: `each_template_should_match_its_sample_path_and_no_other_route`; `pull_and_search_should_be_told_apart_by_stored_after_seq`; `a_trailing_slash_semicolon_parameter_double_slash_or_upper_case_path_should_match_nothing` (each form Spring MVC would not route); `allowed_methods_should_be_get_for_a_known_path_and_empty_for_an_unknown_one`; `every_action_should_be_kebab_verb_noun_with_the_results_store_prefix`. `ReadEndpointTest`, `RouteRefusalTest`: `every_tag_should_come_from_the_fixed_list` (contracts/metrics.md).
  - Notes: the attribute match in each rule (method and path) is written in the form the library's `uk.gov.moj.cpp.authz.drools.Action` class supports; read it from the jar (`javap`) and record the form under this task. The path attribute is matched against the route's template as a regular expression (one UUID or date segment per variable). `ApiRoute` holds the four templates and five actions (pull and search share one template); `arrived` is added only by T013.
  - Covers: FR-001, FR-046, FR-049, FR-053 (document half); contracts/read-api.md §1.
  - Done when: the five test classes green; the gate green.
  - Attribute form (from `javap` of `cp-auth-rules-filter-1.0.7.jar`): `uk.gov.moj.cpp.authz.drools.Action` is
    `record Action(String name, Map<String, Object> attributes)` with `getName()`/`getAttributes()`;
    `HttpAuthzFilter.doFilter` inserts `new Action(<resolved name>, {"method": request.getMethod(), "path":
    UrlPathHelper.getPathWithinApplication(request)})`. The rules therefore read
    `Action(name == "<action>", attributes["method"] == "GET", attributes["path"] matches "<regex>")`, the
    regex being the template with one path segment (`[^/]+`) per variable, matched as a whole string. The
    variable's own form (canonical UUID, date) is left to the endpoint, so a malformed id still gets the
    contract's `400 invalid_share_id` / `invalid_hearing_id` / `invalid_hearing_day` rather than a `403`.
  - Also: `io.swagger.parser.v3:swagger-parser:2.1.20` added as a test dependency (the audit starter already
    puts that version on the runtime classpath) so `OpenApiDocumentTest` parses the document as the audit
    library does; `support/ApiRouteSamples` holds one sample path per route for the route, rule and filter
    tests. `ApiRoute` refuses any path whose raw segment holds `;` (Spring MVC would strip `;params` and
    route it; the store does not serve such paths at all).
  - RED (seams: `ReadEndpoint`/`RouteRefusal` with `tag()` returning `name()`; `ApiRoute` with its five
    constants and data but `method()` empty, `matches` false, `allowedMethods` empty, `resolve` empty; the
    DRL and OpenAPI document as at `538ff04`). The suite runs with `failFast`, so each class was run alone:
    `ReadEndpointTest` → `every_tag_should_come_from_the_fixed_list() FAILED` `Expecting actual: ["PULL",
    "SEARCH", "SHARE", "PAYLOAD", "DAY_VERSIONS"] to contain exactly (and in same order): ["pull", "search",
    "share", "payload", "day_versions"]`; `RouteRefusalTest` → `every_tag_should_come_from_the_fixed_list()
    FAILED` (upper-case names); `ApiRouteTest` → `each_template_should_match_its_sample_path_and_no_other_route
    (ApiRoute) > [1] route = PULL_SHARES FAILED` `Expecting Optional to contain: PULL_SHARES but was empty.`;
    `ResultsStoreRulesTest` → `there_should_be_exactly_one_rule_per_action() FAILED` `expected: 5L but was:
    0L`; `OpenApiDocumentTest` → `the_document_should_parse() FAILED` `expected: "0.2.0" but was: "0.1.0"`,
    `the_paths_should_be_exactly_the_api_route_templates() FAILED`,
    `every_operation_should_declare_the_problem_body_for_4xx_and_5xx() FAILED` `Expecting actual not to be
    empty`.
  - GREEN: `ResultsStoreRulesTest` 33, `OpenApiDocumentTest` 6, `ApiRouteTest` 28, `ReadEndpointTest` 1,
    `RouteRefusalTest` 1, 0 failures; the gate green (1002 tests passed, 0 skipped; coverage line 0.9956,
    branch 0.9950).
  - Gate round 1: `ApiRouteTest.every_route_should_name_its_endpoint_tag` added (the one uncovered line,
    `ApiRoute.endpoint()`). RED with `GET_SHARE` swapped to `ReadEndpoint.PAYLOAD`: `every_route_should_name_
    its_endpoint_tag() FAILED` `Expecting actual: … and others were not expected`; GREEN with the constant
    restored (`ApiRouteTest` 29).

- [X] T002 [US5] Test first: `ActionHeaderFilterTest` (rewritten) in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/ActionHeaderFilterTest.java, `ActionRequestWrapperTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/ActionRequestWrapperTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/filters/ActionHeaderFilter.java (rewritten over `ApiRoute`; `@Component` and `@Order` removed; sets the matched `ApiRoute` as a request attribute for the metrics interceptor of T010), src/main/java/uk/gov/hmcts/cp/resultsstore/filters/ActionRequestWrapper.java, src/main/java/uk/gov/hmcts/cp/resultsstore/filters/RefusalWriter.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ProblemReason.java (every reason of contracts/read-api.md §6 with its status), src/main/java/uk/gov/hmcts/cp/resultsstore/application/RefusalObserver.java (port: `refused(RouteRefusal)`)
  - Cases (`ActionHeaderFilterTest`, parameterised over route × {caller `CPP-ACTION`, vendor `Content-Type`, vendor `Accept`, `Accept` list with one vendor entry}): `every_route_should_carry_its_derived_action_whatever_the_caller_sent`; `every_route_should_answer_application_json_for_a_vendor_content_type_or_accept`; `pull_and_search_should_derive_different_actions`; `stored_after_seq_should_be_looked_up_only_after_the_method_and_path_match` (a `POST` to an unknown path never calls `getParameter`); `an_unmapped_path_should_be_refused_404_route_not_found_without_calling_the_chain`; `a_known_path_with_another_method_including_head_and_options_should_be_refused_405_with_allow_get`; `actuator_and_error_should_pass_with_cpp_action_removed_and_media_types_untouched`; `the_refusal_body_should_be_the_four_fields_and_never_contain_the_requested_path`; `every_refusal_should_be_counted_once_with_its_reason` (recording `RefusalObserver`). `ActionRequestWrapperTest`: `get_header_get_headers_and_get_header_names_should_agree_on_the_action`; `header_names_should_be_case_insensitive`; `a_non_vendor_accept_should_be_left_as_sent`.
  - Notes: single exit in `doFilterInternal` (PMD `OnlyOneReturn`). The vendor pattern is research R2's.
  - Covers: FR-046 (filter half), FR-047, FR-048 (filter half), FR-042 (filter bodies); SC-005 (unit half).
  - Done when: both test classes green; `AuthzIT` still green (wiring notes); the gate green.
  - Built: `ActionHeaderFilter` matches `RequestPath.parse(requestURI, contextPath).pathWithinApplication()`
    through `ApiRoute` and is no longer a `@Component` (not registered until T003); `ActionRequestWrapper`
    (`forRoute` / `withoutAction`) uses the library's own vendor pattern; `RefusalWriter` sets the status,
    `application/problem+json` and `Content-Length` and writes the four fields from `ProblemReason` (title =
    Spring's reason phrase), never via `sendError`; `ProblemReason` holds the 31 reasons of §6 with their
    statuses (pinned by `ProblemReasonTest` in T003). Test fixture `support/RecordingRefusalObserver`.
  - RED (seams: `RefusalObserver` as specified; `ProblemReason` table; `ActionHeaderFilter(RefusalObserver)`
    passing every request on unchanged; `ActionRequestWrapper` factories returning a bare
    `HttpServletRequestWrapper`; `RefusalWriter.write` empty). Run per class, then per method (`failFast`):
    `ActionHeaderFilterTest` → `every_route_should_answer_application_json_for_a_vendor_content_type_or_accept
    (ApiRoute, Attempt) > [2] route = PULL_SHARES, attempt = VENDOR_CONTENT_TYPE FAILED` `Expecting value to be
    false but was true` (the library still resolved a vendor action);
    `every_route_should_carry_its_derived_action_whatever_the_caller_sent(ApiRoute, Attempt) > [1] route =
    PULL_SHARES, attempt = CALLER_ACTION FAILED` `expected: "results-store.pull-shares" but was:
    "results-store.get-share-payload"`; `an_unmapped_path_should_be_refused_404_route_not_found_without_calling_
    the_chain(String) > [1] path = "/results-store/v1/anything" FAILED` `expected: null but was:
    MockHttpServletRequest@…`; `a_known_path_with_another_method_including_head_and_options_should_be_refused_
    405_with_allow_get(ApiRoute, String) > [1] route = PULL_SHARES, method = "HEAD" FAILED` (chain called);
    `every_refusal_should_be_counted_once_with_its_reason() FAILED` `Expecting actual: [] to contain exactly
    …`; `ActionRequestWrapperTest` → `get_header_get_headers_and_get_header_names_should_agree_on_the_action()
    FAILED` `expected: "results-store.search-shares" but was: "caller.supplied"`.
  - GREEN: `ActionHeaderFilterTest` 92, `ActionRequestWrapperTest` 3, 0 failures; `AuthzIT` 3 green (the
    filter is unregistered, so its caller-sent action still finds no rule); the gate green (1095 tests
    passed, 0 skipped; coverage line 0.9959, branch 0.9953).
  - Close-out (orchestrator ruling 1, pull vs search): the filter no longer asks the container for a parameter.
    Pull and search are told apart by `QueryParameterNames.contains(request.getQueryString(), "storedAfterSeq")`
    (new, `filters/`): pairs split on `&`, the name before the first `=`, decoded as the container decodes a
    query (UTF-8 escapes, `+` as a space); a malformed escape matches nothing. No `getParameter*`,
    `getParts`/`getPart`, `getInputStream` or `getReader` is called before authorisation. Tests set the query
    string as a container would (`setQueryString`) beside `setParameter`. RED (seam: `contains` answering
    `false`; filter still on `getParameter`): `QueryParameterNamesTest.a_pair_whose_name_decodes_to_the_name_
    should_be_found(String) > [1] rawQuery = "storedAfterSeq=0" FAILED` `Expecting value to be true but was
    false`; `ActionHeaderFilterTest.a_multipart_get_on_shares_should_derive_its_action_without_reading_
    parameters_or_body(String) > [1] query = "storedAfterSeq=0|results-store.pull-shares" FAILED` `expected:
    "results-store.pull-shares" but was: "results-store.search-shares"` (a spied multipart request whose body
    names `storedAfterSeq`; the case also verifies none of the reads above is ever made).
    `AuthzIT.a_multipart_get_on_shares_without_an_identity_should_be_401_and_never_reach_usersgroups` (a
    multipart `GET` with a body, no `CJSCPPUID`: bounded `401`, no usersgroups request without the header)
    passed against the old filter too: Tomcat parses a body only for `POST`, so it pins the running chain.
    GREEN: `QueryParameterNamesTest` 25, `ActionHeaderFilterTest` 96, `ActionRequestWrapperTest` 3, `AuthzIT`
    20, 0 failures.
  - Gate round 1 remediation (codex MEDIUM, dot segments): the pass-through for `/actuator/**` and `/error`
    was decided on the raw URI, which Tomcat maps by its normalised form, so `/actuator/../results-store/v1/
    shares` passed with no action instead of `404 route_not_found`. The filter now refuses a path with any `.`
    or `..` segment (decoded, `;parameters` stripped: `PathSegment.valueToMatch()`) `404 route_not_found`,
    counted, before anything else is decided. RED: `ActionHeaderFilterTest.a_path_with_a_dot_segment_should_be_
    refused_404_route_not_found_and_counted(String) > [1] path = "/actuator/../results-store/v1/shares" FAILED`
    `expected: null but was: …ActionRequestWrapper@…` (also `%2e%2e`, `%2E%2E`, `..;x=1`, `/actuator/./health`,
    `/actuator/health/..`; the `/error/..` and `/results-store/v1/./shares` cases already gave `404`);
    `ConnectorRejectionIT.a_dot_segment_should_be_refused_404_route_not_found_and_counted(String) > [1] target =
    "/actuator/../results-store/v1/shares?storedAfterSeq=0" FAILED` `expected: "route_not_found" but was:
    "bad_request"` (raw socket: the request reached the dispatcher and was answered through `/error`). GREEN:
    `ActionHeaderFilterTest` 99, `ConnectorRejectionIT` 12, 0 failures.

- [X] T003 [US5] [US6] [US7] Test first: `UnsupportedContentTypeFilterTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/UnsupportedContentTypeFilterTest.java, `BoundedErrorAttributesTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/BoundedErrorAttributesTest.java, `BoundedErrorControllerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/BoundedErrorControllerTest.java, `ProblemReasonTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ProblemReasonTest.java, `MicrometerRefusalObserverTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerRefusalObserverTest.java, `ApiWebConfigTest` (`ApplicationContextRunner`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfigTest.java, `FilterOrderIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/FilterOrderIT.java, `AuthzIT` (moved onto `PostgresTestSupport`, rewritten) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/AuthzIT.java, `ActuatorIntegrationTest` (moved onto `PostgresTestSupport`) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/ActuatorIntegrationTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/filters/UnsupportedContentTypeFilter.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/BoundedErrorAttributes.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/BoundedErrorController.java (implements `ErrorController`; one handler at `/error` for every media type, writing the four fields as `application/json`; counts a `401` as `unauthenticated` and a `403` as `forbidden` through `RefusalObserver`; Boot's `BasicErrorController` then backs off), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/RouteRefusal.java (+ `UNAUTHENTICATED`, `FORBIDDEN`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfig.java (action filter at `HIGHEST_PRECEDENCE`, `415` guard at `HIGHEST_PRECEDENCE + 40`, the `ErrorAttributes` bean, the error controller bean (a bean here, not component-scanned, so `@WebMvcTest` slices do not pick it up), the refusal observer bean, the authz-required check), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerRefusalObserver.java, src/main/resources/application.yaml (`server.error.whitelabel.enabled: false`), src/test/resources/application-test.yaml (header comment: context tests now use the Testcontainers database)
  - Cases: `UnsupportedContentTypeFilterTest`: `multipart_on_a_mapped_route_should_be_refused_415_unsupported_content_type_and_counted`; `json_and_absent_content_type_should_pass`; `actuator_should_never_be_refused`. `BoundedErrorAttributesTest`: `a_401_and_a_403_should_render_type_title_status_and_reason_only` (`unauthenticated`, `forbidden`); `any_other_4xx_should_be_bad_request_and_any_5xx_internal_error`; `no_path_message_error_exception_or_trace_should_ever_appear`. `BoundedErrorControllerTest`: `accept_text_html_json_and_absent_should_all_get_the_four_field_json_body`; `a_401_should_be_counted_once_as_unauthenticated`; `a_403_should_be_counted_once_as_forbidden`; `any_other_status_should_not_be_counted`. `ProblemReasonTest`: `every_reason_should_map_to_one_status_and_a_lower_snake_code`; `the_reasons_should_be_exactly_the_contract_list`. `MicrometerRefusalObserverTest`: `every_reason_should_be_registered_at_start_with_zero`; `no_tag_value_should_parse_as_a_uuid_or_date`. `ApiWebConfigTest`: `authz_off_without_the_test_profile_should_stop_the_service_naming_authz_http_enabled`; `authz_off_with_the_test_profile_should_start`; `the_filters_should_be_registered_at_their_orders`. `FilterOrderIT` (Postgres; authz on with a WireMock usersgroups; `audit.http.enabled` and `cp.audit.enabled` on against `EmbeddedBrokerSupport`): `the_action_filter_should_precede_authz_which_should_precede_the_415_guard_which_should_precede_audit` (from the registered beans' effective orders). `AuthzIT` (WireMock matched on `CJSCPPUID`: one "System Users" id, one id whose groups are "Other Group"): `the_api_should_refuse_a_caller_with_no_identity_with_a_bounded_401_body` (on a mapped route); `the_api_should_refuse_a_caller_in_neither_group_with_a_bounded_403_body`; `a_401_with_accept_text_html_should_get_the_four_field_json_body` (`Content-Type` `application/json`, exactly the four fields); `a_401_and_a_403_should_each_move_read_refused_once`; `an_unmapped_path_should_be_refused_404_route_not_found_before_authentication` (WireMock received no request); `actuator_should_be_reachable_without_an_identity`. `ActuatorIntegrationTest`: unchanged cases, green on Postgres.
  - Covers: FR-042, FR-043 (`/error` half), FR-048, FR-050, FR-051 (counting half), FR-055 (`read.refused`); SC-006 (edge half), SC-008 (authz row).
  - Done when: the nine test classes green; the gate green.
  - Built: `BoundedErrorController` implements `ErrorController` and Spring MVC's `Controller` interface and
    writes the body itself (no content negotiation, so no `Accept` can give a `406` or HTML); `ApiWebConfig`
    maps it at `/error` through its own `SimpleUrlHandlerMapping` (order `HIGHEST_PRECEDENCE`), since a
    `@Controller` in `api/` would be component-scanned. `BoundedErrorAttributes` extends Boot's
    `DefaultErrorAttributes` (it still records the handler's exception) and writes only the four fields; a
    dispatch without an error status, or outside `4xx`/`5xx`, is `500 internal_error`; the title of a status
    Spring does not know (for example `499`) is the reason's own (`Bad Request`). The reason look-up is
    `ProblemReason.forErrorStatus(int)`. The authz check compares `authz.http.enabled` with `true` ignoring case,
    as the library's `@ConditionalOnProperty(havingValue = "true")` does. The audit library's `AuditFilter` is
    `@Order(HIGHEST_PRECEDENCE + 50)` (`javap`), after the `415` guard. `AuthzIT` runs on a real port
    (`RANDOM_PORT`, JDK `HttpClient`) because MockMvc does not perform the error dispatch the library's
    `sendError` relies on; it also checks that a "System Users" caller passes authorisation on every mapped
    route (status neither `401` nor `403`; no endpoint serves yet). `FilterOrderIT` reads the order from
    `ServletContextInitializerBeans`, as Boot registers the chain.
  - RED (seams: `ProblemReason.forErrorStatus` answering `INTERNAL_ERROR`; `UnsupportedContentTypeFilter`
    passing everything; `BoundedErrorAttributes` a bare `DefaultErrorAttributes`; `BoundedErrorController`
    writing nothing; `MicrometerRefusalObserver` registering nothing; `ApiWebConfig` with the observer bean
    only). Run per class (`failFast`): `RouteRefusalTest` (before the two constants) →
    `every_tag_should_come_from_the_fixed_list() FAILED` `Expecting actual: ["route_not_found",
    "method_not_allowed", "unsupported_content_type"] to contain exactly …`;
    `UnsupportedContentTypeFilterTest` → `multipart_on_a_mapped_route_should_be_refused_415_unsupported_content_
    type_and_counted(String) > [1] contentType = "multipart/form-data; boundary=x" FAILED` `expected: null`;
    `BoundedErrorAttributesTest` → `no_path_message_error_exception_or_trace_should_ever_appear() FAILED`
    `Expecting actual: ["timestamp", "status", "error", "exception", "trace", "message", "path"] to contain
    exactly (and in same order): ["type", "title", "status", "reason"]`; `BoundedErrorControllerTest` →
    `any_other_status_should_not_be_counted(int) > [1] status = 400 FAILED` `expected: 400 but was: 200`;
    `ProblemReasonTest` → `an_error_status_should_map_to_its_bounded_reason(int, ProblemReason) > [1] status =
    "401", reason = "UNAUTHENTICATED" FAILED` `expected: UNAUTHENTICATED` (its two table cases pin T002's
    table and passed); `MicrometerRefusalObserverTest` → `every_reason_should_be_registered_at_start_with_zero()
    FAILED`; `ApiWebConfigTest` → `authz_off_without_the_test_profile_should_stop_the_service_naming_authz_http_
    enabled() FAILED` `Expecting: <Started application […]> to have failed but context started successfully`;
    `FilterOrderIT` → `the_action_filter_should_precede_authz_which_should_precede_the_415_guard_which_should_
    precede_audit() FAILED` `Expecting actual: [HttpAuthzFilter, AuditFilter] to contain exactly (and in same
    order): …`; `AuthzIT` → `the_api_should_refuse_a_caller_with_no_identity_with_a_bounded_401_body() FAILED`
    `Expecting actual: "{"timestamp":…,"status":401,"error":"Unauthorized","path":"/results-store/v1/shares/…"}"
    not to contain: "results-store/v1"`, and the same for the `403` case on every route.
    `ActuatorIntegrationTest` (moved onto `PostgresTestSupport`, no new case) was green before and after.
  - GREEN: `UnsupportedContentTypeFilterTest` 8, `BoundedErrorAttributesTest` 10, `BoundedErrorControllerTest`
    12, `ProblemReasonTest` 45, `MicrometerRefusalObserverTest` 7, `ApiWebConfigTest` 3, `FilterOrderIT` 1,
    `AuthzIT` 15, `ActuatorIntegrationTest` 4, `RouteRefusalTest` 1, 0 failures; `IntakeIT` (intake end to
    end) 24, `ExtractionSweepIT` 37 and every other suite green; the gate green (1193 tests passed, 0 skipped;
    coverage line 0.9960, branch 0.9934).
  - Gate round 1 (remediation, one commit per concern):
    - The `415` guard classified the wrapped request's `Content-Type`, which the wrapper answers as
      `application/json` whenever a vendor token appears anywhere in the value, so `multipart/related;
      type="application/vnd.…+json"` passed. `ActionHeaderFilter` now leaves the `Content-Type` as sent in
      `ActionHeaderFilter.SENT_CONTENT_TYPE_ATTRIBUTE` beside the route, and the guard classifies that; the
      wrapper still neutralises the vendor token for the authorisation library. RED:
      `UnsupportedContentTypeFilterTest.multipart_with_a_vendor_parameter_behind_the_action_filter_should_still_
      be_refused_415(String) > [1] … FAILED` `Expecting value to be false but was true` (the servlet was
      reached); `AuthzIT.multipart_from_a_system_users_caller_should_be_refused_415_and_counted(String) > [2]
      contentType = "multipart/related; type=…" FAILED` `expected: 415 but was: 404`. GREEN after the fix.
      `AuthzIT` also gained `multipart_without_an_identity_should_be_401_not_415` (FR-048 (d) in the running
      chain: authorisation first, then the guard on the wrapped request); its plain-multipart case passed at RED.
    - A refusal is counted only after its body has been written and flushed (`RefusalWriter` and
      `BoundedErrorController` now call `flushBuffer()`), so a client that has gone is not counted. RED with
      `support/BrokenPipeResponse` (every write throws): `ActionHeaderFilterTest.a_refusal_whose_body_cannot_be_
      written_should_not_be_counted(String) > [1] methodOrPath = "POST" FAILED` `Expecting empty but was:
      [METHOD_NOT_ALLOWED]` (and `[ROUTE_NOT_FOUND]`); `UnsupportedContentTypeFilterTest.a_415_whose_body_cannot_
      be_written_should_not_be_counted() FAILED` `… [UNSUPPORTED_CONTENT_TYPE]`; `BoundedErrorControllerTest.a_
      refusal_whose_body_cannot_be_written_should_not_be_counted(int) > [1] status = 401 FAILED` `…
      [UNAUTHENTICATED]`. GREEN after moving each count below the write.
    - `/error` writes `application/problem+json` for every status but `401` and `403` (contracts/read-api.md
      §6). RED: `BoundedErrorControllerTest.any_status_but_401_and_403_should_be_written_as_problem_json(int) >
      [1] status = 400 FAILED` `expected: "application/problem+json" but was: "application/json"`; GREEN after.
    - Tests only: `BoundedErrorAttributesTest` pins a status above `599` as `500 internal_error`; `AuthzIT.the_
      configured_action_header_should_be_the_one_the_wrapper_writes` fails the build if
      `authz.http.action-header` drifts from `ActionRequestWrapper.ACTION_HEADER`;
      `ActuatorIntegrationTest.readiness_should_be_up_with_db_in_its_group` (readiness components shown in the
      test only) and the `print()` calls removed; `ReadinessWithoutBrokerIT.readiness_should_stay_up_when_the_
      broker_is_unreachable` (subscription on, broker URL `tcp://localhost:1`; the listener logs its retries and
      readiness stays `UP` with `db`). These passed when written: they pin existing behaviour. The class keeps
      the name `ActuatorIntegrationTest`, which specs 001–003 cite.
    - GREEN: the gate green (1214 tests passed, 0 skipped; JaCoCo report line 0.9961, branch 0.9908).
  - Close-out (orchestrator ruling 2, `HEAD`/`OPTIONS`/`TRACE` on a running server): `AuthzIT.head_and_options_
    on_a_served_path_should_be_refused_405_before_authorisation(String)` (`HEAD`, `OPTIONS` on the `GET_SHARE`
    sample with a `CJSCPPUID` of its own): `405`, `Allow: GET`, `application/problem+json`, the body without the
    path (the four fields with `method_not_allowed` for `OPTIONS`; empty for `HEAD`, which carries no body on
    the wire), `read.refused{method_not_allowed}` moved by one, usersgroups never asked for that caller. It
    passed when written: it pins the action filter in the running chain. `TRACE`: Tomcat (`allowTrace=false`,
    the default) refuses it in `CoyoteAdapter` with `sendError(405)` before any filter, so it lands on the
    service's `/error` page: `405`, `application/problem+json`, the four fields with the generic `4xx` reason
    `bad_request`, an `Allow` header Tomcat builds from the dispatcher servlet's methods (not `GET`), not
    counted, usersgroups not asked. Pinned by `AuthzIT.trace_should_be_refused_405_by_the_connector_and_never_
    counted` (an exploratory run showed the shape first); `TRACE` dropped from
    `ActionHeaderFilterTest.routesAndOtherMethods`, since it never reaches the filter. GREEN: `AuthzIT` 23,
    `ActionHeaderFilterTest` 91, 0 failures.
    - Gate round 1 remediation (codex MEDIUM, `TRACE`): superseded. Tomcat's own refusal was not the contract's
      `405` (generic reason, Tomcat's `Allow`, not counted), so the connector now lets `TRACE` through
      (`TomcatEdgeCustomizer`: `allowTrace=true`) and the action filter refuses it like any other method: on a
      served path by the route table, and on `/actuator/**` and `/error` explicitly (`Allow: GET`), so no
      servlet's `doTrace` ever echoes the request's headers. `TRACE` is back in `ActionHeaderFilterTest.
      routesAndOtherMethods` (passed at once: the filter already refused it). RED: `TomcatEdgeCustomizerTest.
      the_connector_should_reject_encoded_slashes_and_backslashes_and_pass_trace_to_the_filters() FAILED`
      `Expecting value to be true but was false`; `ActionHeaderFilterTest.trace_on_actuator_or_error_should_be_
      refused_405_with_allow_get_and_counted(String) > [1] path = "/actuator" FAILED` `expected: null but was:
      …ActionRequestWrapper@…`; `AuthzIT.trace_should_be_refused_405_method_not_allowed_by_the_action_filter_
      and_counted(String) > [1] target = "share" FAILED` (the `Allow` header held Tomcat's method list, not
      `GET`; also `/actuator/health`). GREEN: `TomcatEdgeCustomizerTest` 3, `ActionHeaderFilterTest` 108,
      `AuthzIT` 24 (replacing `trace_should_be_refused_405_by_the_connector_and_never_counted`),
      `ConnectorRejectionIT` 12, 0 failures. FR-048 (c), contracts/read-api.md §2.2, contracts/metrics.md and
      `application.yaml` say so.
    - Gate round 1 remediation (qa LOWs, pinning only, no production change; each passed when written):
      `AuthzIT.a_second_line_support_caller_should_pass_authorisation_on_every_mapped_route(ApiRoute)` (a third
      stubbed caller whose only group is "Second Line Support"); `QueryParameterNamesTest` adds a percent escape
      cut short at the end of the query (`storedAfterSeq%`, `storedAfterSeq%4`); `TomcatEdgeCustomizerTest.
      a_context_without_a_standard_host_should_be_left_alone`; `ApiWebConfigTest` asserts the
      `TomcatEdgeCustomizer` bean. GREEN: `AuthzIT` 29, `QueryParameterNamesTest` 27, `TomcatEdgeCustomizerTest`
      4, `ApiWebConfigTest` 3, 0 failures.
    - Gate round 1 remediation (documents only): FR-043 now says `/error` writes `application/json` for `401`
      and `403` and `application/problem+json` for any other status, as contracts/read-api.md §6 and the code
      do (spec-validator LOW); contracts/metrics.md *Not counted by 003* now records that a connector-level
      report whose body cannot be written is logged by status and exception class only (code-reviewer LOW).
  - Close-out (orchestrator ruling 3, connector-level URI rejections): option (a) landed. A raw-socket probe
    showed every rejection (`%2F`, `%00`, `%5C`, `%zz`, a bare `%`, and the parser's invalid-character
    `400` for `|` and `{`) reaches the host's `ErrorReportValve` (Boot's, `showReport=false`), which wrote
    Tomcat's HTML page: so the valve is the place to make them conform. Built: `api/ProblemErrorReportValve`
    (extends `ErrorReportValve`; writes only for a `>= 400` response with nothing written and the error not
    yet reported; the four fields from the status via `BoundedErrorAttributes.problemBody` and
    `errorStatus`, now shared, with `BoundedErrorController.mediaType`; never the URI, the exception or
    server details; a body that cannot be written is logged with the status only); `config/
    TomcatEdgeCustomizer` (`WebServerFactoryCustomizer<TomcatServletWebServerFactory>`, `LOWEST_PRECEDENCE`
    so it runs after Boot's customiser: the connector set explicitly to `encodedSolidusHandling=reject`,
    `allowBackslash=false`, `allowTrace=false`, which Boot has no property for; every `ErrorReportValve`
    removed from the host, ours added, and `StandardHost.errorReportValveClass` set to it so the host adds no
    default at start), registered in `ApiWebConfig`; `application.yaml` `server.tomcat.uri-encoding: UTF-8`,
    `relaxed-path-chars: []`, `relaxed-query-chars: []` with a comment. Not counted at first (no `RouteRefusal`
    fitted a connector `400`); superseded by the counting close-out below. RED (seams: the valve's `report`
    writing nothing; the customiser doing nothing): `ConnectorRejectionIT.a_uri_the_connector_rejects_should_
    get_a_400_with_the_four_field_problem_body(String) > [1] target = "/results-store/v1/shares/zq%2Fsecret"
    FAILED` `expected: "application/problem+json" but was: "text/html;charset=utf-8"` (and `%00`);
    `ProblemErrorReportValveTest.an_error_should_get_the_four_field_problem_body(int, String, String) > [1]
    status = "400" … FAILED` `Expecting actual: [] to contain exactly …`; `TomcatEdgeCustomizerTest.the_host_
    should_report_errors_with_the_problem_valve_only() FAILED` `Expecting actual: ErrorReportValve[…]` (its
    connector case also failed; its order case passed at RED). GREEN: `ConnectorRejectionIT` 8 (`%2F`, `%00`,
    `%zz`, `%5C`, bare `%`, `|`, `{`, `|` in the query; each `400`, `application/problem+json`, the four
    fields, `read.refused` unmoved), `ProblemErrorReportValveTest` 12, `TomcatEdgeCustomizerTest` 3,
    `BoundedErrorAttributesTest` 10, `BoundedErrorControllerTest` 20, `AuthzIT` 23, 0 failures.
  - Close-out (orchestrator rulings, constitution VIII and XI; they withdraw the earlier "not counted"
    ruling):
    - Connector-level refusals counted: `RouteRefusal.CONNECTOR_REJECTED` (tag `connector_rejected`),
      registered at start by `MicrometerRefusalObserver` with every other reason. `ProblemErrorReportValve`
      takes a `RefusalObserver` and counts a `4xx` report only after `finishResponse()` has returned, outside
      the write's `try`, as `RefusalWriter` and `BoundedErrorController` do: a body that cannot be written is
      not counted, and a `5xx` it writes is a server failure, not a refusal. `TomcatEdgeCustomizer` hands the
      valve the observer; `ApiWebConfig` passes one that looks the bean up through an `ObjectProvider` when a
      refusal is counted, so building the web server does not create the meter registry early.
      contracts/metrics.md lists the tag on `read.refused` and drops the connector line from *Not counted by
      003*; contracts/read-api.md §6 says such a request is counted as `connector_rejected`. RED (seam: the
      constant and the constructors, the valve counting nothing): `ConnectorRejectionIT.a_uri_the_connector_
      rejects_should_get_a_400_with_the_four_field_problem_body_and_be_counted_once(String) > [1] target =
      "/results-store/v1/shares/zq%2Fsecret" FAILED` `expected: 1.0 but was: 0.0` (and `%00`);
      `ProblemErrorReportValveTest.a_4xx_report_written_in_full_should_be_counted_once_as_connector_
      rejected(int) > [1] status = "400" FAILED` `Expecting actual: [] to contain exactly (and in same order):
      [CONNECTOR_REJECTED]` (and `404`, `414`, `499`). GREEN: `ConnectorRejectionIT` 12 (each of the eight
      targets moves `connector_rejected` by exactly one and `read.refused` in all by exactly one),
      `ProblemErrorReportValveTest` 19 (adds the `4xx` count, no count for a `5xx`, none when
      `finishResponse()` throws, none when the body cannot be written, none when nothing is reported),
      `TomcatEdgeCustomizerTest` 4, `RouteRefusalTest` 1, `MicrometerRefusalObserverTest` 8, `AuthzIT` 29,
      0 failures.
    - Rejected request targets kept out of the logs: Tomcat's `Http11Processor` logs each request
      processor's first parse failure at `INFO` with the `IllegalArgumentException`, whose message quotes the
      target. `logback.xml` pins to `WARN`, with the constitution XI reason: `org.apache.coyote.http11.
      Http11Processor`, `org.apache.coyote.http11.Http11InputBuffer` (the raw request at `TRACE`),
      `org.apache.coyote.AbstractProcessor` (an invalid host at `INFO`), `org.apache.catalina.connector.
      CoyoteAdapter` (the URI at `DEBUG`) and `org.apache.tomcat.util.http.parser` (the cookie header). New
      `ConnectorRejectionLogIT` (real port, capture on the real logback root at the configured levels,
      `server.tomcat.processor-cache=0` so every connection gets a new processor and each rejection is that
      processor's first, whatever ran before in the JVM): `a_rejected_target_should_reach_no_log_line` sends
      the eight targets with the marker `marker-9f3c` and asserts each is `400` and no captured logger name,
      message, exception class or exception message holds the marker or `IllegalArgumentException`. RED
      (before the `logback.xml` change): `ConnectorRejectionLogIT.a_rejected_target_should_reach_no_log_
      line() FAILED` `Expecting no elements of: ["org.apache.coyote.http11.Http11Processor", "Error parsing
      HTTP request header … ", "java.lang.IllegalArgumentException", "Invalid character found in the request
      target [/results-store/v1/shares/zq|marker-9f3c ]. …" …` (the `|`, `{` and query `|` targets; the
      percent-escape rejections are not logged). GREEN: `ConnectorRejectionLogIT` 1, `ConnectorRejectionIT`
      12, 0 failures.
    - Gate: `./gradlew build pmdMain pmdTest jacocoTestReport` exit 0 (1302 tests passed, 0 skipped;
      JaCoCo report line 0.9963, branch 0.9898).
  - Close-out, confirmed by the orchestrator (no code change): the allow rules' path regex has one segment
    (`[^/]+`) per template variable, so a malformed id reaches the endpoint as `400`, never a `403` (T001); a
    raw `;` in a path is refused `404 route_not_found` by `ApiRoute`, now said in contracts/read-api.md §2.2;
    `BoundedErrorController` as a hand-mapped `Controller` behind its own `SimpleUrlHandlerMapping` stays;
    `ActuatorIntegrationTest` keeps its name.
  - Close-out GREEN: the gate green (1263 tests passed, 0 skipped; JaCoCo report line 0.9963, branch 0.9878).

---

## Phase B: data and application (T004–T008)

**Purpose**: the V5 trigger and indexes, the read types and ports, the read-only SQL with the visibility
bound, the read service, and the intake-side overrun counter. Nothing is served over HTTP yet. Depends on
phase A (`ApiRoute`, `ReadEndpoint`, `ProblemReason`).

**Independent test**: `FlywayMigrationIT` proves the schema; `JdbcShareQueriesIT` proves pull safety, the
bound, the filters, search and the payload read; `ReadQueriesPlanIT` ties each query to its index;
`ShareReadServiceTest` proves limits, cursors and the `ETag`; `IntakeServiceTest` and `JdbcShareStoreIT`
prove the overrun counter.

- [X] T004 [P] [US1] [US4] Test first: `FlywayMigrationIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/FlywayMigrationIT.java; then src/main/resources/db/migration/V5__read_api.sql (data-model.md, in full)
  - Cases: `v5_should_create_the_stored_at_trigger_and_the_three_partial_indexes` (`hearing_share_youth_feed_ix (stored_seq)`, `hearing_share_centre_feed_ix (court_centre_id, stored_seq)`, `hearing_share_centre_shared_at_ix (court_centre_id, shared_at, share_id)`: names, `pg_get_indexdef` predicates and column order; no index on `shared_day_london`; `BEFORE INSERT` timing); `stored_at_should_be_at_or_after_a_clock_read_taken_just_before_the_insert` (same connection: `clock_timestamp()`, then the insert, then compare); `an_insert_supplying_stored_at_should_be_overridden` (a value one hour in the past comes back as now); `the_stored_seq_sequence_should_have_cache_one` (`pg_sequences` for `pg_get_serial_sequence('hearing_share', 'stored_seq')`); `updating_stored_at_should_still_be_refused` (`hearing_share_guard`); the existing version list and V1–V4 checksum cases updated to include V5.
  - Covers: FR-019, FR-054; contracts/schema.md rules 1, 2, 4, 6.
  - Done when: `FlywayMigrationIT` green; the gate green.
  - Built: src/main/resources/db/migration/V5__read_api.sql exactly as data-model.md *V5 DDL*. The V1 to V4
    "checksum cases" did not exist yet; `v1_to_v4_should_keep_the_checksums_they_were_applied_with` now pins
    Flyway's recorded checksums of V1 to V4 (an edit to an applied migration changes them).
  - RED (no seam needed: V5 absent). The suite runs with `failFast`, so each new case was run alone:
    `startup_on_an_empty_database_should_apply_v1_to_v5() FAILED` `Expecting actual: ["1", "2", "3", "4"] to
    contain exactly (and in same order): ["1", "2", "3", "4", "5"] but could not find the following elements:
    ["5"]`; `v5_should_create_the_stored_at_trigger_and_the_three_partial_indexes() FAILED` `Expecting actual:
    {} to contain only following keys: ["hearing_share_youth_feed_ix", "hearing_share_centre_feed_ix",
    "hearing_share_centre_shared_at_ix"]`; `HearingShare.an_insert_supplying_stored_at_should_be_overridden()
    FAILED` `[stored_at set by the trigger, not by the insert] Expecting actual: 2026-10-03T22:30:44.275186Z to
    be after or equal to: 2026-10-03T23:30:44.273610Z`. `v1_to_v4_should_keep_the_checksums_they_were_applied_
    with` was written with placeholder values and failed `Expecting map: {"1"=-1073667405, "2"=-927700843,
    "3"=-1989865135, "4"=-586884824} to contain only: ["4"=0, …]`; the recorded values are now its constants.
    Passed at RED, pinning behaviour V5 must keep: `stored_at_should_be_at_or_after_a_clock_read_taken_just_
    before_the_insert` (V3's `DEFAULT clock_timestamp()` also reads the clock during the insert; the trigger's
    ordering against the identity value is what R4 needs, and no single-session test can tell the two apart),
    `updating_stored_at_should_still_be_refused` (`hearing_share_guard`), `the_stored_seq_sequence_should_have_
    cache_one` (`pg_sequence.seqcache` of `pg_get_serial_sequence('hearing_share', 'stored_seq')`).
  - GREEN: `FlywayMigrationIT` 127 (top level 6, `EventReceipt` 32, `HearingShare` 80, `IdentityEdges` 9), 0
    failures; the gate green (1308 tests passed, 0 skipped; JaCoCo report line 0.9963, branch 0.9898).

- [X] T005 [P] [US1] [US2] [US3] [US4] [US7] Test first: `SearchCursorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/SearchCursorTest.java, `DayYouthFilterTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/DayYouthFilterTest.java, `ShareViewTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ShareViewTest.java, `ReadOutcomeTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ReadOutcomeTest.java, `PayloadChecksumTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadChecksumTest.java, `EnvelopeMetadataTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/EnvelopeMetadataTest.java, `SharedDaysTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/SharedDaysTest.java, `MicrometerReadObserverTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerReadObserverTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ShareView.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/DayYouthFilter.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SearchCursor.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/StoredPayload.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadForm.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReadOutcome.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadChecksum.java (+ `sha256Hex(byte[])`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/EnvelopeMetadata.java (parse with Jackson 3, remove the top-level `_metadata` member, write back compact; exact decimals; key order kept), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SharedDays.java (+ the London day range as a [from, to) instant range, `Europe/London`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareQueries.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ReadObserver.java (port: `request(ReadEndpoint, ReadOutcome, Duration)`, `pageItems(int)`, `payloadBytes(long)`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/PullQuery.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/SearchQuery.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerReadObserver.java
  - Cases: `SearchCursorTest` (cursor = `v1|<shared_at epoch microseconds>|<shareId>`, E6): `encode_then_decode_should_round_trip`; `the_encoded_cursor_should_never_exceed_128_characters` (the longest microsecond value); `a_tampered_truncated_padded_over_long_or_non_base64url_cursor_should_be_invalid`; `a_cursor_with_another_version_prefix_extra_part_bad_uuid_or_non_canonical_or_overflowing_microseconds_should_be_invalid` (renamed in gate round 1: negative microseconds are valid, see below). `EnvelopeMetadataTest`: `the_top_level_metadata_member_should_be_removed`; `a_nested_metadata_key_should_be_left_alone`; `a_text_without_metadata_should_keep_its_content`; `key_order_and_exact_numbers_should_be_kept` (`1.50`, a 20-digit integer); `a_u0000_escape_should_survive` (the case jsonb cannot hold); `a_text_that_is_not_json_should_fail_without_echoing_it`. `SharedDaysTest`: `a_london_day_range_should_become_london_midnight_to_london_midnight` (BST and GMT); `a_range_over_the_spring_and_autumn_clock_changes_should_cover_23_and_25_hours`. `DayYouthFilterTest`: `from_value_should_accept_not_false_true_and_false_case_sensitively`; `anything_else_should_be_refused` (`NOTFALSE`, `True`, empty); `false_should_not_be_allowed_on_pull`. `ShareViewTest`: `key_details_should_be_null_exactly_when_failed`. `ReadOutcomeTest`: `every_tag_should_come_from_the_fixed_list`. `PayloadChecksumTest`: `the_bytes_form_should_equal_the_text_form_for_utf_8`; `different_bytes_should_give_different_hashes`. `MicrometerReadObserverTest`: `every_meter_and_tag_combination_should_be_registered_at_start` (`not_modified` only with `payload`); `each_callback_should_move_exactly_its_meter`; `no_tag_value_should_parse_as_a_uuid_or_date`.
  - Notes: `SearchCursor.decode` and `DayYouthFilter.fromValue` are single-exit (PMD). `EnvelopeMetadata` never puts the text in an exception message (Principle XI).
  - Covers: FR-006 (shape), FR-011 (values), FR-027 (day translation), FR-029, FR-033 (text-form strip), FR-039, FR-041 (strip, reused by T013), FR-055 (read meters).
  - Done when: the six test classes green; the gate green.
  - Built: `ShareView` (compact constructor: identity, times and status required; `keyDetails` null exactly
    when `FAILED`, else `IllegalArgumentException`); `DayYouthFilter` (`fromValue` → `Optional`, a stream
    look-up, so single exit; `wireValue()`, `allowedOnPull()`); `SearchCursor` (`after(Instant, UUID)`,
    `encode()`, `decode(String)` → `Optional`: at most 128 characters, base64url alphabet only, UTF-8 checked,
    `^v1\|(0|[1-9][0-9]{0,18})\|<lower-case canonical uuid>$`, no overflow, and the text must be exactly what
    `encode()` writes for the position, which also refuses padding and set unused trailing bits); `StoredPayload`
    and `PayloadForm` (`headerValue()` `working-copy` / `arrived-text`); `ReadOutcome` (+ `appliesTo(ReadEndpoint)`:
    `not_modified` only with `payload`); `PayloadChecksum.sha256Hex(byte[])` (the `String` form now delegates to
    it); `EnvelopeMetadata.strip` (Jackson 3 reader with `FAIL_ON_TRAILING_TOKENS`, `USE_BIG_DECIMAL_FOR_FLOATS`,
    without `STRIP_TRAILING_BIGDECIMAL_ZEROES`, as intake reads; only a top-level object loses `_metadata`; a
    parse failure is rethrown as `EnvelopeMetadata.UnreadablePayloadException` naming the parser's exception
    class only, no cause); `SharedDays.londonDays(from, to)` → `SharedDays.InstantRange` (a nested record);
    the ports `ShareQueries` (`pull(PullQuery, Duration)` → `ShareQueries.PullRows(visibleUpTo, boundSeq,
    rows)`, `search(SearchQuery)`, `share`, `dayVersions`, `payload`) and `ReadObserver`; `PullQuery` and
    `SearchQuery` (the checked values; `SearchQuery` holds the instant range and the decoded cursor);
    `MicrometerReadObserver` (every pair registered at start; an unregistered pair such as `share/not_modified`
    is refused with `IllegalArgumentException` rather than creating a series). No bean: nothing injects them
    until T009. Also `PayloadFormTest` (one case, the header values; passed when written).
  - RED (seams: `tag()` returning `name()`, `appliesTo` always true, `fromValue` empty, `allowedOnPull` true,
    `SearchCursor` encoding `""` and decoding nothing, `sha256Hex(byte[])` `""`, `strip` returning its input,
    `londonDays` the epoch twice, `ShareView` without its checks, `MicrometerReadObserver` registering nothing).
    Run per class (`failFast`): `SearchCursorTest` → `the_encoded_cursor_should_never_exceed_128_characters()
    FAILED` `Expecting Optional to contain: SearchCursor[sharedAtMicros=9223372036854775807, …] but was empty.`
    (and `zero_microseconds_should_round_trip`, `encode_then_decode_should_round_trip`); `DayYouthFilterTest` →
    `"notFalse" -> "NOT_FALSE" FAILED` `Expecting Optional to contain: NOT_FALSE but was empty.`; `ShareViewTest`
    → `key_details_should_be_null_exactly_when_failed() FAILED` `Expecting code to raise a throwable.`;
    `ReadOutcomeTest` → `not_modified_should_apply_to_the_payload_only(ReadEndpoint) > [1] endpoint = PULL FAILED`
    `expected: false but was: true`; `PayloadChecksumTest` → `the_bytes_form_should_equal_the_text_form_for_utf_8
    > "empty" FAILED` `expected: "e3b0c442…b855" but was: ""`; `EnvelopeMetadataTest` →
    `the_top_level_metadata_member_should_be_removed() FAILED` `expected: "{"hearing":{"id":"h"},…}" but was:
    "{"_metadata": …}"` (synthetic fixture); `SharedDaysTest` → `a_range_over_the_spring_and_autumn_clock_changes_
    should_cover_23_and_25_hours() FAILED` `expected: 23H but was: 0S`; `MicrometerReadObserverTest` →
    `a_pair_that_is_not_registered_should_be_refused_rather_than_create_a_series() FAILED` `Expecting code to
    raise a throwable.`, `each_callback_should_move_exactly_its_meter() FAILED` `MeterNotFoundException: … No
    meter with name 'resultsstore.read.page.items' was found.`
  - GREEN: `SearchCursorTest` 30, `DayYouthFilterTest` 15, `ShareViewTest` 2, `ReadOutcomeTest` 11,
    `PayloadChecksumTest` 9, `EnvelopeMetadataTest` 10, `SharedDaysTest` 12, `MicrometerReadObserverTest` 4,
    `PayloadFormTest` 1, 0 failures; the gate green (1392 tests passed, 0 skipped; JaCoCo report line 0.9874,
    branch 0.9871).
  - Gate round 1 (Codex MEDIUM, pre-epoch cursors; QA MEDIUM, overflow path): intake accepts four-digit years
    from 0000, so a search page can end on a share from before 1970, whose cursor `after(...)` refused (a 500
    for that page). The microseconds are now signed, written as `Long.toString` writes them (`-1`, never
    `-0`, `+1` or `-01`); the compact constructor checks only that `shareId` is present. Tests:
    `a_shared_time_before_the_epoch_should_round_trip_with_a_minus_sign` (1969-12-31T23:59:59.999999Z,
    1900-06-01T10:00:00.5Z, 0000-01-01T00:00:00Z); `the_long_range_ends_should_decode_and_one_beyond_should_not`;
    `a_cursor_without_a_share_id_should_not_be_built`; the invalid list gains `-0`, `--1`, `-01`, `- 1` and the
    19-digit `9223372036854775808` / `-9223372036854775809`, which reach `Long.parseLong` and its
    `NumberFormatException` path (the 20-digit row never did); its `-1` row moved to the valid side. RED:
    `a_shared_time_before_the_epoch_should_round_trip_with_a_minus_sign(String) > [1] "1969-12-31T23:59:59.999999Z"
    FAILED` `IllegalArgumentException: sharedAtMicros must not be negative`; `the_long_range_ends_should_decode_
    and_one_beyond_should_not() FAILED` (the same). GREEN: `SearchCursorTest` 40, 0 failures. research.md R9
    says the value is signed.

- [X] T006 [US1] [US2] [US3] [US4] Test first: `JdbcShareQueriesIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueriesIT.java, `JdbcShareQueriesTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueriesTest.java, `ReadQueriesPlanIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/ReadQueriesPlanIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueries.java (the constants of data-model.md *Read queries*; its own `JdbcTemplate` passed in; autocommit)
  - Cases (`JdbcShareQueriesIT`, Testcontainers; shares stored through `JdbcShareStore` with the `support/SampleShares` samples; rows that need a chosen `stored_at` are inserted on a test connection with `SET session_replication_role = replica`, which skips the trigger and guards for that insert only): `pull_should_be_exclusive_of_the_cursor_and_ascending_by_stored_seq`; `pull_should_read_at_most_limit_plus_one_rows`; `pull_should_return_nothing_and_keep_the_cursor_when_no_share_is_older_than_the_lag`; `a_slow_lower_number_should_never_be_overtaken` (two connections: an open insert at *n*, a committed *n + 1*; lag 0 shows the race, so the test can fail; with the lag above the open transaction's age *n + 1* is withheld until *n* ends); `a_row_below_the_bound_should_be_returned_even_if_its_own_stored_at_is_after_the_cut_off` (research R4); `the_bound_should_be_the_highest_number_older_than_the_lag_whatever_the_filter` (filters that match nothing still return `max_seq`); `visible_up_to_should_be_the_database_clock_minus_the_lag`; `not_false_should_include_unknown_days_and_exclude_false_ones`; `true_should_include_only_true_days`; `court_filter_should_return_exact_matches_only`; `court_filter_should_exclude_failed_rows` (E5); `a_failed_share_whose_court_the_sweep_fills_later_should_not_be_presented_to_a_court_pull_from_a_later_cursor` (E5 consequence); `key_details_should_be_null_for_a_failed_row`; `version_number_should_follow_shared_at_and_move_when_an_earlier_share_arrives_late`; `search_day_form_should_filter_the_london_day_inclusively_including_a_00_30_bst_share`; `search_day_form_should_return_the_same_rows_as_shared_day_london_between` (days at both clock changes); `search_time_form_should_be_half_open_on_shared_at` (a share exactly at `sharedTo` is excluded, one exactly at `sharedFrom` included); `search_should_order_by_shared_at_then_share_id`; `search_keyset_pages_should_neither_repeat_nor_miss_rows` (limit 1 over several days and courts, both forms); `search_latest_only_should_return_only_is_latest`; `search_day_youth_seen_variants_should_include_false`; `search_should_never_return_a_failed_row`; `day_versions_should_be_in_shared_at_order_with_row_numbers`; `payload_should_return_the_working_copy_without_metadata_as_the_database_writes_it` (E8; the text equals `(payload_json - '_metadata')::text` and has no `_metadata` key); `payload_should_return_payload_text_and_the_arrived_form_when_the_working_copy_is_null` (the service strips it, T007); `an_unknown_share_payload_or_day_should_be_empty`; `the_read_query_timeout_should_cancel_a_query_held_by_a_lock` (another connection holds `ACCESS EXCLUSIVE` on `hearing_share`; 1 s JDBC query timeout; Spring's `QueryTimeoutException` family). `JdbcShareQueriesTest`: `no_pull_search_share_or_day_constant_should_name_hearing_share_payload`; `the_variant_table_should_cover_every_day_youth_filter_and_court_combination`. `ReadQueriesPlanIT` (`EXPLAIN (FORMAT JSON)` of the **`JdbcShareQueries` constants**, with `SET LOCAL enable_seqscan = off` run inside the test's own transaction, because `SET LOCAL` does nothing outside one; or plain `SET` on a connection the test owns): `pull_not_false_and_true_should_use_hearing_share_youth_feed_ix`; `unfiltered_pull_should_use_hearing_share_stored_seq_uk`; `court_pull_should_use_hearing_share_centre_feed_ix_with_no_sort_node` (with and without a youth filter); `the_bound_should_scan_hearing_share_stored_seq_uk_backward`; `search_day_form_and_time_form_should_both_use_hearing_share_centre_shared_at_ix_with_no_sort_node`; `day_versions_and_version_number_should_use_hearing_share_identity_uk`; `no_pull_search_share_or_day_plan_should_touch_hearing_share_payload`.
  - Covers: FR-009–FR-016, FR-026–FR-033 (data half), FR-038, FR-039 (working copy half), FR-054 (plan half); data-model.md invariants 2–4, 6–8; SC-001, SC-002 (data half), SC-007.
  - Done when: the three test classes green; the gate green.
  - Built: `JdbcShareQueries(JdbcTemplate)` over `JdbcClient.create(template)`, so the template's query
    timeout bounds every statement. The constants of data-model.md *Read queries*: `SHARE_SQL`,
    `DAY_VERSIONS_SQL`, `PAYLOAD_SQL`, and the 6 pull and 16 search variants, built once at class load from
    fixed fragments (day filter × court; day filter × latest only × cursor) and chosen by `pullSql(filter,
    byCourt)` / `searchSql(filter, latestOnly, afterCursor)` (package-private, read by the tests);
    `pullSql(FALSE, …)` throws `IllegalArgumentException`. The pull's `:lagSeconds` is bound as fractional
    seconds; its rows are put in `stored_seq` order in Java as well (the lateral join keeps the page's order in
    practice, but SQL does not promise it without an outer `ORDER BY`, which would add a sort). An empty page
    still returns the bound row (`visibleUpTo`, `boundSeq`, null share). The cursor is bound as
    `(:cursorAt, :cursorId)`; `ProjectionStatus` decides whether `keyDetails` is built. Failures are thrown as
    Spring's `DataAccessException`s; T010's advice maps them.
  - Tests: `JdbcShareQueriesIT` (nested `PullQueries`, `SearchQueries`, `OneShare`; truncates the share tables
    before and after each case) adds to the listed cases `no_filter_should_include_every_day`,
    `every_column_should_reach_the_view` and `search_should_read_at_most_limit_plus_one_rows`; the slow writer
    is a second pooled connection holding an open insert of a complete day. `JdbcShareQueriesTest` adds
    `every_search_should_require_the_court_and_the_half_open_range`, `false_should_have_no_pull_variant`,
    `every_pull_should_be_bounded_by_the_visibility_bound_in_the_same_statement`. `ReadQueriesPlanIT` adds
    `one_share_should_use_hearing_share_pk`.
  - `ReadQueriesPlanIT` departures from the task text, both test-side: (1) it sets `enable_bitmapscan = off`
    as well as `enable_seqscan`: at test volume the planner prefers a bitmap scan and a sort for search, which
    says nothing about whether the index serves the order; with both off the plan shows the index alone serves
    it (no Sort node). (2) The `versionNumber` count (alias `v`) is accepted on `hearing_share_identity_uk` or
    `hearing_share_day_share_uk`: both lead with `(hearing_id, hearing_day)`, cost the same for a day's few
    rows, and the planner's choice between them flipped between runs of the full suite; the day-versions query
    itself is held to `hearing_share_identity_uk` (it needs the `shared_at` order). Its fixture is 2,500 hearing
    days of two shares each (the later latest), fifty courts, every youth flag, 1 in 40 `FAILED`, then `VACUUM
    ANALYZE`; it truncates the share tables afterwards so no later suite (the sweep included) sees the rows.
  - RED (seam: every constant `SELECT 1 FROM hearing_share_payload` (`PAYLOAD_SQL` `SELECT 1`), every method
    answering empty). Run per method (`failFast`): `PullQueries.pull_should_be_exclusive_of_the_cursor_and_
    ascending_by_stored_seq() FAILED` `Expecting actual: [] to contain exactly (and in same order): [2L, 3L,
    4L]`; `PullQueries.a_slow_lower_number_should_never_be_overtaken() FAILED` `[lag 0] Expecting ListN: [] to
    contain: [2L]`; `PullQueries.visible_up_to_should_be_the_database_clock_minus_the_lag() FAILED` `Expecting
    actual not to be null`; `SearchQueries.search_day_form_should_filter_the_london_day_inclusively_including_a_
    00_30_bst_share() FAILED` `Expecting actual: [] to contain exactly (and in same order): [0603d6e1-…,
    77e40cf9-…]`; `OneShare.payload_should_return_the_working_copy_without_metadata_as_the_database_writes_it()
    FAILED` `[present] Expecting Optional to contain a value but it was empty.`; `OneShare.the_read_query_
    timeout_should_cancel_a_query_held_by_a_lock() FAILED` `Expecting code to raise a throwable.`;
    `ReadQueriesPlanIT.unfiltered_pull_should_use_hearing_share_stored_seq_uk() FAILED` `Expecting actual: []
    to contain exactly (and in same order): ["hearing_share_stored_seq_uk"]`; `JdbcShareQueriesTest.no_pull_
    search_share_or_day_constant_should_name_hearing_share_payload() FAILED` `Expecting all elements of:
    ["SELECT 1 FROM hearing_share_payload", …`. (`an_unknown_share_payload_or_day_should_be_empty` passed on
    the seam, as an empty answer is its expectation.) Three test mistakes were found on the way to green and
    fixed in the tests: a court-and-`notFalse` case whose row had a `false` day flag; the same-instant order
    expected `UUID.compareTo`, which is signed, where PostgreSQL orders `uuid` by unsigned bytes (the text
    order); and the plan fixture had no latest shares, so `latestOnly` chose `hearing_share_one_latest_ux`.
  - GREEN: `JdbcShareQueriesIT` 35 (`PullQueries` 16, `SearchQueries` 14, `OneShare` 5), `JdbcShareQueriesTest`
    8, `ReadQueriesPlanIT` 16, 0 failures; the read timeout surfaces as Spring's `QueryTimeoutException`. The
    gate green (1451 tests passed, 0 skipped; JaCoCo report line 0.9951, branch 0.9863).
  - Gate round 1: `OneShare.a_hearing_with_two_days_should_keep_each_days_versions_apart` (QA MEDIUM: the
    matrix's multi-day case; one hearing, two shares on 2026-10-02 and three on 2026-10-03, inserted out of
    order; each day's versions and `share(...).versionNumber()` count within the day). It passes at once, pinning
    behaviour T006 built; checked by mutation: without `v.hearing_day = s.hearing_day` in the version-number
    subquery it fails `expected: 1L but was: 3L`, and without `s.hearing_day = :hearingDay` in the day query it
    fails listing day two's shares. `SearchQueries.search_keyset_pages_should_cross_the_epoch` (Codex MEDIUM:
    five shares from 1969-12-31T12:00Z to 1970-01-01T00:00:00.000001Z, two at the same instant, paged one at a
    time). RED (before the `SearchCursor` change): `search_keyset_pages_should_cross_the_epoch() FAILED`
    `IllegalArgumentException: sharedAtMicros must not be negative`. GREEN: `JdbcShareQueriesIT` 37
    (`PullQueries` 16, `SearchQueries` 15, `OneShare` 6), 0 failures.

- [X] T007 [US1] [US2] [US3] [US4] Test first: `ShareReadServiceTest` (plain mocks, no Spring) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadServiceTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadService.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/PullPage.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/SearchPage.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ServedPayload.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/BadParameterException.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/NotFoundException.java (both carry a `ProblemReason` only, never a caller value)
  - Cases: `pull_limit_should_default_to_100_and_refuse_0_and_501`; `has_more_should_be_true_only_when_limit_plus_one_rows_came_back`; `next_stored_after_seq_should_be_the_last_item_when_there_is_more`; `next_stored_after_seq_should_be_the_greater_of_the_cursor_and_the_bound_otherwise`; `a_null_bound_should_keep_the_cursor`; `visible_up_to_should_be_passed_through`; `search_day_form_should_accept_31_days_and_refuse_32_and_a_reversed_range`; `search_time_form_should_accept_31_days_and_refuse_31_days_and_a_microsecond_and_a_to_not_after_from` (`time_range_too_long`, `time_range_reversed`); `search_day_form_should_reach_the_query_as_london_midnight_instants`; `search_next_cursor_should_encode_the_last_item_and_be_null_on_the_last_page`; `a_bad_cursor_should_be_invalid_cursor`; `payload_etag_should_be_the_quoted_sha256_of_exactly_the_bytes_returned` (non-ASCII text); `the_arrived_text_form_should_be_served_without_metadata_and_hashed_after_the_strip` (E8); `a_working_copy_should_be_served_as_read` (the database already removed `_metadata`); `an_arrived_text_that_fails_to_parse_should_be_internal_error_never_the_text`; `payload_form_should_follow_the_stored_form`; `an_unknown_share_should_be_share_not_found`; `an_empty_day_should_be_hearing_day_not_found`; `page_items_and_payload_bytes_should_be_reported_to_the_observer`.
  - Covers: FR-010, FR-013, FR-014, FR-026–FR-029, FR-032, FR-033, FR-034, FR-039; SC-003 (unit half).
  - Done when: `ShareReadServiceTest` green; the gate green.
  - Built: `ShareReadService(ShareQueries, ReadObserver, Duration visibilityLag)` with `pull(storedAfterSeq,
    Integer limit, DayYouthFilter, UUID court)`, `search(ShareReadService.SearchRequest)` (a nested record of
    the search parameters as the controller reads them: court, both forms, filter, `Boolean latestOnly`,
    `Integer limit`, `String cursor`), `share`, `dayVersions`, `payload`. Limits: `null` → 100, outside 1..500
    → `limit_out_of_range`. Pull also refuses a negative cursor (`invalid_stored_after_seq`) and `FALSE`
    (`invalid_day_youth_seen`). Search refuses a missing court or no complete form (`missing_parameter`), a
    value of each form (`conflicting_parameters`), the day and time range rules of FR-027, and, defensively,
    an instant finer than a microsecond (`invalid_shared_from` / `invalid_shared_to`; T010's parameter check
    refuses the text first). The payload: the working copy's text as read, the arrived text through
    `EnvelopeMetadata.strip`; `ETag` = `"` + SHA-256 hex of the bytes + `"`; an unparseable arrived text
    propagates `EnvelopeMetadata.UnreadablePayloadException` (no text in it; T010's advice answers it `500
    internal_error`) and records no bytes. `ServedPayload` keeps its own copy of the bytes, compares by them and
    prints no body. `BadParameterException` / `NotFoundException` carry the `ProblemReason` (message = its
    code). Extra cases: `pull_should_accept_the_limits_1_and_500_and_pass_its_filters`,
    `a_negative_cursor_or_a_false_filter_should_be_refused_on_pull`, `an_instant_finer_than_a_microsecond_should_be_
    refused`, `search_should_need_the_court_and_exactly_one_complete_form`, `search_limit_should_default_to_100_
    and_refuse_0_and_501`, `a_cursor_should_reach_the_query_decoded`, `a_known_share_should_be_returned`,
    `a_day_with_shares_should_be_returned_in_the_order_read`, `an_unknown_payload_should_be_share_not_found`,
    `served_payloads_should_compare_by_their_bytes`.
  - RED (seam: `pull` and `search` returning empty pages, `share` `orElse(null)`, `dayVersions` passing through,
    `payload` `null`). Run per method (`failFast`): `pull_limit_should_default_to_100_and_refuse_0_and_501()
    FAILED` `WantedButNotInvoked: queries.pull(<Capturing argument: PullQuery>, PT1M30S)`;
    `next_stored_after_seq_should_be_the_greater_of_the_cursor_and_the_bound_otherwise() FAILED` `expected: 90L
    but was: 0L`; `search_day_form_should_reach_the_query_as_london_midnight_instants() FAILED`
    `WantedButNotInvoked: queries.search(…)`; `a_bad_cursor_should_be_invalid_cursor() FAILED` `Expecting code to
    raise a throwable.`; `an_unknown_share_should_be_share_not_found() FAILED` `Expecting code to raise a
    throwable.`; `page_items_and_payload_bytes_should_be_reported_to_the_observer() FAILED` `WantedButNotInvoked:
    observer.pageItems(2); Actually, there were zero interactions with this mock.`
  - GREEN: `ShareReadServiceTest` 30, 0 failures; the gate green (1481 tests passed, 0 skipped; JaCoCo report
    line 0.9954, branch 0.9744).
  - Gate round 1 (Codex MEDIUM, unsigned cursors): not built. A MAC needs a key shared by every pod and its
    rotation, which R9 did not design; FR-029 asks only for a strict decode, which is what was built. spec.md
    acceptance 6 and contracts/read-api.md (the cursor note and the `invalid_cursor` row) now say "does not
    decode to a valid position" instead of "altered" / "not a cursor the store issued". The orchestrator rules
    whether a signed cursor is wanted (then it is a new task with its key settings).
  - Gate round 1: `a_page_ending_before_the_epoch_should_get_a_next_cursor_that_decodes` (Codex MEDIUM). RED:
    `FAILED` `IllegalArgumentException: sharedAtMicros must not be negative` at `SearchCursor.after`. GREEN:
    `ShareReadServiceTest` 31, 0 failures.

- [X] T008 [P] [US1] [US7] (D-OVERRUN = yes, E4; D-LAG-VALUE = 90 s, E3; touches spec 001 code) Test first: `IntakeServiceTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/IntakeServiceTest.java, `JdbcShareStoreIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStoreIT.java, `MicrometerIntakeObserverTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserverTest.java, `IntakeConfigTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfigTest.java, `ConfigurationValidationTest` (extended, intake defaults only) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java, `StatementTimeoutBackstopTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/StatementTimeoutBackstopTest.java, `PooledStatementTimeoutIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/PooledStatementTimeoutIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/StoreResult.java (`Stored` + `insertToCommit`), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java (an injected nanosecond clock read before sending `INSERT_SHARE` and after the transaction returns, in a holder local to `store(...)`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeService.java (threshold `Duration`; `Stored` at or above it → `visibilityOverrun()`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeObserver.java (+ `visibilityOverrun()`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserver.java (+ `resultsstore.intake.visibility.overrun`, registered at start), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java (clock `System::nanoTime`; threshold = transaction + 2 × statement + idle-in-transaction from `IntakeProperties`); every `StoreResult.Stored` call site and exhaustive switch updated; and the intake timeouts (E3): src/main/resources/application.yaml (`resultsstore.intake.store.statement-timeout` default `20s` → `10s`, `lock-timeout` default `10s` → `5s`, with their comments), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeProperties.java (`Store`'s `@DefaultValue`s `20s` → `10s` and `10s` → `5s`; the existing rules unchanged, lock ≤ statement included), src/main/java/uk/gov/hmcts/cp/resultsstore/config/StatementTimeoutBackstop.java (new: a static `BeanPostProcessor` bean that sets the `HikariDataSource`'s `connectionInitSql` to `SET statement_timeout = '<n>ms'`, n = `resultsstore.intake.store.statement-timeout` in milliseconds, read through Boot's `Binder` from the same property; registered in `IntakeConfig` unconditionally, not behind the subscription switch)
  - Cases: `IntakeServiceTest`: `a_stored_share_at_the_threshold_should_count_one_overrun`; `a_stored_share_below_the_threshold_should_count_none`; `a_duplicate_or_refused_copy_should_never_count`. `JdbcShareStoreIT`: `stored_should_carry_the_time_from_sending_the_insert_to_the_commit_returning` (a stepping clock gives an exact value); `a_slow_commit_should_be_measured` (a test-only `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `hearing_share` running `pg_sleep(1.1)`, so the sleep runs at `COMMIT` → at least 1.1 s, compared with a 1 s threshold through `IntakeService`). `MicrometerIntakeObserverTest`: the full registered set includes the overrun counter at zero; `visibility_overrun_should_move_by_one`. `IntakeConfigTest`: `the_overrun_threshold_should_be_transaction_plus_twice_statement_plus_idle` (90 s at the defaults; 45 s for 30 s / 5 s / 5 s); `the_backstop_bean_should_exist_with_the_subscription_off`. `ConfigurationValidationTest`: `the_intake_store_defaults_should_be_60s_10s_5s_10s` (transaction, statement, lock, idle-in-transaction); `a_lock_timeout_above_the_statement_timeout_should_still_stop_the_service`. `StatementTimeoutBackstopTest`: `the_init_sql_should_be_set_statement_timeout_in_milliseconds_of_the_intake_property` (`10s` → `SET statement_timeout = '10000ms'`; `1m` → `'60000ms'`; `500ms` → `'500ms'`); `a_non_hikari_data_source_should_be_left_alone`; `an_existing_init_sql_should_stop_the_service_naming_the_property` (two sources would drift). `PooledStatementTimeoutIT` (Testcontainers, full context): `a_pooled_connection_should_report_the_intake_statement_timeout` (`SHOW statement_timeout` outside any store transaction = `10s` at the default); `a_custom_intake_statement_timeout_should_reach_the_pool` (`resultsstore.intake.store.statement-timeout=7s` → `7s`); `the_store_transactions_own_setting_should_still_win_inside_it` (`set_config(..., true)` applies inside the transaction and the session value returns after it).
  - Notes: the limitation (a commit the client never sees return is not counted) is stated in contracts/metrics.md; no test can produce it. The research R4 proof is re-derived for 10 s / 5 s (90 s); the backstop is not part of it. Client-side timeouts are never part of the bound.
  - Covers: FR-017 (threshold half), FR-020, FR-061, FR-062; SC-009, SC-015.
  - Done when: the seven test classes and every changed call site green; the gate green.
  - Built: `StoreResult.Stored` + `insertToCommit` (`Duration`) and `withInsertToCommit`; `JdbcShareStore` gains a
    five-argument constructor with a `LongSupplier` nanosecond clock (the four-argument one passes
    `System::nanoTime`, so the suites that build a store keep their call); the clock is read into an
    `AtomicLong` local to `store(...)` just before `INSERT_SHARE` is sent and again after the transaction
    returns, and only a `Stored` result carries the difference; `JdbcShareStore.Timeouts.DEFAULTS` follows the
    new defaults (5 s lock, 10 s statement, 10 s idle). `IntakeService` takes the threshold as a ninth
    argument (`overrunThreshold()` exposes it to the wiring tests) and calls `IntakeObserver.visibilityOverrun()`
    after the commit when `insertToCommit` is at or above it. `MicrometerIntakeObserver` registers
    `resultsstore.intake.visibility.overrun` at start. `IntakeProperties.Store` defaults 5 s lock / 10 s
    statement, and `Store.visibilityBound()` = transaction + 2 × statement + idle-in-transaction, which
    `IntakeConfig` passes as the threshold (T009 replaces it with the effective lag). `StatementTimeoutBackstop`
    (a `BeanPostProcessor`, after initialisation, before the pool starts) binds `resultsstore.intake` through
    Boot's `Binder` (`bindOrCreate`, so the record's own defaults apply) and sets the `HikariDataSource`'s
    `connectionInitSql`; any other data source is left alone; an init SQL already set stops the service
    naming `spring.datasource.hikari.connection-init-sql` (never its value). Registered as a static bean in
    `IntakeConfig`, unconditionally. `application.yaml`: the two defaults with their comments.
  - ORCHESTRATOR ADDITION (Flyway): the pool-wide `statement_timeout` would bind Flyway too, and a
    session-level `SET` on a pooled connection would go back to the pool and undo the backstop. So
    `application.yaml` gives Flyway its own unpooled connection (`spring.flyway.user:
    ${spring.datasource.username}`, `password: ${spring.datasource.password:}`: with a Flyway user set, Boot
    4.1's `FlywayAutoConfiguration` builds a `SimpleDriverDataSource` derived from `spring.datasource`, read from
    the jar with `javap`) and `spring.flyway.init-sqls: SET statement_timeout =
    '${RESULTSSTORE_FLYWAY_STATEMENTTIMEOUT:0}'` (a PostgreSQL duration; `0` = no limit). Recorded in
    contracts/configuration.md *Flyway's own connection and statement timeout*; contracts/schema.md rule 7
    reworded to match (it said a migration sets `SET LOCAL` itself).
  - Tests: as listed, plus `IntakeServiceTest.VisibilityOverrun` (`a_stored_share_above_the_threshold_should_count_
    one_overrun`, `the_threshold_should_be_observable_to_the_wiring`), `JdbcShareStoreIT.a_duplicate_should_not_
    read_the_clock_after_the_commit_as_a_stored_share`, `StatementTimeoutBackstopTest.the_init_sql_should_follow_
    the_intake_default_when_the_property_is_unset` and a `PT7S` row, `FlywayMigrationIT.flyway_should_migrate_on_
    its_own_connection_with_the_lifted_statement_timeout` (Flyway's data source is not the Hikari pool; its init
    SQL is `SET statement_timeout = '0'`; a migration of a fresh schema with the context's Flyway configuration
    reads `statement_timeout` `0` in a `BEFORE_MIGRATE` callback). `PooledStatementTimeoutIT` borrows every pooled
    connection at once and reads each one (all `10s`), so a connection Flyway had altered would show; its
    custom-value case is a `@Nested` class with `@TestPropertySource`. `a_slow_commit_should_be_measured` scopes
    its test-only deferred constraint trigger to the test's hearing (`WHEN (NEW.hearing_id = …)`) and drops it in
    a `finally`. Every existing test that named the old defaults was updated: `ConfigurationValidationTest`
    (`defaults_should_be_those_of_the_contract`; the refusal row `transaction-timeout=19s` → `9s`; the boundary
    rows `lock-timeout=20s` → `10s`, `transaction-timeout=20s` → `10s`); the 17 `new Stored(…)` in
    `IntakeServiceTest` and 2 in `JdbcShareStoreIT` (the two equality checks now ignore `insertToCommit`).
    `StoreTimeoutIT` keeps its own short values and is unchanged.
  - RED (seams: `Stored` with the component, the store returning `Duration.ZERO`, `IntakeService` holding but not
    using the threshold, `IntakeConfig` passing `Duration.ZERO`, `visibilityOverrun()` doing nothing and
    registering nothing, `StatementTimeoutBackstop` passing every bean through and not registered, defaults
    unchanged, no Flyway settings). Run per method (`failFast`): `IntakeServiceTest$VisibilityOverrun.a_stored_
    share_at_the_threshold_should_count_one_overrun() FAILED` `Verification in order failure Wanted but not
    invoked: observer.visibilityOverrun();`; `JdbcShareStoreIT.stored_should_carry_the_time_from_sending_the_
    insert_to_the_commit_returning() FAILED` `expected: 0.25S but was: 0S`; `JdbcShareStoreIT.a_slow_commit_
    should_be_measured() FAILED` `Expecting actual: 0S to be greater than or equal to: 1.1S`;
    `MicrometerIntakeObserverTest.visibility_overrun_should_move_by_one() FAILED` `MeterNotFoundException: … No
    meter with name 'resultsstore.intake.visibility.overrun' was found.`; `IntakeConfigTest.the_overrun_threshold_
    should_be_transaction_plus_twice_statement_plus_idle() FAILED` `expected: 1M30S but was: 0S`;
    `IntakeConfigTest.the_backstop_bean_should_exist_with_the_subscription_off() FAILED` `… to have a single bean
    of type: <…StatementTimeoutBackstop> but found no beans of that type`; `ConfigurationValidationTest.the_intake_
    store_defaults_should_be_60s_10s_5s_10s() FAILED` `Expecting actual: [1M, 20S, 10S, 10S] to contain exactly
    (and in same order): [1M, 10S, 5S, 10S]`; `StatementTimeoutBackstopTest.an_existing_init_sql_should_stop_the_
    service_naming_the_property() FAILED` `Expecting code to raise a throwable.`; `PooledStatementTimeoutIT.a_
    pooled_connection_should_report_the_intake_statement_timeout() FAILED` `Expecting ArrayList: ["0", "0", …]
    to contain only: ["10s"]`; `FlywayMigrationIT.flyway_should_migrate_on_its_own_connection_with_the_lifted_
    statement_timeout() FAILED` `[Flyway's data source] Expecting actual: HikariDataSource (HikariPool-1) not to
    be an instance of: com.zaxxer.hikari.HikariDataSource`. `a_lock_timeout_above_the_statement_timeout_should_
    still_stop_the_service` was first written with `6s`, which is legal at the new 10 s statement default; it was
    corrected to `11s` and passes, pinning the rule.
  - GREEN: `IntakeServiceTest` 42 (`VisibilityOverrun` 5), `JdbcShareStoreIT` 29, `MicrometerIntakeObserverTest`
    28, `IntakeConfigTest` 10, `ConfigurationValidationTest` 65, `StatementTimeoutBackstopTest` 7,
    `PooledStatementTimeoutIT` 3, `FlywayMigrationIT` 128, `StoreTimeoutIT` 6, 0 failures; the gate green (1506
    tests passed, 0 skipped; JaCoCo report line 0.9954, branch 0.9734).
  - Gate round 1 (Codex HIGH, fractional timeouts): `IntakeProperties.Store` now refuses a transaction timeout
    that is not whole seconds and a lock, statement or idle-in-transaction timeout that is not whole
    milliseconds (`Rules.whole`, checked before the other rules), so `visibilityBound()` is what Spring and
    PostgreSQL enforce (`IntakeConfig.seconds` would run `500ms` as 1 s; `JdbcShareStore.milliseconds` and
    `StatementTimeoutBackstop` would send `500us` as `0ms`, no limit). Recorded in contracts/configuration.md.
    Tests: `ConfigurationValidationTest.an_intake_store_timeout_the_enforcing_side_would_round_should_stop_the_
    service_starting` (6 rows: `500ms` and `60500ms` transaction; `10500us` and `500us` statement; `4999999ns`
    lock; `500us` idle) and `whole_second_and_whole_millisecond_store_timeouts_should_be_accepted` (1 s / 1 ms /
    1 ms / 999 ms). RED: `[1] settings = "transaction-timeout=500ms;statement-timeout=200ms;lock-timeout=100ms;
    idle-in-transaction-timeout=100ms" … FAILED` `Expecting: <Started application …> to have failed but context
    started successfully`. GREEN: `ConfigurationValidationTest`, `IntakeConfigTest`, `StatementTimeoutBackstopTest`
    0 failures.
  - Gate round 1 (QA LOW): `StatementTimeoutBackstopTest.a_blank_init_sql_should_be_treated_as_unset` (`""`
    and `"  "`) pins the existing `isBlank()` branch: an empty or blank `connection-init-sql` counts as unset and
    is replaced; contracts/configuration.md *Pool backstop* says so. Passes at once (behaviour T008 built).
    plan.md Risk 2 reworded (spec-validator LOW): Flyway is on its own connection, not bound by the backstop.

---

## Phase C: serving, wiring, end to end, documents (T009–T012)

**Purpose**: wire the read beans first, so the controllers that follow never break a context; then the
controllers, advice and `304`; then the API end to end with authorisation and audit on; then the smoke
and the documents. Depends on phase B.

**Independent test**: `ConfigurationValidationTest` and `ReadApiConfigTest` prove the settings;
the controller slices prove mapping, headers and errors; `ReadApiIT` proves US1–US7 end to end; `AuditIT`
pins the audit behaviour; the smoke proves it in the compose stack.

- [X] T009 [US1] [US7] Test first: `ConfigurationValidationTest` (extended; stub `DataSource`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java, `ReadApiConfigTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ReadApiConfigTest.java, `IntakeConfigTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfigTest.java, `SweepSchedulingConfigTest` (stub `DataSource` where it loads `ReadApiConfig`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfigTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/config/ReadApiProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/ReadApiConfig.java (unconditional beans: a read `JdbcTemplate` with the query timeout, `JdbcShareQueries`, `ShareReadService`, `MicrometerReadObserver`; the effective lag as a bean IntakeConfig can take), src/main/java/uk/gov/hmcts/cp/resultsstore/config/Rules.java (+ `atLeast(name, value, boundName, bound)`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java (overrun threshold = the effective lag), src/main/resources/application.yaml (the `resultsstore.read.*` block of contracts/configuration.md); and the contract jar (research R23 C1, C4): test first `OpenApiContractDriftTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiContractDriftTest.java, then gradle/libs.versions.toml (`api-results-store = "rs-69080b1"` under `[versions]`; `api-results-store = { module = "uk.gov.hmcts.cp:api-cp-crime-results-store", version.ref = "api-results-store" }` under `[libraries]`), build.gradle (`apply from: "$rootDir/gradle/apispec-validation.gradle"` beside the other `apply from` lines; `configurations { apiSpec; implementation.extendsFrom apiSpec }`; `apiSpec libs.api.results.store` in `dependencies`; no new repository: `hmcts-lib` is already in gradle/repositories.gradle, anonymous), .github/workflows/ci-released.yml (a `validate-api-spec-version` job as in service-cp-crime-hearing-results-validator's: runs on a release or on `main`, checkout, JDK 25, Gradle, `./gradlew validateApiSpecVersions`; `ci-release` gains `needs: [validate-api-spec-version]`; the note saying there is no gate yet is removed). `audit.http.openapi-rest-spec` stays `results-store-openapi.yaml` and src/main/resources/results-store-openapi.yaml stays where it is (R23 C1)
  - Cases: `ConfigurationValidationTest`: `the_visibility_lag_should_default_to_transaction_plus_twice_statement_plus_idle` (90 s at the T008 defaults); `the_derived_lag_should_follow_custom_intake_values`; `a_lag_below_the_sum_should_stop_the_service_naming_the_property`; `a_lag_above_ten_minutes_should_stop_the_service`; `an_unset_lag_whose_derived_value_exceeds_ten_minutes_should_name_the_transaction_timeout`; `a_read_statement_timeout_of_zero_or_at_the_socket_timeout_should_stop_the_service`; `no_message_should_hold_a_value`. `ReadApiConfigTest`: `the_read_beans_should_exist_with_the_subscription_off`; `the_read_template_should_carry_the_statement_timeout`; `the_service_should_use_the_effective_lag`. `IntakeConfigTest`: `the_overrun_threshold_should_be_the_effective_lag` (a set lag of 200 s gives 200 s). `SweepSchedulingConfigTest`: unchanged cases green. `OpenApiContractDriftTest` (reads `openapi/openapi-spec.yml` from the test classpath, that is from the `apiSpec` jar, and `results-store-openapi.yaml` from the main resources; both parsed with swagger-parser): `the_jar_spec_should_be_on_the_classpath_exactly_once`; `the_paths_should_be_identical` (operations, parameters, request bodies, responses, response headers and content compared as parsed objects); `the_components_should_be_identical` (schemas, parameters, responses, headers); `the_tags_should_be_identical`; `info_and_servers_should_not_be_compared` (a document differing only in `info.version`, `info.contact`, `info.license` and `servers` passes); `a_changed_schema_should_fail_naming_the_path_or_component_only` (a copy with one property type changed fails, and the failure message names the path or component, never a document's text). RED: a seam that compares nothing. `validateApiSpecVersions` run by hand: refuses `rs-69080b1` (quoted), passes when the version is set to `0.0.1` locally and put back.
  - Covers: FR-017, FR-018, FR-021 (rule half), FR-045, FR-056, FR-063 (dependency, drift test, release gate); SC-008, SC-015 (lag half).
  - Done when: the five test classes green; every context test green; `./gradlew dependencies --configuration apiSpec` resolves `uk.gov.hmcts.cp:api-cp-crime-results-store:rs-69080b1` from `hmcts-lib` with no credentials; the gate green.
  - Built: `ReadApiProperties` (`pull.visibilityLag`, `null` when unset or empty; `statementTimeout` 5 s; each
    above zero), `VisibilityLag` (a record bean: the effective lag), `ReadApiConfig` (unconditional; its
    constructor works out the effective lag, the set value or `IntakeProperties.Store.visibilityBound()`, and
    checks lag ≥ the sum (`Rules.atLeast(name, value, boundName, bound)`, new), lag ≤ 10 min (when unset, the
    message starts with `resultsstore.intake.store.transaction-timeout + 2 x …`), and the read statement timeout
    below the socket timeout (`IllegalStateException`, as `IntakeConfig`'s); beans `VisibilityLag`,
    `JdbcShareQueries`, `MicrometerReadObserver` (as `ReadObserver`), `ShareReadService`). The read
    `JdbcTemplate` is built inside the `JdbcShareQueries` bean (`ReadApiConfig.readTemplate`, query timeout in
    whole seconds rounded up), **not** exposed as a bean: a second `JdbcTemplate` bean would make Boot's own
    `JdbcTemplate` back off and put the read timeout under intake's `JdbcClient`. `IntakeConfig.intakeService`
    takes the `VisibilityLag` as the overrun threshold. `ShareReadService.visibilityLag()` exposes the lag to the
    wiring test. `application.yaml` gains the `resultsstore.read.*` block. Rule failures from `Rules` are
    `IllegalArgumentException`s (as every other settings rule; contracts/configuration.md said
    `IllegalStateException`, corrected in T012). The contract jar: `gradle/libs.versions.toml`
    `api-results-store = "rs-69080b1"` (orchestrator ruling: `rs-69080b1` already carries the payload change of
    T010's api half, `getSharePayload` returning `ResponseEntity<byte[]>`; it supersedes `rs-2c5bc08`, and
    plan.md, research R23 and contracts/configuration.md now say so), `build.gradle` (`apispec-validation.gradle`
    applied, `configurations { apiSpec; implementation.extendsFrom apiSpec }`, `apiSpec libs.api.results.store`),
    `.github/workflows/ci-released.yml` (`validate-api-spec-version` as the validator's; `ci-release` needs it; the
    "no gate yet" note removed). Because the pinned jar already has the payload as `type: string`,
    `format: binary`, the service's `results-store-openapi.yaml` follows it here (description as the jar's), not
    in T010. `audit.http.openapi-rest-spec` unchanged.
  - Extra cases: `ConfigurationValidationTest.a_lag_from_the_sum_to_ten_minutes_should_be_taken_as_set` (90 s,
    200 s, 10 m), `a_lag_of_zero_should_stop_the_service`, `a_read_statement_timeout_below_the_socket_timeout_or_
    with_none_should_be_accepted`; `OpenApiContractDriftTest` also compares `components.requestBodies`.
  - `./gradlew dependencies --configuration apiSpec`: `uk.gov.hmcts.cp:api-cp-crime-results-store:rs-69080b1`
    resolved from `hmcts-lib`, no credentials. `validateApiSpecVersions` by hand: refuses the draft
    (`apiSpec contains non-fixed versions: uk.gov.hmcts.cp:api-cp-crime-results-store:rs-69080b1 Release builds
    require fixed versions (X.Y.Z).`); with the version set to `0.0.1` locally, `apiSpec dependency version is a
    valid fixed release version.`; put back.
  - RED (seams: `ReadApiConfig` with the beans but no checks and the lag always the derived sum; `readTemplate`
    with no query timeout; `Rules.atLeast` doing nothing; `IntakeConfig` still passing `visibilityBound()`; the
    drift test's comparison returning no difference). Run without fail-fast: `ReadApiConfigTest.the_read_template_
    should_carry_the_statement_timeout() FAILED` `expected: 5 but was: -1`; `the_service_should_use_the_effective_
    lag() FAILED` `expected: 3M20S but was: 1M30S`; `IntakeConfigTest.the_overrun_threshold_should_be_the_
    effective_lag() FAILED` `expected: 3M20S but was: 1M30S`; `OpenApiContractDriftTest.a_changed_schema_should_
    fail_naming_the_path_or_component_only() FAILED` `Expecting actual: [] to contain exactly (and in same order):
    ["paths /results-store/v1/shares/{shareId}", "components.schemas ShareSummary"]`;
    `ConfigurationValidationTest.a_lag_below_the_sum_should_stop_the_service_naming_the_property`,
    `a_lag_above_ten_minutes_should_stop_the_service`, `an_unset_lag_whose_derived_value_exceeds_ten_minutes_
    should_name_the_transaction_timeout`, the statement-timeout rows `30s` and `31s` and the four
    `no_message_should_hold_a_value` rows `FAILED` `Expecting: <Started application …> to have failed but context
    started successfully`; `a_lag_from_the_sum_to_ten_minutes_should_be_taken_as_set` rows `200s`, `10m` `FAILED`
    (15 failed of 113). With the comparison real and the service document not yet changed:
    `the_paths_should_be_identical() FAILED` `[paths that differ] Expecting empty but was:
    ["paths /results-store/v1/shares/{shareId}/payload"]` (the payload schema), green once the document follows.
  - GREEN: `ConfigurationValidationTest` 89, `ReadApiConfigTest` 3, `IntakeConfigTest` 11,
    `SweepSchedulingConfigTest` 4, `OpenApiContractDriftTest` 6, `OpenApiDocumentTest` 6, 0 failures; the gate
    green (1555 tests passed, 0 skipped; JaCoCo report line 0.9970, branch 0.9766).
  - Gate round 1 (Codex MEDIUM, fractional read timeouts): the socket-timeout rule now compares the timeout
    the read template enforces (whole seconds, rounded up; one `queryTimeoutSeconds` used by both), so
    `29500ms` or `29001ms` under the default 30 s socket timeout is refused; `28500ms` (runs as 29 s) is
    accepted. contracts/configuration.md says so. RED
    (`ConfigurationValidationTest.a_read_statement_timeout_of_zero_or_at_the_socket_timeout_should_stop_the_service`
    row `29500ms`): `Expecting: <Started application …> to have failed`. GREEN: `ConfigurationValidationTest` 91,
    `ReadApiConfigTest` 3, 0 failures.

- [X] T010 [US1] [US2] [US3] [US4] [US6] [US7] Api repo first (research R23 C3, C4): in hmcts/api-cp-crime-results-store, a pull request that changes the payload `200` response's `application/json` schema to `type: string`, `format: binary` (description kept) and adds `"file": "byte[]"` to `typeMappings` in gradle/openapi.gradle, with a CHANGELOG line and an `OpenApiObjectsTest` case that `getSharePayload` returns `ResponseEntity<byte[]>`; merged and fast-forwarded to `team/rs`; its `rs-<sha7>` draft published; then gradle/libs.versions.toml pinned to that draft and the same schema change made in src/main/resources/results-store-openapi.yaml (the drift test of T009 is red until both are made). Then test first: `ShareParametersTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ShareParametersTest.java, `ShareParametersInterceptorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ShareParametersInterceptorTest.java, `InstantFormatTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/InstantFormatTest.java, `ShareResponseMapperTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ShareResponseMapperTest.java, `SharesControllerTest` (`@WebMvcTest(SharesController.class)` slice) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/SharesControllerTest.java, `SharePayloadControllerTest` (the same slice, payload operation) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/SharePayloadControllerTest.java, `HearingDaySharesControllerTest` (the same slice, day operation) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/HearingDaySharesControllerTest.java, `ContentNegotiationTest` (the slice with `ActionRequestWrapper` applied by a test filter for the route) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ContentNegotiationTest.java, `ReadApiExceptionHandlerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReadApiExceptionHandlerTest.java, `ReadMetricsInterceptorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReadMetricsInterceptorTest.java, `OpenApiContractTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiContractTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/api/ShareParameters.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ShareParametersInterceptor.java (a `HandlerInterceptor` for the read routes: `preHandle` runs `ShareParameters` over the raw query string and the URI template variables before Spring binds the generated method's typed arguments, and writes the bounded refusal through the existing problem writer), src/main/java/uk/gov/hmcts/cp/resultsstore/api/InstantFormat.java (+ the Jackson `Instant` serializer it backs, registered on the MVC JSON mapper by a `JsonMapperBuilderCustomizer` bean in src/main/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfig.java), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ShareResponseMapper.java (`ShareView` → `ShareSummary` and `KeyDetails`; `application.PullPage` → model `PullPage`; `application.SearchPage` → model `SearchPage`; the day's views → `DayVersions`; every field set, nulls kept), src/main/java/uk/gov/hmcts/cp/resultsstore/api/PayloadResponses.java (`ServedPayload` → `ResponseEntity<byte[]>`: `ok().eTag(…)`, `contentType(APPLICATION_JSON)`, `contentLength`, `Cache-Control: no-store`, the `Results-Store-*` headers, body the exact bytes), src/main/java/uk/gov/hmcts/cp/resultsstore/api/SharesController.java (`@RestController`, `implements SharesApi`; overrides `pullOrSearchShares`, `getShare`, `getSharePayload` and `listHearingDayShares` with the generated signatures, adds no `@RequestMapping` of its own; each override builds the query from its typed arguments, calls `ShareReadService` and returns through the mapper or `PayloadResponses`; every `200` sets `Content-Type: application/json`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReadApiExceptionHandler.java (extends `ResponseEntityExceptionHandler`, overrides `handleExceptionInternal`; a `MethodArgumentTypeMismatchException` maps to the parameter's own reason), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReadMetricsInterceptor.java (endpoint from the `ApiRoute` request attribute; outcome from the status; duration from `preHandle` to `afterCompletion`), the registration of both interceptors in src/main/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfig.java (metrics first, so a parameter refusal is counted as `bad_request`). No hand-written response records: the generated models are the responses.
  - Generated signatures implemented (from `rs-69080b1`, which already carries the api change above): `ResponseEntity<PullOrSearchShares200Response> pullOrSearchShares(Long storedAfterSeq, Integer limit, String dayYouthSeen, UUID courtCentreId, LocalDate sharedDayFrom, LocalDate sharedDayTo, Instant sharedFrom, Instant sharedTo, Boolean latestOnly, String cursor)`; `ResponseEntity<ShareSummary> getShare(UUID shareId)`; `ResponseEntity<byte[]> getSharePayload(UUID shareId, String ifNoneMatch)` (was `ResponseEntity<Map<String, Object>>`); `ResponseEntity<DayVersions> listHearingDayShares(UUID hearingId, LocalDate hearingDay)`. Each mapping is `produces = { "application/json", "application/problem+json" }`.
  - Cases: `ShareParametersTest`: `an_unknown_parameter_should_be_unknown_parameter` (`storedAfterseq`); `a_repeated_parameter_should_be_repeated_parameter`; `pull_with_a_search_parameter_should_be_conflicting_parameters`; `neither_mode_complete_should_be_missing_parameter`; one row per invalid reason of contracts/read-api.md §6; `day_youth_seen_false_should_be_refused_on_pull_and_accepted_on_search`; `search_should_accept_the_day_form_or_the_time_form`; `a_day_parameter_with_a_time_parameter_should_be_conflicting_parameters`; `an_incomplete_form_should_be_missing_parameter`; `an_instant_with_an_offset_or_seven_fraction_digits_should_be_invalid_shared_from_or_to`; `a_time_parameter_on_pull_should_be_conflicting_parameters`; `a_non_canonical_uuid_should_be_invalid_even_though_uuid_from_string_takes_it` (`1-1-1-1-1`); `latest_only_yes_on_or_1_should_be_invalid_latest_only`. `ShareParametersInterceptorTest`: `a_refused_parameter_should_be_answered_before_binding_and_the_handler_never_called`; `the_path_variables_should_be_checked_from_the_raw_template_values`; `a_valid_request_should_pass_to_the_handler`; `no_refusal_body_should_echo_a_value`. `InstantFormatTest`: `six_fraction_digits_should_always_be_written` (zero fraction, millis, nanos truncated), `utc_with_z`, `the_registered_serializer_should_write_every_model_instant_this_way` (`visibleUpTo`, `sharedTime`, `storedAt`). `ShareResponseMapperTest`: `every_share_view_field_should_reach_the_model`; `key_details_should_be_null_when_failed`; `pull_and_search_pages_should_map_every_field`; `the_day_versions_should_keep_their_order`. `SharesControllerTest`: `the_controller_should_be_the_only_sharesapi_implementation_and_register_each_mapping_once`; `pull_should_map_every_parameter_and_field`; `search_should_map_every_parameter_and_field`; `the_item_should_write_every_field_with_nulls_present` (JSON keys checked, `keyDetails` null when `FAILED`); `pull_and_search_should_be_written_as_their_own_page_shape` (no type property); `no_problem_body_should_echo_a_caller_value`; `every_problem_body_should_read_as_the_generated_problem_detail` (the four fields deserialised into the model). `SharePayloadControllerTest`: `the_body_should_be_byte_identical_to_the_service_bytes`; `the_body_should_be_written_by_the_byte_array_converter` (no `Accept-Ranges`; a `Range` header still gives `200` and the whole body); `the_body_should_have_no_metadata_key` (E8); `the_etag_should_be_strong_and_quoted`; `exactly_one_etag_header_should_be_sent_on_200_and_on_304`; `identity_enrichment_and_form_headers_should_be_present`; `cache_control_should_be_no_store`; `content_type_should_be_application_json_without_charset`; `if_none_match_should_give_304_for_strong_weak_list_and_star`; `a_stale_if_none_match_should_give_200`; `no_content_encoding_and_content_length_should_equal_the_byte_count`. `HearingDaySharesControllerTest`: `should_list_in_shared_at_order`; `an_empty_day_should_be_404_hearing_day_not_found`. `ContentNegotiationTest` (research R23 C5; each route): `accept_json_star_absent_and_vendor_should_give_200_application_json`; `accept_problem_json_should_give_200_with_content_type_application_json`; `accept_text_html_should_give_406_not_acceptable_with_the_bounded_body`; `an_error_should_be_application_json_whatever_accept_says`. `ReadApiExceptionHandlerTest`: `each_spring_mvc_exception_type_should_render_the_four_fields_only` (method not supported → `405`, not acceptable → `406`, no resource → `404 route_not_found`, missing parameter, message not readable → `400 bad_request`); `a_type_mismatch_should_give_the_parameters_own_reason` (`shareId` → `invalid_share_id`, `hearingDay` → `invalid_hearing_day`, …); `a_connection_failure_or_query_timeout_should_be_503_store_unavailable_with_retry_after_5`; `any_other_exception_should_be_500_internal_error_logged_by_class_without_its_message` (`support/CapturedLog`). `ReadMetricsInterceptorTest`: `status_should_map_to_outcome` (200 `ok`, 304 `not_modified`, 400, 405, 406 and 415 `bad_request`, 404 `not_found`, 503 `unavailable`, 500 `failed`; the table in contracts/metrics.md); `the_endpoint_should_come_from_the_route_attribute`; `a_request_with_no_route_attribute_should_record_nothing`; `a_parameter_refusal_should_be_counted_as_bad_request`. `OpenApiContractTest`: `every_controller_mapping_should_be_described`; `every_described_route_should_have_a_controller_mapping`; `every_api_route_should_have_a_drl_rule_and_a_controller_mapping`; `every_path_parameter_should_be_named_as_the_controller_names_it`; `every_sharesapi_operation_should_be_overridden` (no generated default answering `501`).
  - Notes: no manual `checkNotModified`; `ResponseEntity.ok().eTag(…).body(bytes)` (research R11) with a `byte[]` body, never a `String`, `Map` or `Resource` (research R23 C3). Single exit where PMD needs it. `spring.mvc.problemdetails.enabled` stays unset. Generated bean validation stays off; every check is the service's (C5).
  - Covers: FR-002–FR-008, FR-026 and FR-027 (parameter half), FR-031–FR-037, FR-039, FR-042–FR-044, FR-053 (both ways), FR-055 (requests and duration), FR-063 (the controller implements the published interface; the payload change made api repo first); SC-003, SC-006 (slice half).
  - Done when: the api pull request merged and its `rs-<sha7>` draft published (run URL recorded under the task); the service pinned to it; the eleven test classes and `OpenApiContractDriftTest` green; the gate green.
  - Api half: already made before this phase, as api commit `69080b1` ("feat(spec): serve the share payload as
    exact bytes", on `team/rs`), published as the draft `rs-69080b1` (Azure Artifacts `hmcts-lib`; resolved
    anonymously by T009). The service was pinned to it, and its own document followed, in T009, so the drift
    test is green from T009 on. No api pull request was opened by this phase; the run URL is the api repo's: `rs-69080b1` was published by
    the `team/rs` push run https://github.com/hmcts/api-cp-crime-results-store/actions/runs/37161902049 (Build
    and Publish (DRAFT CANDIDATE VERSION), success; the `main` run for the same commit is 37161898371).
  - Built: `api/ShareParameters` (the strict rules on the raw query and the path variables, single-exit
    `Optional` chain: path variables, unknown, repeated, conflicting, missing, then each value in the order of
    the contract's tables; the query decoded with `filters/QueryParameterNames.decode`, now public, so a
    malformed escape in a name is `unknown_parameter`; ranges and the cursor's content stay the service's),
    `api/ShareParametersInterceptor` (route from the action filter's attribute, or resolved from method and path
    where no filter ran; refuses through `RefusalWriter`), `api/InstantFormat` (+ its Jackson `Instant`
    serializer module), `api/ShareResponseMapper`, `api/PayloadResponses` (`served(…)`: `ok().eTag(…)`,
    `application/json`, `Content-Length`, `no-store`, the six `Results-Store-*` headers, the exact bytes;
    `answer(…, ifNoneMatch)`: `If-None-Match: *` (alone or in a list) answered `304` with the `ETag` alone,
    because Spring's own check honours `*` only for unsafe methods, found by
    `if_none_match_should_give_304_for_strong_weak_list_and_star`; every other `If-None-Match` is Spring's),
    `api/SharesController` (the one `SharesApi` implementation; the four generated signatures of `rs-69080b1`),
    `api/ReadApiExceptionHandler` (global `@RestControllerAdvice`, not limited to the controller, so a `406` or
    `405` raised while choosing the handler, which has no handler type, is answered by it too; extends
    `ResponseEntityExceptionHandler`, `handleExceptionInternal` writes the generated `ProblemDetail` as
    `application/problem+json`, keeping Spring's headers such as `Allow`; a type mismatch maps to the
    parameter's own reason; `DataAccessResourceFailureException` (so `CannotGetJdbcConnectionException`) and
    `QueryTimeoutException` give `503 store_unavailable` with `Retry-After: 5`; any other `RuntimeException`,
    `EnvelopeMetadata.UnreadablePayloadException` included, gives `500 internal_error`, logged by class with
    `shareId` in the MDC only when the path's value is canonical), `api/ReadMetricsInterceptor`
    (`ReadOutcome.forStatus`, new in `domain/ReadOutcome`), and `config/ReadApiWebMvcConfig` (a
    `WebMvcConfigurer`: both interceptors on `/results-store/v1/**`, metrics first, and the
    `JsonMapperBuilderCustomizer` with the instant module). Placed in its own configuration class rather than
    in `ApiWebConfig` (the change that alters least): a `@WebMvcTest` slice picks up a `WebMvcConfigurer`
    without `ApiWebConfig`'s filters, error page and authorisation check.
  - Notes: Jackson 3 writes the generated models' members in alphabetical order (its default; the models carry
    no `@JsonPropertyOrder`), so a problem body from the advice is `{"reason","status","title","type"}` while one
    from a filter is `{"type","title","status","reason"}`. JSON member order is not part of the contract; the
    tests compare members as sets or parsed trees. `ServedPayload` bytes are still recorded by the service before
    Spring decides a `304` (T007 behaviour, unchanged). Spring binds `sharedFrom=…T17:00:00.5Z` with
    `@DateTimeFormat(iso = DATE_TIME)` as the instant (pinned by `search_should_map_every_parameter_and_field`).
    `24:00:00` is a valid ISO-8601 time and `Instant.parse` takes it, so it is accepted; the refusal row uses
    `25:00:00`.
  - Extra cases: `ShareParametersTest`: `a_name_with_a_malformed_escape_should_be_unknown_parameter`,
    `an_encoded_name_should_be_read_decoded`, `empty_pairs_should_be_skipped`, `pull_should_accept_its_own_
    parameters`, `the_other_routes_should_take_no_query`, `an_instant_with_z_and_up_to_six_fraction_digits_should_
    be_accepted`, `a_bad_hearing_day_should_be_invalid_hearing_day`, `the_path_should_be_checked_before_the_query`;
    `ShareParametersInterceptorTest.the_route_should_be_resolved_from_method_and_path_when_no_filter_set_it`,
    `a_path_no_route_serves_should_pass_to_the_handler`; `SharesControllerTest.pull_should_default_the_limit_and_
    pass_no_filter`; `SharePayloadControllerTest.the_arrived_text_form_should_be_named_in_its_header`;
    `HearingDaySharesControllerTest.a_bad_day_or_hearing_should_be_refused_with_its_own_reason`;
    `ContentNegotiationTest.without_the_wrapper_a_vendor_accept_would_be_refused`; `ReadApiExceptionHandlerTest`:
    `a_405_should_keep_the_allow_header`, `our_own_refusals_should_keep_their_reason`, `a_share_id_that_is_not_
    canonical_should_not_reach_the_logging_context`, `every_reason_should_be_a_reason_of_the_generated_model`;
    `ReadMetricsInterceptorTest.a_request_whose_handler_never_started_should_record_nothing`;
    `ReadOutcomeTest.status_should_map_to_outcome`.
  - RED (seams: `ReadOutcome.forStatus` always `OK`; `InstantFormat.format` = `Instant.toString()`;
    `ShareParameters.check` always empty; `ShareResponseMapper.summary` setting the share id only;
    `PayloadResponses` returning the bytes with no header; `SharesController` overriding nothing, so the generated
    defaults answer `501`; the advice's `handleExceptionInternal` delegating to Spring's; the metrics interceptor
    recording nothing). Run without fail-fast, 175 failed of 248, each an assertion, for example:
    `ReadOutcomeTest.status_should_map_to_outcome [2] 304 FAILED` `expected: NOT_MODIFIED but was: OK`;
    `InstantFormatTest [1] FAILED` `expected: "2026-10-03T09:15:00.000000Z" but was: "2026-10-03T09:15:00Z"`;
    `ShareParametersTest$Names.a_repeated_parameter_should_be_repeated_parameter() FAILED` `Expecting Optional to
    contain: REPEATED_PARAMETER but was empty.`; `ShareResponseMapperTest.key_details_should_be_null_when_failed()
    FAILED` `expected: FAILED but was: null`; `SharePayloadControllerTest.content_type_should_be_application_json_
    without_charset() FAILED` `expected: "application/json" but was: null`; `SharePayloadControllerTest.if_none_
    match_should_give_304_for_strong_weak_list_and_star [1] FAILED` `expected: 304 but was: 501`;
    `SharesControllerTest.no_problem_body_should_echo_a_caller_value [1] FAILED` `Expecting actual: ["detail",
    "instance", "status", "title"] to contain exactly …` (Spring's own body, with `detail` and `instance`);
    `HearingDaySharesControllerTest.should_list_in_shared_at_order() FAILED` `expected: 200 but was: 501`;
    `ReadApiExceptionHandlerTest.a_type_mismatch_should_give_the_parameters_own_reason [1] FAILED` `expected:
    application/problem+json but was: null`; `ReadMetricsInterceptorTest.status_should_map_to_outcome [1] FAILED`
    `Wanted but not invoked: readObserver.request(PAYLOAD, OK, PT0.0025S);`; `OpenApiContractTest.every_sharesapi_
    operation_should_be_overridden() FAILED` `[getSharePayload] expected: …SharesController but was: …SharesApi`;
    `ShareParametersInterceptorTest.the_path_variables_should_be_checked_from_the_raw_template_values() FAILED`
    `Expecting value to be false but was true`. With the real code, 11 still failed and were fixed: member order
    (Jackson 3 sorts; the tests now compare sets), `24:00:00` (row changed to `25:00:00`) and `If-None-Match: *`
    (`expected: 304 but was: 200`, fixed in `PayloadResponses.answer`).
  - GREEN: `ShareParametersTest` 63, `ShareParametersInterceptorTest` 6, `InstantFormatTest` 6,
    `ShareResponseMapperTest` 4, `SharesControllerTest` 12, `SharePayloadControllerTest` 15,
    `HearingDaySharesControllerTest` 3, `ContentNegotiationTest` 43, `ReadApiExceptionHandlerTest` 53,
    `ReadMetricsInterceptorTest` 13, `OpenApiContractTest` 9, `OpenApiContractDriftTest` 6, `ReadOutcomeTest` 21,
    0 failures; the gate green (1792 tests passed, 0 skipped; JaCoCo report line 0.9974, branch 0.9738).
  - Gate round 1 (code-reviewer MEDIUM, silent Spring 5xx): `handleExceptionInternal` now logs a Spring MVC
    `5xx` (`HttpMessageNotWritableException`, `MissingPathVariableException`, …) by exception class, with the
    canonical `shareId` in the logging context, as `internalError` does (FR-044); a `4xx` is still not logged.
    The redundant `EnvelopeMetadata.UnreadablePayloadException` entry is dropped from `internalError`'s
    `@ExceptionHandler` (`RuntimeException` covers it; code-reviewer LOW). RED
    (`ReadApiExceptionHandlerTest.a_spring_mvc_5xx_should_be_logged_by_class_with_the_share_id_and_without_its_message`,
    both rows): `Expected size: 1 but was: 0`. GREEN: `ReadApiExceptionHandlerTest` 58, 0 failures. Pinned on
    the way (qa LOW, passed when written): a `500` on a route naming no share, and a non-string `shareId`
    template value, log no `shareId`.
  - Gate round 1 (qa MEDIUM, the controller's own `dayYouthSeen` guard never ran): pinned by
    `SharesControllerTest.the_controller_should_refuse_a_bad_day_youth_seen_itself_without_calling_the_service`
    (the controller built directly over the mocked service, so the interceptor does not refuse first; kept as
    defence in depth rather than deleted). It passed when written against the built guard; RED shown by
    mutation (the `orElseThrow` replaced by `orElse(null)`): `Expecting actual throwable to be an instance of:
    …BadParameterException but was: …`. GREEN: `SharesControllerTest` 13, 0 failures.
  - Gate round 1 (code-reviewer LOW, 304 header sets): the `If-None-Match: *` `304` now carries
    `Cache-Control: no-store` as Spring's own `304` does (the contract still promises only the `ETag`). RED
    (`SharePayloadControllerTest.if_none_match_should_give_304_for_strong_weak_list_and_star` row `*`):
    `expected: "no-store" but was: null`. GREEN: `SharePayloadControllerTest` 15, 0 failures.
  - Gate round 1 (qa LOW, pinning only, no production change; passed when written): `ShareParametersTest`
    rows for a malformed escape in a value (`storedAfterSeq=%zz`, `courtCentreId=%zz…`) and a pair with no
    `=` (`storedAfterSeq`), each refused with the parameter's own reason. `ShareParametersTest` 66, 0 failures.

- [X] T011 [US1] [US2] [US3] [US4] [US5] [US6] [US7] Test first: `ReadApiIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/ReadApiIT.java, `AuditIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/AuditIT.java, `NoPayloadInLogsIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/NoPayloadInLogsIT.java, and (D-AUDIT option 4, E1) `PayloadBodyFreeAuditPayloadGenerationServiceTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/PayloadBodyFreeAuditPayloadGenerationServiceTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/filters/PayloadBodyFreeAuditPayloadGenerationService.java and its bean in src/main/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfig.java (`@ConditionalOnProperty(cp.audit.enabled=true)`), and any fix the tests find
  - Cases (`ReadApiIT`: Testcontainers Postgres, MockMvc, `authz.http.enabled=true`, WireMock usersgroups matched on `CJSCPPUID` with a "System Users" id, a "Second Line Support" id and an "Other Group" id; short intake timeouts so the lag is about 5 s; shares stored through a `JdbcShareStore` built over the context's data source with the `support/SampleShares` samples): `each_endpoint_should_serve_a_system_users_caller`; `each_endpoint_should_serve_a_second_line_support_caller`; `each_endpoint_should_refuse_a_caller_in_neither_group_403_with_a_bounded_body`; `each_endpoint_should_refuse_no_identity_401_with_a_bounded_body`; `a_spoofed_cpp_action_content_type_or_accept_should_change_no_outcome` (route × spoof); `pull_should_not_return_a_share_until_the_lag_has_passed` (Awaitility); `pull_paged_to_the_end_should_present_every_share_once_in_order`; `a_filtered_pull_should_advance_the_cursor_over_non_matching_ranges`; `visible_up_to_should_be_present_and_behind_the_database_clock_by_the_lag`; `a_failed_share_presented_by_a_not_false_pull_should_show_its_key_details_on_re_read_after_the_sweep_fixes_it_and_not_be_re_presented` (the unknown-row obligation, FR-023); `a_court_pull_should_never_present_a_failed_share_even_after_the_sweep_fills_its_court` (E5, FR-012); `search_by_day_form_and_by_time_form_should_serve_both_groups` (E6); `payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key` (E8; working copy and arrived-text form); `if_none_match_should_give_304`; `a_query_held_by_a_lock_should_give_503_store_unavailable_with_retry_after`; `an_unmapped_path_should_be_404_route_not_found_and_counted`; `the_read_meters_should_move_as_contracts_metrics_says`. `AuditIT` (`audit.http.enabled` and `cp.audit.enabled` on, `EmbeddedBrokerSupport` consuming the library's audit destination): `the_request_event_should_carry_the_derived_cpp_action_and_the_share_id_path_parameter`; `the_payload_response_event_should_carry_the_marker_and_no_payload_byte` (option 4, E1); `a_list_response_event_should_carry_the_page`; `a_304_should_publish_no_response_event`; `a_multipart_request_should_be_refused_415_and_publish_nothing`; `a_401_and_a_403_should_publish_nothing` (refused by authorisation, so they never reach the audit filter); `the_response_info_context_path_should_be_the_form_the_override_matches`; `with_the_audit_wrapper_the_payload_should_keep_content_length_and_no_chunked_encoding`. `NoPayloadInLogsIT`: `serving_a_payload_should_log_no_payload_marker_at_any_level` (root at DEBUG). `PayloadBodyFreeAuditPayloadGenerationServiceTest`: `the_payload_routes_should_be_replaced_by_the_marker`; `every_other_route_should_be_left_to_the_library`; `the_bean_should_exist_only_with_cp_audit_enabled`.
  - Notes: option 2 (a body-exclusion switch from the library owners) is asked for in parallel; when it ships the option-4 class is deleted (Deferred). The DPIA records both.
  - Reviewers: from phase B until T012's commit, the code departs from Principle VII 2.1.0 (refused requests unaudited; the payload body replaced) and from Principle II 2.1.0 (served bodies without `_metadata`). plan.md *Complexity Tracking* records the departure as the constitution allows; do not block T011 on it.
  - Covers: US1–US7 end to end; FR-012, FR-014–FR-016, FR-022–FR-030, FR-039 (no `_metadata` served), FR-044, FR-049, FR-051, FR-052; SC-001–SC-006, SC-010, SC-011.
  - Done when: the four test classes green; the gate green.
  - Built: `filters/PayloadBodyFreeAuditPayloadGenerationService` (extends the library's
    `AuditPayloadGenerationService`; `generatePayload(ResponseInfo)` swaps the body for `{"payloadOmitted":true}`
    when the request's `CPP-ACTION` in `ResponseInfo.headers()`, as the action filter derived it, is
    `results-store.get-share-payload`; every other event is the library's) and its bean in `ApiWebConfig`
    (`@ConditionalOnProperty(cp.audit.enabled=true)`, built over the library's `auditObjectMapper`; the library's
    `@ConditionalOnMissingBean` builder backs off). `support/UsersGroupsStub` (in-process usersgroups: a "System
    Users", a "Second Line Support" and an "Other Group" caller, matched on `CJSCPPUID`) for the two suites.
  - Found by `AuditIT` (facts of `cp-audit-filter-springboot` 1.0.5, pinned): `ResponseInfo.contextPath()` is the
    servlet context path without its leading slash, so `""` for this service, not the request path (every event's
    `origin` is `""` and `component` `-api`); the override therefore identifies the route by the derived
    `CPP-ACTION`, which the caller cannot choose. The library writes request headers into no event: an event
    carries the caller (`_metadata.context.user`), the correlation id, the query and path parameters and the body;
    the inner record's `name` is the `Accept` or `Content-Type` as the action filter left it (a vendor type reads
    `application/json`), so neither the derived action nor a caller-chosen name is in the event. Path parameters
    are resolved only where the document declares them inline: the day route's request event carries `hearingId`
    and `hearingDay`, but the share routes take `shareId` by `$ref` (as the contract jar does), so their request
    event carries no `shareId`. The case `the_request_event_should_carry_the_derived_cpp_action_and_the_share_id_
    path_parameter` is therefore built as three cases that pin what is true: `the_request_event_should_carry_the_
    caller_and_no_caller_chosen_name`, `the_day_route_request_event_should_carry_its_path_parameters`, `the_share_
    routes_request_event_should_not_carry_a_share_id_declared_by_reference`. Both gaps go to Deferred (a contract
    change in the api repo, or a library change; not in this phase).
  - `ReadApiIT` runs on a real server (not MockMvc: the authorisation library's `sendError` reaches `/error` only
    on a real container), intake timeouts 2 s / 1 s / 500 ms / 1 s (lag 5 s) and a 1 s read statement timeout (the
    `503` case holds `LOCK TABLE hearing_share IN ACCESS EXCLUSIVE MODE` on another connection). Extra assertions:
    the court-filtered pull from an older cursor presents the fixed share (filters at read time, reconciliation);
    the `ETag` is not, in general, the stored `payload_sha256`. `NoPayloadInLogsIT.serving_a_payload_should_log_no_payload_
    marker_at_any_level` serves both forms (working copy, and arrived text for a `\u0000` share) with root at
    DEBUG.
  - RED (seams: the override passing every response through unchanged; its bean not registered). Run without
    fail-fast: `PayloadBodyFreeAuditPayloadGenerationServiceTest.the_payload_routes_should_be_replaced_by_the_
    marker() FAILED` `Expecting value to be true but was false`; `the_bean_should_exist_only_with_cp_audit_
    enabled() FAILED` `[Bean of type <class …AuditPayloadGenerationService> from <Started application …>]` (no such
    bean); `AuditIT.the_payload_response_event_should_carry_the_marker_and_no_payload_byte() FAILED` `[List check
    single element] … Expecting value to be true but was false`; `AuditIT.the_response_info_context_path_should_be_
    the_form_the_override_matches() FAILED` `Expecting actual: …AuditPayloadGenerationService@… to be an instance
    of: …PayloadBodyFreeAuditPayloadGenerationService`. Two `ReadApiIT` fixture slips were fixed on the way (the
    checksum is on `hearing_share`, and hour `9` needed two digits); every `ReadApiIT` case then passed against
    T010's code, and `NoPayloadInLogsIT`'s new case passed at once (serving logs no content).
  - GREEN: `PayloadBodyFreeAuditPayloadGenerationServiceTest` 7, `AuditIT` 10, `ReadApiIT` 57,
    `NoPayloadInLogsIT` 4, 0 failures; the gate green (1867 tests passed, 0 skipped; JaCoCo report line 0.9974,
    branch 0.9741).
  - Gate round 1 (qa LOW, pinning only, no production change; both passed when written): `ReadApiIT`
    `a_share_between_midnight_and_one_bst_should_be_searched_on_its_london_day` (shared 23:30Z on 2 October:
    listed by the day form for 3 October with `sharedDayLondon` 2026-10-03 and `sharedDayUtc` 2026-10-02,
    absent for 2 October) and `each_day_of_a_multi_day_hearing_should_list_only_its_own_versions` (version
    numbers restart at 1 per day), driving the always-cover edges through HTTP. `ReadApiIT` 59, 0 failures.
  - Codex review (phase C), rulings by the orchestrator:
    - Fixed (MEDIUM): `ReadApiExceptionHandler.handleExceptionInternal` bypassed Spring's committed-response
      guard. On a committed response it now logs the failure by exception class (with the canonical `shareId`
      in the logging context when the path names one; never the message) and returns nothing, so nothing is
      appended. `ReadApiExceptionHandlerTest.a_committed_response_should_be_left_as_it_is_and_the_failure_logged_
      by_class` (RED: the handler returned the problem body).
    - Fixed (MEDIUM): a `406` raised while Spring MVC chooses the handler never reaches
      `ReadMetricsInterceptor.preHandle`, so `resultsstore.read.requests` did not move. The advice now counts a
      request on a matched route whose handler never started (`ReadObserver.requestWithoutHandler`: the counter
      only, no duration, as no handler started); the interceptor and the advice share one per-request marker
      (`ReadMetricsInterceptor.claimCount`), so a request is counted once. contracts/metrics.md says so. RED:
      `ContentNegotiationTest.accept_text_html_should_be_counted_once_as_bad_request` (wanted but not invoked),
      `ReadApiIT.accept_text_html_should_count_one_bad_request_and_no_duration` (expected 1.0 but was 0.0), and
      the advice, interceptor and observer unit cases.
    - Declined (HIGH, "request audit events carry the caller's own query and path values"): those values are
      the caller's own request parameters, which the audit library records by design (research R14, FR-051);
      the service never copies stored payload into them, and the no-personal-data rule concerns data the
      service holds. No code change; contracts/read-api.md §2.4 now states that request parameters a caller
      sends are recorded in the audit request event as sent.
    - Gate after the fixes: 1898 tests passed, 0 skipped; JaCoCo line 0.9974, branch 0.9738.

- [X] T012 [US1] [US2] [US3] [US5] [US7] Smoke, compose and documents. Test first: extend scripts/container-smoke.sh so it fails on the pre-003 build, with the HTTP checks of spec FR-060 after the published share is stored (pull lists the `shareId` once the derived lag has passed; one share `200`; `/payload` `200` with `sha256sum` of the body equal to the unquoted `ETag` and `jq 'has("_metadata")'` false; `If-None-Match` `304`; day versions `200`; no `CJSCPPUID` `401`; the no-group `CJSCPPUID` `403`; `/results-store/v1/anything` `404` with reason `route_not_found` and no path in the body; a vendor `Accept` on pull still `200`; `resultsstore_read_requests_total` and `resultsstore_read_refused_total` present); and the review grep (RED: the hits before the edits) for `lowest sequence number`, `still-open write`, `library's default settings`, `include-payload-body`, `caller-supplied`, `carry no request or response bodies` across `specs/`, `.specify/memory/constitution.md` and `.claude/rules/design_rules.md`; then
  - docker-compose.yml (app service: `RESULTSSTORE_INTAKE_STORE_TRANSACTIONTIMEOUT`, `…_STATEMENTTIMEOUT`, `…_LOCKTIMEOUT`, `…_IDLEINTRANSACTIONTIMEOUT` through `${VAR:-default}` with short values (transaction 6 s, statement 2 s, lock 1 s, idle-in-transaction 1 s; derived lag 11 s), contracts/configuration.md *Compose*; `RESULTSSTORE_READ_PULL_VISIBILITYLAG` left unset), docker/wiremock/mappings/identity-stub.json (a low priority, so it is the default), docker/wiremock/mappings/identity-no-group.json (new: matched on `CJSCPPUID` `11111111-1111-4111-8111-111111111111`, priority 1, groups "Other Group");
  - .specify/memory/constitution.md: version 2.1.0 → 2.2.0 (MINOR), Principle VII reworded to research R20's text (the derived action; both groups and the method-and-path match; *every request that reaches an endpoint is audited; a request refused by a filter or by authorisation is counted*; the payload marker sentence, E1, E13); Principle II's read-API sentence reworded to *The read API serves the working copy without the message envelope's metadata (`_metadata`), and the text, likewise without it, when the working copy is empty* (E8), with the arrived-text clause *and, on its own endpoint, the text as it arrived, likewise without the envelope metadata* (E2), both in 2.2.0, so T013 touches no constitution; Sync Impact Report updated (modified principles, templates checked, follow-ups); **Last Amended** set;
  - `.claude/rules/design_rules.md`: the *Pull safety* paragraph replaced by the visibility bound, 90 s (research R4, R7); *Security*: the action derived for every request with vendor media types neutralised, rules admitting "System Users" and "Second Line Support" and matching method and path, the audit wording above; the read API table: the payload row says "without `_metadata`", search gains the time form, and the arrived row is added (E2, E6, E8);
  - specs/001-share-intake/spec.md (*Out of scope* "the search indexes consumers need: spec 003" and *Assumptions* "Consumer search indexes are left to spec 003") and specs/001-share-intake/data-model.md ("spec 003 adds them with the read API"): an *Amended by spec 003* note pointing at V5; specs/001-share-intake/contracts/metrics.md *Not in 001*: a pointer to specs/003-read-api/contracts/metrics.md; specs/001-share-intake/contracts/configuration.md: an *Amended by spec 003* note on the statement (10 s) and lock (5 s) defaults and the pool backstop (FR-061, FR-062); specs/002-enrichment/spec.md FR-041, specs/002-enrichment/contracts/schema.md rule 5 and specs/002-enrichment/page-notes.md §3: an *Amended by spec 003* note: the served bytes are the working copy without `_metadata` (E8);
  - specs/003-read-api/contracts/*.md and quickstart.md checked against what was built and corrected; specs/003-read-api/page-notes.md reconciled; specs/003-read-api/plan.md constitution reference 2.2.0; specs/003-read-api/spec.md status set to Implemented (phases A to C);
  - `/speckit-analyze` (read-only) over spec.md, plan.md and tasks.md with the constitution, research, data-model and contracts as context; every CRITICAL and HIGH finding resolved, MEDIUM fixed or listed below with a reason; the result recorded under this task; `.specify/scripts/bash/check-prerequisites.sh --require-tasks --include-tasks --json` exits 0;
  - the Deferred list below, completed with anything the phases left open;
  - the contract release (research R23 C4), last, once the smoke and the documents are done: a GitHub Release `v0.2.0` of hmcts/api-cp-crime-results-store from the api `main` commit that `team/rs` was fast-forwarded to for phase C; its ci-released run publishes `0.2.0`; gradle/libs.versions.toml bumped from the `rs-<sha7>` draft to `0.2.0`; `./gradlew validateApiSpecVersions` passes (quoted); `OpenApiContractDriftTest` green against the released jar. Corrected by T013 (orchestrator ruling): there is ONE api release, `v0.2.0`, at the end of 003, covering all five operations (the arrived route of phase D included); there is no `0.3.0`. The release and the bump are the orchestrator's step after phase D, not part of T012 or T013.
  - Covers: FR-012 and FR-039 (the contract text checked against what was built), FR-021 (document half), FR-022–FR-025 and FR-040 (likewise), FR-057, FR-058, FR-059, FR-060; FR-063 (the release and the bump) is deferred to after phase D by the orchestrator's ruling (see
    Deferred); SC-012, SC-013.
  - Done when: `scripts/container-smoke.sh` prints `PASS` with every check `ok` (the RED run quoted: the HTTP checks fail on the build before phase A); the review grep shows no hit that is not reworded or marked historical; `/speckit-analyze` reports no CRITICAL or HIGH finding; the gate green. Amended by the orchestrator's ruling: the one release `0.2.0` (all five operations), the bump from the draft (`rs-f9d870c` since the phase D close-out; `rs-a33c5ec` from T013) and
    `validateApiSpecVersions` passing follow phase D (Deferred), so they are not part of this task's tick.
    Done after phase D (2026-10-04): api Release `v0.2.0` published `0.2.0`; the service pins `0.2.0` and
    `validateApiSpecVersions` passes.
  - Deferred (not in 003):
    - push notifications to consumers (outbox, Service Bus); pull is the only feed;
    - views by defendant, courtroom or prosecutor, and the `share_defendant(defendant_id)` index;
    - retention and purge (`expires_at`; the DPIA); probation S6 asks for at least 91 days;
    - writers recording their effective bound in a row readers check, only if a deployment with differing pods is ever planned (E10 says none is);
    - PostgreSQL 17 `transaction_timeout` in the store transaction and the lag rule, once the production version is known (D-PG-VERSION, E11: a risk);
    - a stored served-text column or table, if consumers need payload bytes stable across database upgrades (D-JSONB-PROMISE);
    - the `pg_stat_activity` watermark, only with a dedicated race test, if a 90-second delay becomes a problem;
    - paging the day's versions, if NFT shows very long days;
    - D-AUDIT option 2 (a body-exclusion switch from the library owners), after which the option-4 class is deleted;
    - `CREATE INDEX CONCURRENTLY` for V5 if it deploys after live capture starts;
    - deploy values in `cpp-aks-deploy` (audit transport hosts and credentials, the gateway route to `/results-store/v1`) and the Azure Monitor alert rules for `resultsstore.intake.visibility.overrun` and `read.requests{outcome=unavailable}`;
    - consumer client code in YOT and probation (they build to contracts/read-api.md);
    - a youth-raised feed for spec 004's held `FALSE`→`TRUE` changes (D-YOUTH-RAISE);
    - DONE 2026-10-04: the api repo's one GitHub Release `v0.2.0` (all five operations, phase D's arrived
      route included; no `0.3.0`) and the bump of gradle/libs.versions.toml from the draft (`rs-f9d870c`
      since the phase D close-out; `rs-a33c5ec` from T013) to `0.2.0`: after phase D, the orchestrator's
      step (not done in T012 or T013); `validate-api-spec-version` now accepts the pin;
    - D-PG-VERSION / HA: the production PostgreSQL version and synchronous replication stay a recorded risk
      (E11; research R4);
    - Flyway's own unpooled connection has no `socketTimeout` (the pool's `data-source-properties` do not
      reach it), so a migration stalled on a dead database waits on TCP;
    - `spring.flyway.init-sqls` interpolates the raw `RESULTSSTORE_FLYWAY_STATEMENTTIMEOUT` environment
      variable into SQL rather than a typed, checked property (a PostgreSQL duration, refused only by
      PostgreSQL at start);
    - the search cursor is strictly decoded but not signed: ruled acceptable (it holds only public facts and
      moves only the caller's own paging position within the court it already may read; FR-029, R9);
    - audit events (T011 findings, `cp-audit-filter-springboot` 1.0.5): the share routes' request event
      carries no `shareId`, because the library resolves only inline path parameters and the contract declares
      `shareId` by `$ref` (fix: inline the parameter in hmcts/api-cp-crime-results-store, or the library
      resolving references); no event carries the derived action (the library names its record from
      `Accept`/`Content-Type`, which the action filter reads as `application/json`). Both need a contract or
      library change;
    - the generated models carry no `@JsonPropertyOrder`, so Jackson 3 writes their members alphabetically
      (problem bodies from the advice differ in order from the filters'); JSON order is not part of the
      contract; the api repo's generator could add the order.
  - Smoke and compose: `scripts/container-smoke.sh` gains the read API checks (pull lists the share once the
    derived lag has passed, budget 45 s; `visibleUpTo` with six digits; one share for a System Users and a
    Second Line Support caller; the payload's `sha256sum` equals the unquoted `ETag`, `jq 'has("_metadata")'`
    false, its hearing, `Content-Type`, `Results-Store-Share-Id`, `-Enrichment-Applied`, `Cache-Control`;
    `If-None-Match` `304`; the day's versions; `401`, `403`, `404 route_not_found` bounded bodies with no
    path or id; a vendor `Accept` on pull `200` as `application/json`; the Prometheus lines
    `resultsstore_read_requests_total{…}`, `resultsstore_read_refused_total{…}` for `route_not_found`,
    `unauthenticated`, `forbidden`, and `resultsstore_intake_visibility_overrun_total 0.0`); `jq` joins curl
    and coreutils as a host tool (preinstalled on GitHub's runners). docker-compose.yml: the four intake
    timeouts (6 s, 2 s, 1 s, 1 s; derived lag 11 s) through `${VAR:-default}`, the lag left unset.
    docker/wiremock/mappings: `identity-stub.json` priority 10 (the default "System Users"),
    `identity-second-line.json` (`CJSCPPUID` `22222222-2222-4222-8222-222222222222`, "Second Line Support")
    and `identity-no-group.json` (`11111111-1111-4111-8111-111111111111`, "Other Group"), priority 1.
  - RED (the extended smoke on this branch before the compose and stub changes, `flock … scripts/container-
    smoke.sh`, exit 1): `FAIL: pull lists the share after the lag: expected 'true', found 'false'` (90 s lag,
    45 s budget); `FAIL: a caller in neither group: status: expected '403', found '200'` (and its reason, its
    four fields and its echo checks); `FAIL: metric line missing: resultsstore_read_requests_total{endpoint=
    "share",outcome="ok"} 2.0`; `FAIL: metric line missing: resultsstore_read_refused_total{reason=
    "forbidden"} 1.0`; `FAIL: 7 read API check(s) failed`. (The build before phase A has no read API at all,
    so every HTTP check fails there; this run is the sharper red.) Review grep (RED, 15 hits before the
    edits): constitution 4, design_rules 2, page-notes 4, research 3, plan 1, tasks 1.
  - GREEN: the smoke prints `PASS: intake stored the share enriched and the read API served it to admitted
    callers only`, every check `ok` (58), exit 0; the gate green (1867 tests passed, 0 skipped; JaCoCo report
    line 0.9974, branch 0.9741). Review grep after the edits: the remaining hits are the
    constitution's Sync Impact Report record of 2.0.0 (historical), page-notes' quoted *Today* text, research
    R14/R20's quotations of the old wording, plan.md's Complexity Tracking row (resolved by 2.2.0) and this
    task's own text; none is current wording.
  - Documents: constitution 2.2.0 (II and VII as above, the orchestrator's VII wording "a request refused by
    a filter, by the connector or by authorisation is counted"; Sync Impact Report; Last Amended 2026-10-04);
    `.claude/rules/design_rules.md` (pull safety = the lag, 90 s; security and audit wording with every refusal
    reason incl. `connector_rejected`; the read API table: payload without `_metadata`, the search time form,
    the arrived row); specs/001 (spec.md *Out of scope*, *Assumptions*, FR-015, FR-036 notes; data-model.md;
    contracts/metrics.md *Not in 001*; contracts/configuration.md timeouts and backstop); specs/002 (FR-041,
    contracts/schema.md rule 5, page-notes.md §3); specs/003 contracts (read-api.md: the draft and release,
    the audit paragraph as built incl. the connector, `If-None-Match: *`, member order not fixed;
    configuration.md: rule exceptions are `IllegalArgumentException`, the read timeout's whole seconds and why
    the read template is no bean, the release after phase D, the compose identity stubs; metrics.md:
    `payload.bytes` is recorded per payload read, a `304` included), quickstart.md (catalogue key), page-notes
    (status, audit as built, release after phase D), plan.md (constitution 2.2.0; release after phase D),
    spec.md (status Implemented, phases A to C; FR-057 names the connector).
  - `/speckit-analyze` (by hand, hooks disabled; spec, plan and tasks against constitution 2.2.0, research,
    data-model and contracts). CRITICAL: none (II and VII now read as built). HIGH: none open; resolved in
    this task: FR-057's audit wording lacked the connector the constitution and FR-051 name (spec edited);
    contracts/read-api.md §2.4 said the audit record holds the action, which the library does not write
    (contract edited, gap in Deferred). MEDIUM, kept with a reason: T012's own text still lists the contract
    release, now ruled to follow phase D (recorded in Deferred, not rewritten); T011's audit case name differs
    from the cases built (recorded under T011); spec 004's documents still carry 110 s and the old timeouts
    (corrected when 004 is rebased, Notes). LOW: none recorded.
    `.specify/scripts/bash/check-prerequisites.sh --require-tasks --include-tasks --json` exit 0
    (`{"FEATURE_DIR":".../specs/003-read-api","AVAILABLE_DOCS":["research.md","data-model.md","contracts/",
    "quickstart.md","tasks.md"]}`).
  - Gate round 1 (Codex MEDIUM, stale jar in the smoke image): scripts/container-smoke.sh now removes
    `build/libs/*.jar` before `./gradlew bootJar` and fails unless exactly one jar results; docker/startup.sh
    refuses (exit 1, message to stderr) when more than one application jar is in `/app` (or `./build/libs`),
    and now exits 1 when there is none. The Dockerfile keeps its `build/libs/*.jar` copy, because the jar's
    name carries the build's version. RED (a stale `build/libs/aaa-stale.jar` beside the current jar, image
    built, `startup.sh` run): `startup.sh : Running docker java jarfile /app/aaa-stale.jar`. GREEN: the same
    image `startup.sh : ERROR - 2 jarfiles found in /app, expected one. Unable to start application`, exit 1;
    with one jar it runs it; the smoke prints `PASS`, every check `ok`, exit 0.
  - Gate round 1 (documents only; spec-validator and qa/Codex LOWs): CLAUDE.md *Authorisation Rule*,
    `.claude/agents/software-engineer.md` (Default-deny bullet) and `.claude/agents/spec-validator.md`
    (checklist item 4) now say what constitution 2.2.0 VII says (both groups admitted on read rules, matched on
    method and path; the derived action; every request that reaches an endpoint audited, refusals counted;
    the payload audit marker); the constitution's Sync Impact Report records that change instead of "no change
    needed". T012's *Covers* and *Done when* now state that the `0.2.0` release, the bump and
    `validateApiSpecVersions` follow phase D (the orchestrator's ruling), so the tick matches the text; T010
    records the api repo's publish run for `rs-69080b1`.

---

## Phase D: arrived text (T013; D-RAW accepted, E2)

**Purpose**: the text as it arrived, without the envelope metadata (E8), on its own endpoint, action and
rule. Its own phase so phases A to C never wait on it. Depends on phase C.

**Independent test**: `ReadApiIT` serves the arrived text without `_metadata`, with `ETag` equal to the
SHA-256 of the body and not, in general, equal to `payload_sha256`.

- [X] T013 [US8] Api repo first (research R23 C4): in hmcts/api-cp-crime-results-store, a pull request adding `GET /results-store/v1/shares/{shareId}/payload/arrived` (operation `getShareArrivedPayload`, tag `shares`, the payload operation's parameters, headers and `type: string`, `format: binary` `200` body; contracts/read-api.md §4.6), with a CHANGELOG line and an `OpenApiObjectsTest` case that it returns `ResponseEntity<byte[]>`; merged and fast-forwarded to `team/rs`; its `rs-<sha7>` draft published; gradle/libs.versions.toml pinned to that draft (the drift test is red until the service's document follows, and `OpenApiContractTest.every_sharesapi_operation_should_be_overridden` is red until the controller does). Then test first, contract first within the service: `ResultsStoreRulesTest`, `OpenApiDocumentTest`, `ApiRouteTest`, `OpenApiContractTest` and `OpenApiContractDriftTest` gain the route and action through their parameterised sources (red until the contract changes); then src/main/resources/results-store-openapi.yaml (the same operation, so the drift test passes), src/main/resources/acl/results-store-rules.drl (`results-store.get-share-arrived-payload`), src/main/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRoute.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReadEndpoint.java (+ `ARRIVED_PAYLOAD`); then test first `JdbcShareQueriesIT`, `ShareReadServiceTest`, `SharePayloadControllerTest`, `ReadApiIT`, `AuditIT`, `MicrometerReadObserverTest` cases below; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareQueries.java (+ `arrivedText`), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueries.java (identity columns and `p.payload_text`; `payload_sha256` not read), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadService.java (strips with T005's `EnvelopeMetadata`, hashes the served bytes), src/main/java/uk/gov/hmcts/cp/resultsstore/api/SharesController.java (overrides the generated `getShareArrivedPayload`, through `PayloadResponses`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ShareParametersInterceptor.java (covers the route), src/main/java/uk/gov/hmcts/cp/resultsstore/filters/PayloadBodyFreeAuditPayloadGenerationService.java (covers the route); no release here (corrected by the orchestrator's ruling: ONE api release `v0.2.0` at the end of 003 covering all five operations, then the bump from the draft, both the orchestrator's step after this phase; there is no `0.3.0`); no constitution change (T012 wrote the Principle II clause in 2.2.0); specs/003-read-api/contracts/read-api.md §4.6 checked against what was built
  - Cases: `JdbcShareQueriesIT.arrived_text_should_return_payload_text_with_the_identity_columns`; `ShareReadServiceTest.arrived_body_should_have_no_metadata_and_its_etag_should_be_the_sha256_of_the_served_bytes`; `ShareReadServiceTest.arrived_etag_should_never_equal_payload_sha256`; `SharePayloadControllerTest.arrived_body_should_be_byte_identical_to_the_service_bytes_with_form_arrived_text`; `ReadApiIT.arrived_should_serve_both_groups_and_refuse_a_caller_in_neither`; `ReadApiIT.arrived_body_parsed_should_equal_the_published_message_without_metadata` (no application results added); `ReadApiIT.arrived_if_none_match_should_give_304`; `AuditIT.arrived_response_event_should_carry_the_marker`; `MicrometerReadObserverTest`: `arrived_payload` registered with `not_modified`.
  - Covers: FR-001 (arrived route), FR-038 (arrived query), FR-041, FR-046 (arrived action), FR-049 (its rule), FR-063 (the route published api repo first); US8; SC-014.
  - Done when (as corrected by the orchestrator's ruling): the api operation merged and its draft `rs-a33c5ec` published, the service pinned to it; every named test class green; the gate green; the smoke `PASS` with the arrived checks. The release `0.2.0` and `validateApiSpecVersions` passing follow phase D (Deferred under T012; done 2026-10-04, the service pins `0.2.0`).
  - Api repo (done before this phase, orchestrator's note): hmcts/api-cp-crime-results-store carries
    `getShareArrivedPayload` (`default ResponseEntity<byte[]> getShareArrivedPayload(UUID shareId, @Nullable
    String ifNoneMatch)` at `PATH_GET_SHARE_ARRIVED_PAYLOAD`, produces `application/json` and
    `application/problem+json`; CHANGELOG line), published as the draft `rs-a33c5ec` (resolved from
    `hmcts-lib` without credentials). gradle/libs.versions.toml pinned from `rs-69080b1` to `rs-a33c5ec`; the
    two jars' specs differ only by the arrived path (and `info.version` / the `servers` default).
  - Built: `ApiRoute.GET_SHARE_ARRIVED_PAYLOAD` (`/results-store/v1/shares/{shareId}/payload/arrived`,
    `results-store.get-share-arrived-payload`, `ReadEndpoint.ARRIVED_PAYLOAD`, tag `arrived_payload`);
    `ReadOutcome.appliesTo` admits `not_modified` for `payload` and `arrived_payload`, so
    `MicrometerReadObserver` registers the six `arrived_payload` series up front; the DRL rule (both groups,
    `attributes["method"] == "GET"`, `attributes["path"] matches "/results-store/v1/shares/[^/]+/payload/arrived"`);
    results-store-openapi.yaml gains the jar's path block (descriptions rewrapped as folded scalars, the
    same parsed strings; `OpenApiContractDriftTest` green); `ShareQueries.arrivedText` /
    `JdbcShareQueries.ARRIVED_SQL` (identity columns and `p.payload_text AS body`; neither `payload_sha256` nor
    `payload_json` read; the row mapper shared with the payload query); `ShareReadService.arrivedPayload`
    (`EnvelopeMetadata.strip` always, then the shared `served` step: UTF-8 bytes, quoted SHA-256 `ETag`,
    `payload.bytes` recorded; unreadable text is `UnreadablePayloadException`, so `500 internal_error` by
    the advice, never the text); `SharesController.getShareArrivedPayload` through `PayloadResponses.answer`
    (Spring's `If-None-Match` from the `ETag`, `*` answered there; `Cache-Control: no-store`;
    `Results-Store-Enrichment-Applied` as stored; `Results-Store-Payload-Form: arrived-text`);
    `PayloadBodyFreeAuditPayloadGenerationService` replaces the body for both payload actions (keyed on the
    derived action). `ShareParametersInterceptor` and `ActionHeaderFilter` needed no change: they work over
    `ApiRoute`, and the route takes no query parameter (`unknown_parameter`) and a canonical `shareId`.
    scripts/container-smoke.sh gains the arrived checks (200; `sha256sum` equals the unquoted `ETag`; the
    `ETag` is not, in general, `payload_sha256`; no `_metadata`; its hearing; its first application without the results
    added at intake; `Content-Type`; `Results-Store-Payload-Form` `arrived-text`;
    `Results-Store-Enrichment-Applied` `true`; `Cache-Control`; `If-None-Match` `304`; a Second Line Support
    caller `200`; the metric line `resultsstore_read_requests_total{endpoint="arrived_payload",outcome=
    "not_modified"} 1.0`). A missing header now fails its check instead of stopping the script.
  - Least-behaviour choices: the arrived body is served whatever the working copy holds (always
    `payload_text`); `Results-Store-Enrichment-Applied` carries the share's stored flag (the orchestrator's
    note), though this body never holds application results; a stored text that does not parse gives
    `500 internal_error` as the `/payload` fallback does.
  - RED, contract half (seam: `ReadEndpoint.ARRIVED_PAYLOAD` only; jar pinned to `rs-a33c5ec`; run per class,
    `failFast`): `ReadOutcomeTest` → `not_modified_should_apply_to_the_two_payload_endpoints_only(ReadEndpoint)
    > [6] endpoint = ARRIVED_PAYLOAD FAILED` `expected: true but was: false`; `MicrometerReadObserverTest` →
    `arrived_payload_should_be_registered_with_not_modified() FAILED` `Expecting actual: ["bad_request", "ok",
    "not_found", "unavailable", "failed"] to contain exactly in any order: [… "not_modified" …] but could not
    find the following elements: ["not_modified"]`; `ApiRouteTest` → `every_route_should_name_its_endpoint_tag()
    FAILED` `could not find the following elements: [ARRIVED_PAYLOAD]`; `ResultsStoreRulesTest` →
    `the_arrived_payload_action_should_be_allowed_on_its_own_path_for_both_groups_only() FAILED` `Expecting
    value to be true but was false`; `OpenApiDocumentTest` → `the_arrived_operation_should_declare_the_etag_
    and_results_store_headers_with_the_arrived_form() FAILED` (`Expecting actual: {…} to contain key:
    "/results-store/v1/shares/{shareId}/payload/arrived"`); `OpenApiContractTest` →
    `every_controller_mapping_should_be_described() FAILED`, `every_sharesapi_operation_should_be_overridden()
    FAILED` `[getShareArrivedPayload] expected: …SharesController but was: …SharesApi`,
    `every_described_route_should_have_a_controller_mapping() FAILED`; `OpenApiContractDriftTest` →
    `the_paths_should_be_identical() FAILED` `[paths that differ] Expecting empty but was: ["paths
    /results-store/v1/shares/{shareId}/payload/arrived"]`.
  - RED, service half (seams: `ShareQueries.arrivedText`, `JdbcShareQueries.arrivedText` answering empty,
    `ShareReadService.arrivedPayload` serving the text unstripped; the controller not yet overriding the
    generated default, which answers `501`): `JdbcShareQueriesIT` → `arrived_text_should_return_payload_text_
    with_the_identity_columns() FAILED` `[present] Expecting Optional to contain a value but it was empty.`;
    `ShareReadServiceTest` → `arrived_etag_should_never_equal_payload_sha256() FAILED` `Expecting actual:
    ""7028dbbf…a59b"" not to be equal to: ""7028dbbf…a59b""`, `an_unreadable_arrived_text_should_be_internal_
    error_never_the_text() FAILED` `Expecting code to raise a throwable.`; `SharePayloadControllerTest` →
    `an_unreadable_arrived_text_should_be_500_internal_error_with_the_bounded_body() FAILED` `expected: 500
    but was: 501`; `PayloadBodyFreeAuditPayloadGenerationServiceTest` → `the_payload_routes_should_be_
    replaced_by_the_marker(ApiRoute) > [2] route = GET_SHARE_ARRIVED_PAYLOAD FAILED` `Expecting value to be
    true but was false`; `ReadApiIT` → `arrived_body_parsed_should_equal_the_published_message_without_
    metadata() FAILED` `[status] expected: 200 but was: 501`; `AuditIT` → `arrived_response_event_should_
    carry_the_marker() FAILED` `expected: 200 but was: 501`. `ShareParametersInterceptorTest`'s new
    parameterised case (`a_share_route_should_refuse_a_bad_share_id_and_any_query_parameter`, the three
    share-id routes) and `JdbcShareQueriesTest.the_arrived_constant_should_read_payload_text_and_never_
    payload_sha256_or_payload_json` were green on their first run: coverage through the route table and a
    guard on the constant, with no production change of their own.
  - RED, smoke (the extended script run on the build at `099c0fc`, `flock … scripts/container-smoke.sh`,
    exit 1): `FAIL: arrived: 200: expected '200', found '404'`; `FAIL: arrived: SHA-256 of the body equals the
    unquoted ETag: expected '', found '502e0c46…d578'`; `FAIL: arrived: the ETag is not the stored checksum:
    expected 'true', found 'false'`; `FAIL: arrived: the hearing it holds: … found 'null'`; `FAIL: arrived: its
    first application without the results added at intake: expected 'true', found 'false'`; `FAIL: arrived:
    Content-Type: expected 'application/json', found 'application/problem+json'`; `FAIL: arrived:
    Results-Store-Payload-Form: expected 'arrived-text', found ''` (and `-Enrichment-Applied`,
    `Cache-Control`); `FAIL: arrived: If-None-Match gives 304: expected '304', found '404'`; `FAIL: arrived for
    a Second Line Support caller: 200: … found '404'`; `FAIL: metric line missing: resultsstore_read_requests_
    total{endpoint="arrived_payload",outcome="not_modified"} 1.0` (and `route_not_found` 1.0: the arrived
    calls were 404s); `FAIL: 13 read API check(s) failed`. (The first run of the extension stopped at the
    first missing `ETag` under `pipefail`; fixed before this run.)
  - GREEN (per class, 0 failures): `ReadEndpointTest` 1, `ReadOutcomeTest` 22, `MicrometerReadObserverTest` 7,
    `ApiRouteTest` 35, `ResultsStoreRulesTest` 40, `OpenApiDocumentTest` 7, `OpenApiContractTest` 10,
    `OpenApiContractDriftTest` 6, `SharesControllerTest` 13, `JdbcShareQueriesTest` 9, `JdbcShareQueriesIT` 38,
    `ShareReadServiceTest` 35, `SharePayloadControllerTest` 17, `ContentNegotiationTest` 58,
    `PayloadBodyFreeAuditPayloadGenerationServiceTest` 8, `ShareParametersInterceptorTest` 9,
    `ActionHeaderFilterTest` 123, `ReadApiIT` 72, `AuditIT` 11, `AuthzIT` 32. The smoke prints `PASS: intake
    stored the share enriched and the read API served it to admitted callers only`, every check `ok`, exit 0.
    The gate green: 1968 tests, 0 failed, 0 skipped; JaCoCo report line 0.9974, branch 0.9738.
  - Documents: contracts/read-api.md (the header: built against `rs-a33c5ec`, one release `0.2.0` covering
    all five operations; §1 row 6; §4.6 final, as built); contracts/metrics.md (`arrived_payload` registered
    up front, `not_modified` for both payload endpoints); contracts/configuration.md (the draft `rs-a33c5ec`,
    the one release); page-notes.md (the arrived row, S10, G2, status); plan.md, research.md R23 (the pin and
    the one release); spec.md status (phases A to D); this task, T012's release text and *Phase dependencies*
    corrected to one release `v0.2.0` at the end of 003 (no `0.3.0`). Earlier tasks' records that name
    `rs-69080b1` (T009, T010, T012) are left as the history of what those tasks pinned.
  - Gate round 1 remediation (tests and wording only; no production behaviour changed, so every new
    assertion was green on its first run and none has a RED run): `NoPayloadInLogsIT.serving_a_payload_
    should_log_no_payload_marker_at_any_level` now fetches `/payload/arrived` as well as `/payload` for each
    stored share, an enriched one among them (marker in the note and in the added result, so the two bodies
    differ), and a share whose stored text is not JSON: `500`, the marker not in the body, the advice's line
    naming `EnvelopeMetadata$UnreadablePayloadException` only, and no captured line at any level holding the
    marker. `ReadApiIT.an_arrived_text_that_does_not_parse_should_give_500_internal_error_and_never_the_text`
    drives that path through `JdbcShareQueries`, the advice and the filters (bounded `internal_error`, no
    `Results-Store-*` header, no `ETag`, `requests{endpoint=arrived_payload,outcome=failed}` +1);
    `ReadApiIT.arrived_if_none_match_should_give_304` also pins `resultsstore.read.duration{endpoint=
    arrived_payload}` (+4, one per call, 304s included) and `resultsstore.read.payload.bytes`;
    `AuditIT.the_share_routes_request_event_should_not_carry_a_share_id_declared_by_reference` is
    parameterised over the share, payload and arrived routes. GREEN: `NoPayloadInLogsIT` 4, `ReadApiIT` 73,
    `AuditIT` 13, 0 failures. Wording: the arrived `ETag` is "not, in general" the stored checksum (a
    compact message without `_metadata` hashes the same, since intake does not require `_metadata`):
    `ShareReadService#arrivedPayload` Javadoc, contracts/read-api.md §4.4 and §4.6, research.md R23. The
    OpenAPI description is the contract jar's text and is left as it is (`OpenApiContractDriftTest`).

---

## Dependencies & Execution Order

### Phase dependencies

- Before phase A (done) → Phase A → Phase B → Phase C → Phase D. Each phase starts only
  after the previous phase-gate run has ended at PASS.
- Phase B needs from phase A: `ApiRoute`, `ReadEndpoint`, `RouteRefusal` (T001), `ProblemReason` (T002).
- Phase C needs from phase B: V5 (T004), the read types and ports (T005), `JdbcShareQueries` (T006),
  `ShareReadService` (T007), and the overrun threshold seam and the new intake defaults (T008).
- Phase D needs phase C complete. The one contract release, `0.2.0` (all five operations), follows phase D
  (orchestrator ruling; T012's Deferred).
- Every contract change (T010's payload body, T013's arrived route) is made in hmcts/api-cp-crime-results-store
  first and taken as a `rs-<sha7>` draft; the service never releases on a draft (`validateApiSpecVersions`,
  research R23 C4).

### Within phases

- Phase A: T001 → T002 (filters over the route table) → T003 (registers the filter of T002; uses
  `ProblemReason` of T002).
- Phase B: T004, T005 and T008 touch disjoint files and may run in any order; T006 needs T004 (the plan
  test needs V5's indexes) and T005 (the read types); T007 needs T005 and the port of T005 (it mocks
  `ShareQueries`).
- Phase C: T009 → T010 (the controller needs T009's beans and the contract jar) → T011 (end to end) → T012
  last (it records the analysis of the finished range and releases the contract).

### User story → tasks

| Story | Tasks | Independently proven by |
|---|---|---|
| US1 Pull every share since a cursor, safely (P1, MVP) | T004, T005, T006, T007, T008, T009, T010, T011, T012 | `JdbcShareQueriesIT` race and bound; `ReadApiIT` paging and lag; smoke pull |
| US2 Fetch a payload and prove it intact (P2) | T005, T006, T007, T010, T011, T012 | `SharePayloadControllerTest`; `ReadApiIT` hash equals `ETag`; smoke `sha256sum` |
| US3 One share and the day's versions (P3) | T005, T006, T007, T010, T011, T012 | `JdbcShareQueriesIT` `versionNumber`; `HearingDaySharesControllerTest` |
| US4 Search by court and London day or time range (P4) | T001, T004, T005, T006, T007, T010, T011 | `JdbcShareQueriesIT` both forms and keyset; `ReadQueriesPlanIT` |
| US5 Only admitted callers; no self-chosen action (P5) | T001, T002, T003, T011, T012 | `ResultsStoreRulesTest`; `ActionHeaderFilterTest`; `ReadApiIT` spoofing; smoke `401`/`403`/`404` |
| US6 Bounded, retry-friendly errors (P6) | T003, T010, T011 | `ReadApiExceptionHandlerTest`; `BoundedErrorAttributesTest`; `ReadApiIT` `503` |
| US7 Read traffic, refusals and overrun visible (P7) | T003, T005, T008, T009, T010, T011, T012 | `MicrometerReadObserverTest`; `IntakeServiceTest` overrun; smoke metric lines |
| US8 The arrived text without `_metadata` (P8, phase D) | T005 (strip), T013 | `EnvelopeMetadataTest`; `ReadApiIT` arrived cases |

## Parallel examples

```text
Phase B: "T004 FlywayMigrationIT, then V5__read_api.sql"
         "T005 SearchCursorTest / DayYouthFilterTest / … / MicrometerReadObserverTest, then the read types and ports"
         "T008 IntakeServiceTest / JdbcShareStoreIT / MicrometerIntakeObserverTest / IntakeConfigTest, then the overrun counter"
```

Phases A, C and D have no parallel tasks: each task uses the one before it. Within a phase-gate run there
is one implementer, so `[P]` marks independence (the order is free), not concurrent agents in one tree.

## Implementation strategy

1. Phase A makes the edge safe before anything is served: unknown paths refused, actions derived, rules
   in place.
2. Phases B and C deliver the MVP: US1 (pull) end to end in `ReadApiIT`, with US2–US7 proven alongside
   it.
3. T012 proves it in the compose stack and reconciles the constitution, the design rules, spec 001 and
   the page notes.
4. Phase D adds the arrived text (D-RAW accepted, E2).

## Notes

- Ticks (`[X]`) and the RED / GREEN lines are written by the implementer in the commit that completes
  the task.
- A task's commit message follows Conventional Commits and names the task id.
- Where a task finds the design documents silent, the implementer takes the option that changes the
  least behaviour and records it under the task.
- Every decision is taken (spec.md *Decisions taken with Sachin (2026-10-03)*). A later change to one
  changes only the tasks named in its row; the implementer records it under the task.
- Spec 004's documents carry 110 s for the lag and the old timeouts; they are corrected when 004 is rebased onto the built 003.
