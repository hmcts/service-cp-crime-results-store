---

description: "Task list for feature 003: read API"
---

# Tasks: Read API

**Input**: Design documents from `/specs/003-read-api/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R22), data-model.md, contracts/ (read-api, metrics,
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
- Explicit imports only; constructor injection; records for responses; PMD clean. **`OnlyOneReturn`**:
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
- **T010 adds component-scanned controllers.** Every context stays green because T009's beans exist. The
  `@WebMvcTest` slices mock `ShareReadService` and `ReadObserver` with `@MockitoBean` and run with
  `@AutoConfigureMockMvc(addFilters = false)`; if a slice pulls in `ApiWebConfig`, its dependencies are
  mocked the same way.
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
    `RouteRefusalTest` 1, 0 failures; the gate green (1002 tests passed, 1 skipped; coverage line 0.9956,
    branch 0.9950).

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
    passed, 1 skipped; coverage line 0.9959, branch 0.9953).

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
    end) 24, `ExtractionSweepIT` 37 and every other suite green; the gate green (1193 tests passed, 1 skipped;
    coverage line 0.9960, branch 0.9934).

---

## Phase B: data and application (T004–T008)

**Purpose**: the V5 trigger and indexes, the read types and ports, the read-only SQL with the visibility
bound, the read service, and the intake-side overrun counter. Nothing is served over HTTP yet. Depends on
phase A (`ApiRoute`, `ReadEndpoint`, `ProblemReason`).

**Independent test**: `FlywayMigrationIT` proves the schema; `JdbcShareQueriesIT` proves pull safety, the
bound, the filters, search and the payload read; `ReadQueriesPlanIT` ties each query to its index;
`ShareReadServiceTest` proves limits, cursors and the `ETag`; `IntakeServiceTest` and `JdbcShareStoreIT`
prove the overrun counter.

- [ ] T004 [P] [US1] [US4] Test first: `FlywayMigrationIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/FlywayMigrationIT.java; then src/main/resources/db/migration/V5__read_api.sql (data-model.md, in full)
  - Cases: `v5_should_create_the_stored_at_trigger_and_the_three_partial_indexes` (`hearing_share_youth_feed_ix (stored_seq)`, `hearing_share_centre_feed_ix (court_centre_id, stored_seq)`, `hearing_share_centre_shared_at_ix (court_centre_id, shared_at, share_id)`: names, `pg_get_indexdef` predicates and column order; no index on `shared_day_london`; `BEFORE INSERT` timing); `stored_at_should_be_at_or_after_a_clock_read_taken_just_before_the_insert` (same connection: `clock_timestamp()`, then the insert, then compare); `an_insert_supplying_stored_at_should_be_overridden` (a value one hour in the past comes back as now); `the_stored_seq_sequence_should_have_cache_one` (`pg_sequences` for `pg_get_serial_sequence('hearing_share', 'stored_seq')`); `updating_stored_at_should_still_be_refused` (`hearing_share_guard`); the existing version list and V1–V4 checksum cases updated to include V5.
  - Covers: FR-019, FR-054; contracts/schema.md rules 1, 2, 4, 6.
  - Done when: `FlywayMigrationIT` green; the gate green.

- [ ] T005 [P] [US1] [US2] [US3] [US4] [US7] Test first: `SearchCursorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/SearchCursorTest.java, `DayYouthFilterTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/DayYouthFilterTest.java, `ShareViewTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ShareViewTest.java, `ReadOutcomeTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ReadOutcomeTest.java, `PayloadChecksumTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadChecksumTest.java, `EnvelopeMetadataTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/EnvelopeMetadataTest.java, `SharedDaysTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/SharedDaysTest.java, `MicrometerReadObserverTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerReadObserverTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ShareView.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/DayYouthFilter.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SearchCursor.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/StoredPayload.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadForm.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReadOutcome.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/PayloadChecksum.java (+ `sha256Hex(byte[])`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/EnvelopeMetadata.java (parse with Jackson 3, remove the top-level `_metadata` member, write back compact; exact decimals; key order kept), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SharedDays.java (+ the London day range as a [from, to) instant range, `Europe/London`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareQueries.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ReadObserver.java (port: `request(ReadEndpoint, ReadOutcome, Duration)`, `pageItems(int)`, `payloadBytes(long)`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/PullQuery.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/SearchQuery.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerReadObserver.java
  - Cases: `SearchCursorTest` (cursor = `v1|<shared_at epoch microseconds>|<shareId>`, E6): `encode_then_decode_should_round_trip`; `the_encoded_cursor_should_never_exceed_128_characters` (the longest microsecond value); `a_tampered_truncated_padded_over_long_or_non_base64url_cursor_should_be_invalid`; `a_cursor_with_another_version_prefix_extra_part_bad_uuid_or_negative_microseconds_should_be_invalid`. `EnvelopeMetadataTest`: `the_top_level_metadata_member_should_be_removed`; `a_nested_metadata_key_should_be_left_alone`; `a_text_without_metadata_should_keep_its_content`; `key_order_and_exact_numbers_should_be_kept` (`1.50`, a 20-digit integer); `a_u0000_escape_should_survive` (the case jsonb cannot hold); `a_text_that_is_not_json_should_fail_without_echoing_it`. `SharedDaysTest`: `a_london_day_range_should_become_london_midnight_to_london_midnight` (BST and GMT); `a_range_over_the_spring_and_autumn_clock_changes_should_cover_23_and_25_hours`. `DayYouthFilterTest`: `from_value_should_accept_not_false_true_and_false_case_sensitively`; `anything_else_should_be_refused` (`NOTFALSE`, `True`, empty); `false_should_not_be_allowed_on_pull`. `ShareViewTest`: `key_details_should_be_null_exactly_when_failed`. `ReadOutcomeTest`: `every_tag_should_come_from_the_fixed_list`. `PayloadChecksumTest`: `the_bytes_form_should_equal_the_text_form_for_utf_8`; `different_bytes_should_give_different_hashes`. `MicrometerReadObserverTest`: `every_meter_and_tag_combination_should_be_registered_at_start` (`not_modified` only with `payload`); `each_callback_should_move_exactly_its_meter`; `no_tag_value_should_parse_as_a_uuid_or_date`.
  - Notes: `SearchCursor.decode` and `DayYouthFilter.fromValue` are single-exit (PMD). `EnvelopeMetadata` never puts the text in an exception message (Principle XI).
  - Covers: FR-006 (shape), FR-011 (values), FR-027 (day translation), FR-029, FR-033 (text-form strip), FR-039, FR-041 (strip, reused by T013), FR-055 (read meters).
  - Done when: the six test classes green; the gate green.

- [ ] T006 [US1] [US2] [US3] [US4] Test first: `JdbcShareQueriesIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueriesIT.java, `JdbcShareQueriesTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueriesTest.java, `ReadQueriesPlanIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/ReadQueriesPlanIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueries.java (the constants of data-model.md *Read queries*; its own `JdbcTemplate` passed in; autocommit)
  - Cases (`JdbcShareQueriesIT`, Testcontainers; shares stored through `JdbcShareStore` with the `support/SampleShares` samples; rows that need a chosen `stored_at` are inserted on a test connection with `SET session_replication_role = replica`, which skips the trigger and guards for that insert only): `pull_should_be_exclusive_of_the_cursor_and_ascending_by_stored_seq`; `pull_should_read_at_most_limit_plus_one_rows`; `pull_should_return_nothing_and_keep_the_cursor_when_no_share_is_older_than_the_lag`; `a_slow_lower_number_should_never_be_overtaken` (two connections: an open insert at *n*, a committed *n + 1*; lag 0 shows the race, so the test can fail; with the lag above the open transaction's age *n + 1* is withheld until *n* ends); `a_row_below_the_bound_should_be_returned_even_if_its_own_stored_at_is_after_the_cut_off` (research R4); `the_bound_should_be_the_highest_number_older_than_the_lag_whatever_the_filter` (filters that match nothing still return `max_seq`); `visible_up_to_should_be_the_database_clock_minus_the_lag`; `not_false_should_include_unknown_days_and_exclude_false_ones`; `true_should_include_only_true_days`; `court_filter_should_return_exact_matches_only`; `court_filter_should_exclude_failed_rows` (E5); `a_failed_share_whose_court_the_sweep_fills_later_should_not_be_presented_to_a_court_pull_from_a_later_cursor` (E5 consequence); `key_details_should_be_null_for_a_failed_row`; `version_number_should_follow_shared_at_and_move_when_an_earlier_share_arrives_late`; `search_day_form_should_filter_the_london_day_inclusively_including_a_00_30_bst_share`; `search_day_form_should_return_the_same_rows_as_shared_day_london_between` (days at both clock changes); `search_time_form_should_be_half_open_on_shared_at` (a share exactly at `sharedTo` is excluded, one exactly at `sharedFrom` included); `search_should_order_by_shared_at_then_share_id`; `search_keyset_pages_should_neither_repeat_nor_miss_rows` (limit 1 over several days and courts, both forms); `search_latest_only_should_return_only_is_latest`; `search_day_youth_seen_variants_should_include_false`; `search_should_never_return_a_failed_row`; `day_versions_should_be_in_shared_at_order_with_row_numbers`; `payload_should_return_the_working_copy_without_metadata_as_the_database_writes_it` (E8; the text equals `(payload_json - '_metadata')::text` and has no `_metadata` key); `payload_should_return_payload_text_and_the_arrived_form_when_the_working_copy_is_null` (the service strips it, T007); `an_unknown_share_payload_or_day_should_be_empty`; `the_read_query_timeout_should_cancel_a_query_held_by_a_lock` (another connection holds `ACCESS EXCLUSIVE` on `hearing_share`; 1 s JDBC query timeout; Spring's `QueryTimeoutException` family). `JdbcShareQueriesTest`: `no_pull_search_share_or_day_constant_should_name_hearing_share_payload`; `the_variant_table_should_cover_every_day_youth_filter_and_court_combination`. `ReadQueriesPlanIT` (`EXPLAIN (FORMAT JSON)` of the **`JdbcShareQueries` constants**, with `SET LOCAL enable_seqscan = off` run inside the test's own transaction, because `SET LOCAL` does nothing outside one; or plain `SET` on a connection the test owns): `pull_not_false_and_true_should_use_hearing_share_youth_feed_ix`; `unfiltered_pull_should_use_hearing_share_stored_seq_uk`; `court_pull_should_use_hearing_share_centre_feed_ix_with_no_sort_node` (with and without a youth filter); `the_bound_should_scan_hearing_share_stored_seq_uk_backward`; `search_day_form_and_time_form_should_both_use_hearing_share_centre_shared_at_ix_with_no_sort_node`; `day_versions_and_version_number_should_use_hearing_share_identity_uk`; `no_pull_search_share_or_day_plan_should_touch_hearing_share_payload`.
  - Covers: FR-009–FR-016, FR-026–FR-033 (data half), FR-038, FR-039 (working copy half), FR-054 (plan half); data-model.md invariants 2–4, 6–8; SC-001, SC-002 (data half), SC-007.
  - Done when: the three test classes green; the gate green.

- [ ] T007 [US1] [US2] [US3] [US4] Test first: `ShareReadServiceTest` (plain mocks, no Spring) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadServiceTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadService.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/PullPage.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/SearchPage.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ServedPayload.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/BadParameterException.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/NotFoundException.java (both carry a `ProblemReason` only, never a caller value)
  - Cases: `pull_limit_should_default_to_100_and_refuse_0_and_501`; `has_more_should_be_true_only_when_limit_plus_one_rows_came_back`; `next_stored_after_seq_should_be_the_last_item_when_there_is_more`; `next_stored_after_seq_should_be_the_greater_of_the_cursor_and_the_bound_otherwise`; `a_null_bound_should_keep_the_cursor`; `visible_up_to_should_be_passed_through`; `search_day_form_should_accept_31_days_and_refuse_32_and_a_reversed_range`; `search_time_form_should_accept_31_days_and_refuse_31_days_and_a_microsecond_and_a_to_not_after_from` (`time_range_too_long`, `time_range_reversed`); `search_day_form_should_reach_the_query_as_london_midnight_instants`; `search_next_cursor_should_encode_the_last_item_and_be_null_on_the_last_page`; `a_bad_cursor_should_be_invalid_cursor`; `payload_etag_should_be_the_quoted_sha256_of_exactly_the_bytes_returned` (non-ASCII text); `the_arrived_text_form_should_be_served_without_metadata_and_hashed_after_the_strip` (E8); `a_working_copy_should_be_served_as_read` (the database already removed `_metadata`); `an_arrived_text_that_fails_to_parse_should_be_internal_error_never_the_text`; `payload_form_should_follow_the_stored_form`; `an_unknown_share_should_be_share_not_found`; `an_empty_day_should_be_hearing_day_not_found`; `page_items_and_payload_bytes_should_be_reported_to_the_observer`.
  - Covers: FR-010, FR-013, FR-014, FR-026–FR-029, FR-032, FR-033, FR-034, FR-039; SC-003 (unit half).
  - Done when: `ShareReadServiceTest` green; the gate green.

- [ ] T008 [P] [US1] [US7] (D-OVERRUN = yes, E4; D-LAG-VALUE = 90 s, E3; touches spec 001 code) Test first: `IntakeServiceTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/IntakeServiceTest.java, `JdbcShareStoreIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStoreIT.java, `MicrometerIntakeObserverTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserverTest.java, `IntakeConfigTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfigTest.java, `ConfigurationValidationTest` (extended, intake defaults only) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java, `StatementTimeoutBackstopTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/StatementTimeoutBackstopTest.java, `PooledStatementTimeoutIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/PooledStatementTimeoutIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/StoreResult.java (`Stored` + `insertToCommit`), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java (an injected nanosecond clock read before sending `INSERT_SHARE` and after the transaction returns, in a holder local to `store(...)`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeService.java (threshold `Duration`; `Stored` at or above it → `visibilityOverrun()`), src/main/java/uk/gov/hmcts/cp/resultsstore/application/IntakeObserver.java (+ `visibilityOverrun()`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserver.java (+ `resultsstore.intake.visibility.overrun`, registered at start), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java (clock `System::nanoTime`; threshold = transaction + 2 × statement + idle-in-transaction from `IntakeProperties`); every `StoreResult.Stored` call site and exhaustive switch updated; and the intake timeouts (E3): src/main/resources/application.yaml (`resultsstore.intake.store.statement-timeout` default `20s` → `10s`, `lock-timeout` default `10s` → `5s`, with their comments), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeProperties.java (`Store`'s `@DefaultValue`s `20s` → `10s` and `10s` → `5s`; the existing rules unchanged, lock ≤ statement included), src/main/java/uk/gov/hmcts/cp/resultsstore/config/StatementTimeoutBackstop.java (new: a static `BeanPostProcessor` bean that sets the `HikariDataSource`'s `connectionInitSql` to `SET statement_timeout = '<n>ms'`, n = `resultsstore.intake.store.statement-timeout` in milliseconds, read through Boot's `Binder` from the same property; registered in `IntakeConfig` unconditionally, not behind the subscription switch)
  - Cases: `IntakeServiceTest`: `a_stored_share_at_the_threshold_should_count_one_overrun`; `a_stored_share_below_the_threshold_should_count_none`; `a_duplicate_or_refused_copy_should_never_count`. `JdbcShareStoreIT`: `stored_should_carry_the_time_from_sending_the_insert_to_the_commit_returning` (a stepping clock gives an exact value); `a_slow_commit_should_be_measured` (a test-only `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `hearing_share` running `pg_sleep(1.1)`, so the sleep runs at `COMMIT` → at least 1.1 s, compared with a 1 s threshold through `IntakeService`). `MicrometerIntakeObserverTest`: the full registered set includes the overrun counter at zero; `visibility_overrun_should_move_by_one`. `IntakeConfigTest`: `the_overrun_threshold_should_be_transaction_plus_twice_statement_plus_idle` (90 s at the defaults; 45 s for 30 s / 5 s / 5 s); `the_backstop_bean_should_exist_with_the_subscription_off`. `ConfigurationValidationTest`: `the_intake_store_defaults_should_be_60s_10s_5s_10s` (transaction, statement, lock, idle-in-transaction); `a_lock_timeout_above_the_statement_timeout_should_still_stop_the_service`. `StatementTimeoutBackstopTest`: `the_init_sql_should_be_set_statement_timeout_in_milliseconds_of_the_intake_property` (`10s` → `SET statement_timeout = '10000ms'`; `1m` → `'60000ms'`; `500ms` → `'500ms'`); `a_non_hikari_data_source_should_be_left_alone`; `an_existing_init_sql_should_stop_the_service_naming_the_property` (two sources would drift). `PooledStatementTimeoutIT` (Testcontainers, full context): `a_pooled_connection_should_report_the_intake_statement_timeout` (`SHOW statement_timeout` outside any store transaction = `10s` at the default); `a_custom_intake_statement_timeout_should_reach_the_pool` (`resultsstore.intake.store.statement-timeout=7s` → `7s`); `the_store_transactions_own_setting_should_still_win_inside_it` (`set_config(..., true)` applies inside the transaction and the session value returns after it).
  - Notes: the limitation (a commit the client never sees return is not counted) is stated in contracts/metrics.md; no test can produce it. The research R4 proof is re-derived for 10 s / 5 s (90 s); the backstop is not part of it. Client-side timeouts are never part of the bound.
  - Covers: FR-017 (threshold half), FR-020, FR-061, FR-062; SC-009, SC-015.
  - Done when: the seven test classes and every changed call site green; the gate green.

---

## Phase C: serving, wiring, end to end, documents (T009–T012)

**Purpose**: wire the read beans first, so the controllers that follow never break a context; then the
controllers, advice and `304`; then the API end to end with authorisation and audit on; then the smoke
and the documents. Depends on phase B.

**Independent test**: `ConfigurationValidationTest` and `ReadApiConfigTest` prove the settings;
the controller slices prove mapping, headers and errors; `ReadApiIT` proves US1–US7 end to end; `AuditIT`
pins the audit behaviour; the smoke proves it in the compose stack.

- [ ] T009 [US1] [US7] Test first: `ConfigurationValidationTest` (extended; stub `DataSource`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java, `ReadApiConfigTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ReadApiConfigTest.java, `IntakeConfigTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfigTest.java, `SweepSchedulingConfigTest` (stub `DataSource` where it loads `ReadApiConfig`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfigTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/config/ReadApiProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/ReadApiConfig.java (unconditional beans: a read `JdbcTemplate` with the query timeout, `JdbcShareQueries`, `ShareReadService`, `MicrometerReadObserver`; the effective lag as a bean IntakeConfig can take), src/main/java/uk/gov/hmcts/cp/resultsstore/config/Rules.java (+ `atLeast(name, value, boundName, bound)`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/IntakeConfig.java (overrun threshold = the effective lag), src/main/resources/application.yaml (the `resultsstore.read.*` block of contracts/configuration.md)
  - Cases: `ConfigurationValidationTest`: `the_visibility_lag_should_default_to_transaction_plus_twice_statement_plus_idle` (90 s at the T008 defaults); `the_derived_lag_should_follow_custom_intake_values`; `a_lag_below_the_sum_should_stop_the_service_naming_the_property`; `a_lag_above_ten_minutes_should_stop_the_service`; `an_unset_lag_whose_derived_value_exceeds_ten_minutes_should_name_the_transaction_timeout`; `a_read_statement_timeout_of_zero_or_at_the_socket_timeout_should_stop_the_service`; `no_message_should_hold_a_value`. `ReadApiConfigTest`: `the_read_beans_should_exist_with_the_subscription_off`; `the_read_template_should_carry_the_statement_timeout`; `the_service_should_use_the_effective_lag`. `IntakeConfigTest`: `the_overrun_threshold_should_be_the_effective_lag` (a set lag of 200 s gives 200 s). `SweepSchedulingConfigTest`: unchanged cases green.
  - Covers: FR-017, FR-018, FR-021 (rule half), FR-045, FR-056; SC-008, SC-015 (lag half).
  - Done when: the four test classes green; every context test green; the gate green.

- [ ] T010 [US1] [US2] [US3] [US4] [US6] [US7] Test first: `ShareParametersTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ShareParametersTest.java, `InstantFormatTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/InstantFormatTest.java, `SharesControllerTest` (`@WebMvcTest` slice) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/SharesControllerTest.java, `SharePayloadControllerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/SharePayloadControllerTest.java, `HearingDaySharesControllerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/HearingDaySharesControllerTest.java, `ReadApiExceptionHandlerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReadApiExceptionHandlerTest.java, `ReadMetricsInterceptorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReadMetricsInterceptorTest.java, `OpenApiContractTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiContractTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/api/ShareParameters.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/InstantFormat.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/SharesController.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/SharePayloadController.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/HearingDaySharesController.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ShareSummaryResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/KeyDetailsResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/PullPageResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/SearchPageResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/DayVersionsResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReadApiExceptionHandler.java (extends `ResponseEntityExceptionHandler`, overrides `handleExceptionInternal`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReadMetricsInterceptor.java (endpoint from the `ApiRoute` request attribute; outcome from the status; duration from `preHandle` to `afterCompletion`), its registration in src/main/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfig.java
  - Cases: `ShareParametersTest`: `an_unknown_parameter_should_be_unknown_parameter` (`storedAfterseq`); `a_repeated_parameter_should_be_repeated_parameter`; `pull_with_a_search_parameter_should_be_conflicting_parameters`; `neither_mode_complete_should_be_missing_parameter`; one row per invalid reason of contracts/read-api.md §6; `day_youth_seen_false_should_be_refused_on_pull_and_accepted_on_search`; `search_should_accept_the_day_form_or_the_time_form`; `a_day_parameter_with_a_time_parameter_should_be_conflicting_parameters`; `an_incomplete_form_should_be_missing_parameter`; `an_instant_with_an_offset_or_seven_fraction_digits_should_be_invalid_shared_from_or_to`; `a_time_parameter_on_pull_should_be_conflicting_parameters`. `InstantFormatTest`: `six_fraction_digits_should_always_be_written` (zero fraction, millis, nanos truncated), `utc_with_z`. `SharesControllerTest`: `pull_should_map_every_parameter_and_field`; `search_should_map_every_parameter_and_field`; `the_item_should_write_every_field_with_nulls_present` (JSON keys checked, `keyDetails` null when `FAILED`); `no_problem_body_should_echo_a_caller_value`. `SharePayloadControllerTest`: `the_body_should_be_byte_identical_to_the_service_bytes`; `the_body_should_have_no_metadata_key` (E8); `the_etag_should_be_strong_and_quoted`; `exactly_one_etag_header_should_be_sent_on_200_and_on_304`; `identity_enrichment_and_form_headers_should_be_present`; `cache_control_should_be_no_store`; `content_type_should_be_application_json_without_charset`; `if_none_match_should_give_304_for_strong_weak_list_and_star`; `a_stale_if_none_match_should_give_200`; `no_content_encoding_and_content_length_should_equal_the_byte_count`. `HearingDaySharesControllerTest`: `should_list_in_shared_at_order`; `an_empty_day_should_be_404_hearing_day_not_found`. `ReadApiExceptionHandlerTest`: `each_spring_mvc_exception_type_should_render_the_four_fields_only` (method not supported → `405`, not acceptable → `406`, no resource → `404 route_not_found`, missing parameter, type mismatch, message not readable → `400 bad_request`); `a_connection_failure_or_query_timeout_should_be_503_store_unavailable_with_retry_after_5`; `any_other_exception_should_be_500_internal_error_logged_by_class_without_its_message` (`support/CapturedLog`). `ReadMetricsInterceptorTest`: `status_should_map_to_outcome` (200 `ok`, 304 `not_modified`, 400, 405, 406 and 415 `bad_request`, 404 `not_found`, 503 `unavailable`, 500 `failed`; the table in contracts/metrics.md); `the_endpoint_should_come_from_the_route_attribute`; `a_request_with_no_route_attribute_should_record_nothing`. `OpenApiContractTest`: `every_controller_mapping_should_be_described`; `every_described_route_should_have_a_controller_mapping`; `every_api_route_should_have_a_drl_rule_and_a_controller_mapping`; `every_path_parameter_should_be_named_as_the_controller_names_it`.
  - Notes: no manual `checkNotModified`; `ResponseEntity.ok().eTag(…).body(bytes)` (research R11). Single exit where PMD needs it. `spring.mvc.problemdetails.enabled` stays unset.
  - Covers: FR-002–FR-008, FR-026 and FR-027 (parameter half), FR-031–FR-037, FR-039, FR-042–FR-044, FR-053 (both ways), FR-055 (requests and duration); SC-003, SC-006 (slice half).
  - Done when: the eight test classes green; the gate green.

- [ ] T011 [US1] [US2] [US3] [US4] [US5] [US6] [US7] Test first: `ReadApiIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/ReadApiIT.java, `AuditIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/AuditIT.java, `NoPayloadInLogsIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/NoPayloadInLogsIT.java, and (D-AUDIT option 4, E1) `PayloadBodyFreeAuditPayloadGenerationServiceTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/PayloadBodyFreeAuditPayloadGenerationServiceTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/filters/PayloadBodyFreeAuditPayloadGenerationService.java and its bean in src/main/java/uk/gov/hmcts/cp/resultsstore/config/ApiWebConfig.java (`@ConditionalOnProperty(cp.audit.enabled=true)`), and any fix the tests find
  - Cases (`ReadApiIT`: Testcontainers Postgres, MockMvc, `authz.http.enabled=true`, WireMock usersgroups matched on `CJSCPPUID` with a "System Users" id, a "Second Line Support" id and an "Other Group" id; short intake timeouts so the lag is about 5 s; shares stored through a `JdbcShareStore` built over the context's data source with the `support/SampleShares` samples): `each_endpoint_should_serve_a_system_users_caller`; `each_endpoint_should_serve_a_second_line_support_caller`; `each_endpoint_should_refuse_a_caller_in_neither_group_403_with_a_bounded_body`; `each_endpoint_should_refuse_no_identity_401_with_a_bounded_body`; `a_spoofed_cpp_action_content_type_or_accept_should_change_no_outcome` (route × spoof); `pull_should_not_return_a_share_until_the_lag_has_passed` (Awaitility); `pull_paged_to_the_end_should_present_every_share_once_in_order`; `a_filtered_pull_should_advance_the_cursor_over_non_matching_ranges`; `visible_up_to_should_be_present_and_behind_the_database_clock_by_the_lag`; `a_failed_share_presented_by_a_not_false_pull_should_show_its_key_details_on_re_read_after_the_sweep_fixes_it_and_not_be_re_presented` (the unknown-row obligation, FR-023); `a_court_pull_should_never_present_a_failed_share_even_after_the_sweep_fills_its_court` (E5, FR-012); `search_by_day_form_and_by_time_form_should_serve_both_groups` (E6); `payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key` (E8; working copy and arrived-text form); `if_none_match_should_give_304`; `a_query_held_by_a_lock_should_give_503_store_unavailable_with_retry_after`; `an_unmapped_path_should_be_404_route_not_found_and_counted`; `the_read_meters_should_move_as_contracts_metrics_says`. `AuditIT` (`audit.http.enabled` and `cp.audit.enabled` on, `EmbeddedBrokerSupport` consuming the library's audit destination): `the_request_event_should_carry_the_derived_cpp_action_and_the_share_id_path_parameter`; `the_payload_response_event_should_carry_the_marker_and_no_payload_byte` (option 4, E1); `a_list_response_event_should_carry_the_page`; `a_304_should_publish_no_response_event`; `a_multipart_request_should_be_refused_415_and_publish_nothing`; `a_401_and_a_403_should_publish_nothing` (refused by authorisation, so they never reach the audit filter); `the_response_info_context_path_should_be_the_form_the_override_matches`; `with_the_audit_wrapper_the_payload_should_keep_content_length_and_no_chunked_encoding`. `NoPayloadInLogsIT`: `serving_a_payload_should_log_no_payload_marker_at_any_level` (root at DEBUG). `PayloadBodyFreeAuditPayloadGenerationServiceTest`: `the_payload_routes_should_be_replaced_by_the_marker`; `every_other_route_should_be_left_to_the_library`; `the_bean_should_exist_only_with_cp_audit_enabled`.
  - Notes: option 2 (a body-exclusion switch from the library owners) is asked for in parallel; when it ships the option-4 class is deleted (Deferred). The DPIA records both.
  - Reviewers: from phase B until T012's commit, the code departs from Principle VII 2.1.0 (refused requests unaudited; the payload body replaced) and from Principle II 2.1.0 (served bodies without `_metadata`). plan.md *Complexity Tracking* records the departure as the constitution allows; do not block T011 on it.
  - Covers: US1–US7 end to end; FR-012, FR-014–FR-016, FR-022–FR-030, FR-039 (no `_metadata` served), FR-044, FR-049, FR-051, FR-052; SC-001–SC-006, SC-010, SC-011.
  - Done when: the four test classes green; the gate green.

- [ ] T012 [US1] [US2] [US3] [US5] [US7] Smoke, compose and documents. Test first: extend scripts/container-smoke.sh so it fails on the pre-003 build, with the HTTP checks of spec FR-060 after the published share is stored (pull lists the `shareId` once the derived lag has passed; one share `200`; `/payload` `200` with `sha256sum` of the body equal to the unquoted `ETag` and `jq 'has("_metadata")'` false; `If-None-Match` `304`; day versions `200`; no `CJSCPPUID` `401`; the no-group `CJSCPPUID` `403`; `/results-store/v1/anything` `404` with reason `route_not_found` and no path in the body; a vendor `Accept` on pull still `200`; `resultsstore_read_requests_total` and `resultsstore_read_refused_total` present); and the review grep (RED: the hits before the edits) for `lowest sequence number`, `still-open write`, `library's default settings`, `include-payload-body`, `caller-supplied`, `carry no request or response bodies` across `specs/`, `.specify/memory/constitution.md` and `.claude/rules/design_rules.md`; then
  - docker-compose.yml (app service: `RESULTSSTORE_INTAKE_STORE_TRANSACTIONTIMEOUT`, `…_STATEMENTTIMEOUT`, `…_LOCKTIMEOUT`, `…_IDLEINTRANSACTIONTIMEOUT` through `${VAR:-default}` with short values (transaction 6 s, statement 2 s, lock 1 s, idle-in-transaction 1 s; derived lag 11 s), contracts/configuration.md *Compose*; `RESULTSSTORE_READ_PULL_VISIBILITYLAG` left unset), docker/wiremock/mappings/identity-stub.json (a low priority, so it is the default), docker/wiremock/mappings/identity-no-group.json (new: matched on `CJSCPPUID` `11111111-1111-4111-8111-111111111111`, priority 1, groups "Other Group");
  - .specify/memory/constitution.md: version 2.1.0 → 2.2.0 (MINOR), Principle VII reworded to research R20's text (the derived action; both groups and the method-and-path match; *every request that reaches an endpoint is audited; a request refused by a filter or by authorisation is counted*; the payload marker sentence, E1, E13); Principle II's read-API sentence reworded to *The read API serves the working copy without the message envelope's metadata (`_metadata`), and the text, likewise without it, when the working copy is empty* (E8), with the arrived-text clause *and, on its own endpoint, the text as it arrived, likewise without the envelope metadata* (E2), both in 2.2.0, so T013 touches no constitution; Sync Impact Report updated (modified principles, templates checked, follow-ups); **Last Amended** set;
  - `.claude/rules/design_rules.md`: the *Pull safety* paragraph replaced by the visibility bound, 90 s (research R4, R7); *Security*: the action derived for every request with vendor media types neutralised, rules admitting "System Users" and "Second Line Support" and matching method and path, the audit wording above; the read API table: the payload row says "without `_metadata`", search gains the time form, and the arrived row is added (E2, E6, E8);
  - specs/001-share-intake/spec.md (*Out of scope* "the search indexes consumers need: spec 003" and *Assumptions* "Consumer search indexes are left to spec 003") and specs/001-share-intake/data-model.md ("spec 003 adds them with the read API"): an *Amended by spec 003* note pointing at V5; specs/001-share-intake/contracts/metrics.md *Not in 001*: a pointer to specs/003-read-api/contracts/metrics.md; specs/001-share-intake/contracts/configuration.md: an *Amended by spec 003* note on the statement (10 s) and lock (5 s) defaults and the pool backstop (FR-061, FR-062); specs/002-enrichment/spec.md FR-041, specs/002-enrichment/contracts/schema.md rule 5 and specs/002-enrichment/page-notes.md §3: an *Amended by spec 003* note: the served bytes are the working copy without `_metadata` (E8);
  - specs/003-read-api/contracts/*.md and quickstart.md checked against what was built and corrected; specs/003-read-api/page-notes.md reconciled; specs/003-read-api/plan.md constitution reference 2.2.0; specs/003-read-api/spec.md status set to Implemented (phases A to C);
  - `/speckit-analyze` (read-only) over spec.md, plan.md and tasks.md with the constitution, research, data-model and contracts as context; every CRITICAL and HIGH finding resolved, MEDIUM fixed or listed below with a reason; the result recorded under this task; `.specify/scripts/bash/check-prerequisites.sh --require-tasks --include-tasks --json` exits 0;
  - the Deferred list below, completed with anything the phases left open.
  - Covers: FR-012 and FR-039 (the contract text checked against what was built), FR-021 (document half), FR-022–FR-025 and FR-040 (likewise), FR-057, FR-058, FR-059, FR-060; SC-012, SC-013.
  - Done when: `scripts/container-smoke.sh` prints `PASS` with every check `ok` (the RED run quoted: the HTTP checks fail on the build before phase A); the review grep shows no hit that is not reworded or marked historical; `/speckit-analyze` reports no CRITICAL or HIGH finding; the gate green.
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
    - a youth-raised feed for spec 004's held `FALSE`→`TRUE` changes (D-YOUTH-RAISE).

---

## Phase D: arrived text (T013; D-RAW accepted, E2)

**Purpose**: the text as it arrived, without the envelope metadata (E8), on its own endpoint, action and
rule. Its own phase so phases A to C never wait on it. Depends on phase C.

**Independent test**: `ReadApiIT` serves the arrived text without `_metadata`, with `ETag` equal to the
SHA-256 of the body and not equal to `payload_sha256`.

- [ ] T013 [US8] Test first, contract first within the task: `ResultsStoreRulesTest`, `OpenApiDocumentTest`, `ApiRouteTest` and `OpenApiContractTest` gain the route and action through their parameterised sources (red until the contract changes); then src/main/resources/results-store-openapi.yaml, src/main/resources/acl/results-store-rules.drl (`results-store.get-share-arrived-payload`), src/main/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRoute.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReadEndpoint.java (+ `ARRIVED_PAYLOAD`); then test first `JdbcShareQueriesIT`, `ShareReadServiceTest`, `SharePayloadControllerTest`, `ReadApiIT`, `AuditIT`, `MicrometerReadObserverTest` cases below; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareQueries.java (+ `arrivedText`), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueries.java (identity columns and `p.payload_text`; `payload_sha256` not read), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadService.java (strips with T005's `EnvelopeMetadata`, hashes the served bytes), src/main/java/uk/gov/hmcts/cp/resultsstore/api/SharePayloadController.java, src/main/java/uk/gov/hmcts/cp/resultsstore/filters/PayloadBodyFreeAuditPayloadGenerationService.java (covers the route); no constitution change (T012 wrote the Principle II clause in 2.2.0); specs/003-read-api/contracts/read-api.md §4.6 checked against what was built
  - Cases: `JdbcShareQueriesIT.arrived_text_should_return_payload_text_with_the_identity_columns`; `ShareReadServiceTest.arrived_body_should_have_no_metadata_and_its_etag_should_be_the_sha256_of_the_served_bytes`; `ShareReadServiceTest.arrived_etag_should_never_equal_payload_sha256`; `SharePayloadControllerTest.arrived_body_should_be_byte_identical_to_the_service_bytes_with_form_arrived_text`; `ReadApiIT.arrived_should_serve_both_groups_and_refuse_a_caller_in_neither`; `ReadApiIT.arrived_body_parsed_should_equal_the_published_message_without_metadata` (no application results added); `ReadApiIT.arrived_if_none_match_should_give_304`; `AuditIT.arrived_response_event_should_carry_the_marker`; `MicrometerReadObserverTest`: `arrived_payload` registered with `not_modified`.
  - Covers: FR-001 (arrived route), FR-038 (arrived query), FR-041, FR-046 (arrived action), FR-049 (its rule); US8; SC-014.
  - Done when: every named test class green; the gate green; the smoke still `PASS`.

---

## Dependencies & Execution Order

### Phase dependencies

- Before phase A (done) → Phase A → Phase B → Phase C → Phase D. Each phase starts only
  after the previous phase-gate run has ended at PASS.
- Phase B needs from phase A: `ApiRoute`, `ReadEndpoint`, `RouteRefusal` (T001), `ProblemReason` (T002).
- Phase C needs from phase B: V5 (T004), the read types and ports (T005), `JdbcShareQueries` (T006),
  `ShareReadService` (T007), and the overrun threshold seam and the new intake defaults (T008).
- Phase D needs phase C complete.

### Within phases

- Phase A: T001 → T002 (filters over the route table) → T003 (registers the filter of T002; uses
  `ProblemReason` of T002).
- Phase B: T004, T005 and T008 touch disjoint files and may run in any order; T006 needs T004 (the plan
  test needs V5's indexes) and T005 (the read types); T007 needs T005 and the port of T005 (it mocks
  `ShareQueries`).
- Phase C: T009 → T010 (controllers need T009's beans) → T011 (end to end) → T012 last (it records the
  analysis of the finished range).

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
