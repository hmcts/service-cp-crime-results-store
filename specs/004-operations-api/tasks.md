---

description: "Task list for feature 004: operations API"
---

# Tasks: Operations API

**Input**: Design documents from `/specs/004-operations-api/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R22), data-model.md, contracts/ (operations-api,
metrics, configuration, schema), quickstart.md; spec 003 implemented (phases A to C); constitution 2.2.0
(2.3.0 at the end of T011)

**Tests**: Mandatory (Principle X). Each task names its test classes and cases first, then the
production files. The implementer writes the tests, runs them, records the RED run under the task (a
failing assertion, never a compile error: land compile-safe seams first), then writes the minimum
production code and records the GREEN run. One commit per task, the test at or before the production
code. A task is ticked (`[X]`) only on a green full-suite run, in the commit that completes it.

**Organisation**: four phases (A, B, C, D), exactly as plan.md *Phase plan* and the orchestrator's ruling
C11. Each phase is one run of the repository's phase-gate workflow over a contiguous task range, and each
phase's range must be green on its own. The user stories cut across the phases (contract and schema,
then persistence and the sweep, then serving), so every task carries the tags of the stories it serves;
the story-to-task map is under *Dependencies*.

**Pending decisions**: every task applies the defaults of spec.md *Decisions pending Sachin*. Where a
decision changes a task, the task says so (T002 D-RERUN-GUARD, D-NEVER-BLANK, D-RERUN-ERASURE; T005
D-YOUTH-RAISE, D-RERUN-CANCEL; T006 D-SWEEP-ROUND; T007 D-RERUN-BOUNDS, D-RECON-CLOCK, D-R1-WINDOW, D-R2;
T011 D-PRINCIPLE-I-BUMP).

**Spec 003's names**: 004 uses the classes spec 003's documents name (`ApiRoute`, `ActionHeaderFilter`,
`ProblemReason`, `BadParameterException`, `ReadApiExceptionHandler`, `ReadMetricsInterceptor`,
`InstantFormat`, `ReadApiConfig`, `OpenApiDocumentTest`, `OpenApiContractTest`, `AuditIT`). If spec 003's
implementation renamed one, the task uses the new name and records it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (disjoint files, no dependency on an unfinished task)
- **[USn]**: the user story (spec.md) the task serves
- Paths are repository-relative and written out in full: `src/main/java/uk/gov/hmcts/cp/resultsstore/…`,
  `src/test/java/uk/gov/hmcts/cp/resultsstore/…`.

## Phase-gate invocations

One run per phase, in order. `baseCommit` is `git rev-parse HEAD` in the tree at the moment the phase
starts (for phase A, the commit that adds this file, rebased onto spec 003's implemented tip). Each run
needs the previous phase's run to have ended with every reviewer at PASS.

| Phase | Invocation (paths absolute) |
|---|---|
| A | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/004-operations-api", "tasks": ["T001","T002","T003"], "baseCommit": "<HEAD at phase A start>", "codex": true, "maxRemediations": 1}` |
| B | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/004-operations-api", "tasks": ["T004","T005","T006"], "baseCommit": "<HEAD at phase B start>", "codex": true, "maxRemediations": 1}` |
| C | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/004-operations-api", "tasks": ["T007","T008","T009"], "baseCommit": "<HEAD at phase C start>", "codex": true, "maxRemediations": 1}` |
| D | `{"tree": "/home/sachin/moj/service-cp-crime-results-store", "specDir": "/home/sachin/moj/service-cp-crime-results-store/specs/004-operations-api", "tasks": ["T010","T011"], "baseCommit": "<HEAD at phase D start>", "codex": true, "maxRemediations": 1}` |

## Gate used by every task's "Done when"

`flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew build pmdMain pmdTest jacocoTestReport`
exits 0 (JDK 25). `build` runs `check`, which includes `jacocoTestCoverageVerification` (line 0.88,
branch 0.85; `config/**` excluded). Written below as **the gate**.

## Rules for every task

- A unit test per production class; an IT on Testcontainers Postgres (`support/PostgresTestSupport`),
  the embedded Artemis broker (`support/EmbeddedBrokerSupport`) or in-process WireMock for every
  persistence, messaging and HTTP path.
- Latches or Awaitility, never `Thread.sleep` (`failFast` is on).
- No payload, message text, rerun reason, operator id or problem body content beyond the four fields in
  assertion messages or log output; ids only. Marker checks compare booleans, so a failure prints no
  content.
- WireMock stubs and `verify()` match the exact path and the `CJSCPPUID` header; no global counts, no
  `resetAll()` on a shared server.
- Explicit imports only; constructor injection; records for responses; PMD clean. **`OnlyOneReturn`**:
  the body parser, `OperationsParameters`, the `RerunSelector` factories, `ReconciliationWindow`, the
  sweep's outcome decision and the store's outcome switch use single-exit style or a site suppression
  with a reason (research R22). A `RuntimeException` caught to record an outcome carries the existing
  `AvoidCatchingGenericException` suppression with its reason.
- Migrations V1 to V5 are never edited. 004 adds V6 only.
- No SQL is built from input; every query is a fixed constant with bound parameters.
- `reason` and `requested_by` are never logged, never put in an exception message, never returned.

## Wiring notes

Each phase must be green on its own, so the beans arrive in this order:

- **T001 adds routes and rules before any controller.** A call to an operations path now gets its
  derived action; with no controller, Spring MVC raises `NoResourceFoundException`, which spec 003's
  advice answers `404 route_not_found`. `OpenApiContractTest` lets an `x-planned` path have no controller
  mapping until T008 (a "route" there is a controller mapping, not an `ApiRoute` constant).
- **T002 replaces the share guard.** The existing `FAILED`-path writes pass the new version guard (both
  raise attempts and set the time with the current version), so `JdbcShareStoreIT`, `ExtractionSweepIT`,
  `YouthSeenIT` and `ShareChainIT` stay green unchanged. `FlywayMigrationIT`'s `OK`-row refusal case is
  re-parameterised in the same task.
- **T003 and T004 build no bean.** `JdbcRerunRequests` and `JdbcOperationsQueries` are constructed in
  their tests; nothing injects them until T007.
- **T005 changes `ExtractionSweep`'s constructor** (+ `SweepObserver`, rerun settings) and adds the
  unconditional `SweepObserverConfig` with `MicrometerSweepObserver`, so `SweepSchedulingConfig` has the
  bean it needs in phase B. Every test that builds `ExtractionSweep` is updated in the same commit.
- **T006 changes it again** (+ `SweepRounds`), and `SweepSchedulingConfig` builds `JdbcSweepRounds` with
  the sweep (same conditions).
- **T007 wires the operations beans unconditionally** (`OperationsConfig`). The
  `ApplicationContextRunner` tests that load it use spec 003's stub `DataSource` pattern.
- **T008 adds component-scanned controllers.** Every context stays green because T007's beans exist. The
  `@WebMvcTest` slices mock the services and `OperationsObserver` with `@MockitoBean` and run with
  `@AutoConfigureMockMvc(addFilters = false)`.
- **Authorisation and audit are switched on per test class.** `OperationsApiIT` and `AuditIT` (T009)
  switch them on for themselves; the `test` profile keeps them off.

---

## Before phase A (done, no task id)

- Branch `004-operations-api` from `003-read-api` at `b74c43b`; `.specify/feature.json` points at
  `specs/004-operations-api`.
- spec.md (Draft), plan.md, research.md, data-model.md, contracts/, quickstart.md and page-notes.md
  written. Every D-item of the rulings that touches 004 is in spec.md *Decisions pending Sachin* with its
  default applied.
- Before phase A starts, the branch is rebased onto spec 003's implemented tip, so `baseCommit` holds
  003's code.

---

## Phase A: contract, schema, types (T001–T003)

**Purpose**: the OpenAPI paths, the allow rules and the routes first (constitution, *Development
Workflow*: the contract before the code that serves it); then V6 with every guard; then the domain
values and ports. Nothing serves an operations request yet. Blocks phase B.

**Independent test**: `ResultsStoreRulesTest`, `OpenApiDocumentTest` and `ApiRouteTest` prove the
contract; `OperationsSchemaIT` proves every guard by name; the domain tests prove the selector, reason,
outcome and window rules.

- [ ] T001 [US5] Test first: `ResultsStoreRulesTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/acl/ResultsStoreRulesTest.java, `OpenApiDocumentTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiDocumentTest.java, `OpenApiContractTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiContractTest.java, `ApiRouteTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRouteTest.java, `ActionHeaderFilterTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/filters/ActionHeaderFilterTest.java, `ReadMetricsInterceptorTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReadMetricsInterceptorTest.java, `OperationsEndpointTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/OperationsEndpointTest.java; then src/main/resources/results-store-openapi.yaml (the four paths with `x-planned: true` on each path item; request schema `RerunRequest`; response schemas `RerunAccepted`, `ExtractionStatus`, `Receipts`, `DailyReconciliation` as contracts/operations-api.md; `ProblemDetail` shared; the `reason` field's description says it must hold no personal data; `info.version` 0.3.0), src/main/resources/acl/results-store-rules.drl (four allow rules, "Second Line Support" only, each matching its route's method and path in the form spec 003's T001 recorded), src/main/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRoute.java (+ `RERUN_EXTRACTION`, `GET_EXTRACTION_STATUS`, `LIST_RECEIPTS`, `GET_DAILY_RECONCILIATION`; the tag component typed `RouteEndpoint`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/RouteEndpoint.java (sealed interface, `tag()`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReadEndpoint.java (implements `RouteEndpoint`), src/main/java/uk/gov/hmcts/cp/resultsstore/domain/OperationsEndpoint.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReadMetricsInterceptor.java (records only `ReadEndpoint` routes)
  - Cases: `ResultsStoreRulesTest` (parameterised over `ApiRoute`): `every_operations_action_should_be_allowed_for_second_line_support`; `every_operations_action_should_be_refused_for_system_users`; `every_operations_action_should_be_refused_for_a_caller_in_neither_group`; `every_read_action_should_still_be_allowed_for_system_users_and_second_line_support`; `an_operations_action_name_with_another_method_or_path_should_be_refused` (`GET` for the rerun; the status path for the receipts action); `every_operations_rule_should_name_exactly_second_line_support` (spec 003's `every_rule_should_name_exactly_the_two_groups` is narrowed to read rules); `there_should_be_exactly_one_rule_per_action` (now nine or ten). `OpenApiDocumentTest`: `the_paths_should_be_exactly_the_api_route_templates` (now with the four); `the_rerun_operation_should_declare_its_request_body_and_202`; `every_operations_response_should_declare_the_problem_body_for_4xx_and_5xx`; `x_planned_should_appear_only_on_operations_paths`. `OpenApiContractTest`: `an_x_planned_path_may_have_no_controller_mapping`; `every_path_without_x_planned_should_have_a_controller_mapping` (spec 003's two-way cases keep holding for the read paths). `ApiRouteTest`: `each_operations_template_should_match_its_sample_path_and_no_other_route`; `allowed_methods_should_be_post_for_the_rerun_and_get_for_the_other_three`; `every_operations_action_should_be_kebab_verb_noun_with_the_results_store_operations_prefix`. `ActionHeaderFilterTest` (parameterised over the four routes × {caller `CPP-ACTION` naming a read action, vendor `Content-Type`, vendor `Accept`, none}): `every_operations_route_should_carry_its_derived_action_whatever_the_caller_sent`; `a_vendor_json_content_type_on_the_rerun_should_be_answered_as_application_json`; `get_on_the_rerun_path_should_be_refused_405_with_allow_post`; `an_unmapped_operations_path_should_be_refused_404_route_not_found`. `ReadMetricsInterceptorTest`: `an_operations_route_should_record_no_read_meter`. `OperationsEndpointTest`: `every_tag_should_come_from_the_fixed_list` (contracts/metrics.md).
  - Covers: FR-001, FR-042, FR-043, FR-044 (route half); contracts/operations-api.md §1, §2.1.
  - Done when: the seven test classes green; spec 003's tests green; the gate green.

- [ ] T002 [P] [US1] [US5] (D-RERUN-GUARD option A, D-NEVER-BLANK allow, D-RERUN-ERASURE no delete guard) Test first: `OperationsSchemaIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/OperationsSchemaIT.java, `FlywayMigrationIT` (updated) in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/FlywayMigrationIT.java; then src/main/resources/db/migration/V6__operations.sql (data-model.md, in full)
  - Cases (`OperationsSchemaIT`, Testcontainers; rows inserted with SQL as `FlywayMigrationIT` does): `an_ok_row_should_be_rewritten_in_place_while_a_pending_item_names_it`; `an_ok_row_rewrite_should_be_refused_by_the_rerun_guard_with_no_item`; `an_ok_row_rewrite_should_be_refused_once_its_item_is_done`; `an_ok_row_moved_to_failed_should_be_refused_by_the_projection_guard_even_with_a_pending_item`; `a_true_youth_subject_lowered_to_false_or_null_should_be_refused_by_the_youth_guard`; `a_false_youth_subject_raised_to_true_should_be_accepted_by_the_database` (the hold is the sweep's, T005); `a_key_detail_moved_to_null_on_an_ok_row_should_be_accepted` (D-NEVER-BLANK); `a_version_lowered_attempts_not_raised_or_projected_at_moved_back_should_be_refused_by_the_version_guard` (on an `OK` and on a `FAILED` row); `the_guards_should_fire_in_order` (a change breaking several names the first); `v3_s_failed_row_retry_should_still_be_accepted` (the `SET_EXTRACTED` and `SET_FAILED_AGAIN` shapes); `v3_s_fixed_column_refusals_should_still_hold` (the eleven columns, parameterised); `chain_youth_propagation_and_projection_tried_at_updates_should_be_unaffected`; `a_second_pending_item_for_one_share_should_be_refused`; `a_done_item_should_never_change`; `an_item_s_key_should_never_change`; `a_request_s_fixed_columns_should_never_change`; `a_done_request_should_stay_done`; `a_second_open_request_for_one_selector_should_be_refused`; `items_before_their_request_should_commit_and_items_with_no_request_should_fail_at_commit` (the deferred key); `reason_length_counts_unknown_and_status_checks_should_hold`; `an_item_outcome_outside_the_nine_should_be_refused`; `deleting_a_request_or_an_item_should_be_accepted` (no delete guard); `sweep_round_checks_should_hold` (pod pattern, clock, counts, version); `v6_objects_should_exist_with_their_names` (`pg_indexes`, `pg_trigger`, partial predicates). `FlywayMigrationIT`: the version list gains `"6"`; `update_of_an_ok_share_s_key_details_or_projection_should_be_refused` re-parameterised as (assignment, expected guard): an assignment that does not raise `projection_attempts` → `hearing_share_projection_version_guard`; the same with `projection_attempts = projection_attempts + 1, projected_at = clock_timestamp()` → `hearing_share_rerun_guard`; the move to `FAILED` with attempts raised → `hearing_share_projection_guard`; its javadoc ("an OK share's key details and projection are final") reworded to "only while a pending rerun item names it"; the V1 to V5 checksum cases unchanged.
  - Notes: the fixed-columns branch is copied from V3 character for character; the review diffs it against `V3__create_share_store.sql`.
  - Covers: FR-020, FR-021 (database half), FR-022 (database half), FR-029, FR-030, FR-048 (tables and guards); contracts/schema.md rules 1–6, 9, 11; SC-002.
  - Done when: both test classes green; every existing persistence IT green unchanged; the gate green.

- [ ] T003 [P] [US1] [US4] Test first: `RerunSelectorTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/RerunSelectorTest.java, `SelectorKindTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/SelectorKindTest.java, `RerunReasonTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/RerunReasonTest.java, `OperatorIdTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/OperatorIdTest.java, `RerunRowOutcomeTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/RerunRowOutcomeTest.java, `ReconciliationWindowTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/domain/ReconciliationWindowTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/domain/RerunSelector.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/SelectorKind.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/RerunReason.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/OperatorId.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/RerunRowOutcome.java, src/main/java/uk/gov/hmcts/cp/resultsstore/domain/ReconciliationWindow.java; the ports src/main/java/uk/gov/hmcts/cp/resultsstore/application/RerunRequests.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/OperationsQueries.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/SweepRounds.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/OperationsObserver.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/SweepObserver.java; the records src/main/java/uk/gov/hmcts/cp/resultsstore/application/RerunRequest.java, RerunCreation.java (sealed: `Created`, `Repeat`, `TooWide`, `RangeNotSettled`), RerunAccepted.java, RerunCandidate.java, ExtractionCounts.java, RerunOverview.java, RecentRerun.java, SweepRoundView.java, SweepRoundRecord.java, ReceiptView.java, DailyCounts.java, R1Finding.java, ExtractionStatus.java, DailyReconciliation.java, RoundResult.java (all under src/main/java/uk/gov/hmcts/cp/resultsstore/application/)
  - Cases: `RerunSelectorTest`: `canonical_text_should_sort_lower_case_and_deduplicate_ids`; `the_same_selector_written_differently_should_give_the_same_text` (id case, order, repeats; an instant with `+01:00` and its `Z` form); `instants_should_be_written_utc_with_six_fraction_digits`; `more_than_six_fraction_digits_should_be_refused`; `a_range_should_be_half_open_and_from_before_to`; `the_canonical_text_should_be_fixed_order_json`. `SelectorKindTest`: `tags_should_be_lower_snake`. `RerunReasonTest`: `should_trim_then_accept_10_to_500_characters` (boundaries 9, 10, 500, 501); `a_control_character_should_be_refused` (tab, newline, `\u0000`, `\u007f`); `to_string_should_never_print_the_reason`. `OperatorIdTest`: `a_canonical_uuid_should_be_accepted_and_anything_else_refused`; `to_string_should_never_print_the_id`. `RerunRowOutcomeTest`: `tags_should_be_lower_snake_and_the_twelve_of_contracts_metrics`; `stored_should_be_exactly_the_nine_the_item_check_lists`. `ReconciliationWindowTest`: `a_gmt_day_should_be_24_hours_from_london_midnight`; `29_march_2026_should_be_23_hours`; `25_october_2026_should_be_25_hours`; `a_bst_day_should_start_at_23_00_utc_the_day_before`; `a_date_after_london_today_should_be_refused_and_today_allowed_and_partial` (injected clock at 23:30 UTC on a BST evening: London's tomorrow already).
  - Covers: FR-006–FR-010 (value rules), FR-015 (canonical form), FR-018 (outcome names), FR-038 (window); data-model.md *In-memory types*.
  - Done when: the six test classes green; the gate green.

---

## Phase B: persistence and the sweep (T004–T006)

**Purpose**: the request writer and the read-only queries; the sweep's rerun path in place; the round
record. Nothing is served over HTTP yet. Depends on phase A (V6, the types and ports).

**Independent test**: `JdbcRerunRequestsIT` proves counts, repeats, races and bounds; `JdbcOperationsQueriesIT`
proves every read; `RerunSweepIT` proves every outcome and the claim across pods; `JdbcSweepRoundsIT`
proves the round record.

- [ ] T004 [US1] [US2] [US3] [US4] Test first: `JdbcRerunRequestsIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcRerunRequestsIT.java, `JdbcOperationsQueriesIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcOperationsQueriesIT.java, `OperationsSqlTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/OperationsSqlTest.java, `OperationsQueriesPlanIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/OperationsQueriesPlanIT.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcRerunRequests.java (data-model.md *Rerun request*; its own `TransactionOperations` and timeouts passed in), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcOperationsQueries.java (data-model.md *Operations reads*; its own `JdbcTemplate` passed in; autocommit), src/test/java/uk/gov/hmcts/cp/resultsstore/support/SampleShares.java (rerun fixtures: shares with chosen `stored_at`, `FAILED` rows of each kind, receipts of each status with chosen times)
  - Cases (`JdbcRerunRequestsIT`, Testcontainers): `a_share_list_should_create_one_request_and_one_item_per_known_share_and_count_unknown_ids`; `a_hearing_list_should_select_every_share_of_every_day_of_those_hearings`; `a_stored_range_should_select_half_open_on_stored_at`; `a_stored_range_ending_inside_the_lag_should_be_range_not_settled_and_write_nothing`; `matched_over_the_cap_should_be_too_wide_and_write_nothing`; `the_maximum_request_should_commit_within_its_transaction_timeout` (200,000 shares, chunk 5,000; SC-005); `the_max_hearing_ids_case_should_be_bounded_before_the_insert` (200 hearings × several days); `a_share_pending_elsewhere_should_count_already_pending_and_not_be_queued`; `matched_minus_already_pending_should_equal_the_request_s_items`; `matched_zero_should_store_the_request_done`; `a_repeat_while_open_should_return_the_same_request_and_write_nothing` (pre-read path); `two_identical_requests_at_once_should_leave_one_request` (two threads, a latch; the second answers `Repeat`; SC-004); `a_request_that_closes_between_insert_and_lookup_should_be_retried_once` (a test hook closes it between the two steps); `the_same_selector_after_done_should_create_a_new_request`; `a_request_being_written_should_not_block_intake_youth_propagation_or_a_no_key_update_lock` (a held request transaction, another connection's `UPDATE hearing_share SET day_youth_seen …` and `SELECT … FOR NO KEY UPDATE` finish within 1 s); `the_request_transaction_should_set_its_own_timeouts`. `JdbcOperationsQueriesIT`: `failed_counts_should_follow_the_sweep_s_rule` (retryable, exhausted, awaiting new extractor); `pending_open_abandoned_and_held_counts_and_newest_share_ids_should_match_with_truncation_at_50`; `recent_requests_should_be_the_newest_20_any_status_with_outcome_and_pending_counts`; `sweep_rounds_should_be_the_recent_pods_newest_first`; `receipts_by_day_should_be_ordered_and_capped_with_one_extra_row`; `a_receipt_by_message_id_should_be_found_exactly` (`ID:x` versus `id:x`); `no_receipt_response_should_ever_hold_message_text` (a marker seeded in `message_text`); `daily_receipt_counts_should_use_the_london_window_on_first_received_at`; `daily_share_counts_should_use_the_window_on_stored_at`; `r1_should_cut_off_on_last_received_at_and_keep_window_membership_on_first_received_at`; `r1_should_list_the_oldest_50_and_count_all`; `a_query_held_by_a_lock_should_time_out_as_a_query_timeout` (1 s template timeout; Spring's `QueryTimeoutException` family). `OperationsSqlTest`: `no_constant_should_name_hearing_share_payload_or_message_text`; `the_receipts_select_should_name_exactly_its_twelve_columns_and_no_star`; `no_constant_should_select_reason_or_requested_by_except_the_request_insert`. `OperationsQueriesPlanIT` (`EXPLAIN (FORMAT JSON)` of the constants, `enable_seqscan` off on a connection the test owns): `the_stored_range_selector_should_use_hearing_share_stored_at_ix`; `the_hearing_list_selector_should_use_hearing_share_identity_uk`; `daily_receipts_should_use_event_receipt_first_received_ix`; `daily_shares_should_use_hearing_share_stored_at_ix`; `r1_should_use_a_received_partial_index`; `failed_counts_should_use_hearing_share_sweep_ix`; `receipts_by_day_should_use_event_receipt_hearing_day_ix`.
  - Covers: FR-004, FR-007 (lag half), FR-008, FR-009 (unknown ids), FR-012–FR-016, FR-031 (data half), FR-034–FR-036 (data half), FR-038–FR-041 (data half), FR-047 (timeout); contracts/schema.md rules 5, 7, 8, 10; data-model.md invariants 2, 3, 5; SC-004, SC-005, SC-006 (data half).
  - Done when: the four test classes green; the gate green.

- [ ] T005 [US1] [US6] (D-YOUTH-RAISE, D-NEVER-BLANK, D-RERUN-CANCEL defaults) Test first: `ExtractionSweepTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweepTest.java, `RerunSweepIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/RerunSweepIT.java, `RerunConcurrencyIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/RerunConcurrencyIT.java, `MicrometerSweepObserverTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerSweepObserverTest.java, `SweepObserverConfigTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/SweepObserverConfigTest.java, `ConfigurationValidationTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java, `SweepSchedulingConfigTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfigTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/ShareStore.java (+ `claimRerunItems(int limit)`, `recordRerun(RerunCandidate, Projection, int version)`, `recordRerunError(RerunCandidate, int maxAttempts)`, `closeFinishedReruns()`), src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java (data-model.md *The sweep's rerun statements*: `CLAIM_RERUN_ITEMS`, `LOCK_RERUN_ITEM`, `LOCK_SHARE_FOR_RERUN` (`FOR NO KEY UPDATE`), `SET_REEXTRACTED`, `SET_VERSION_ONLY`, `INSERT_DEFENDANT_IF_ABSENT`, `MARK_ITEM_DONE`, `RECORD_RERUN_ERROR`, `CLOSE_FINISHED_RERUNS`; the outcome decision of spec FR-018 in that order; `fixed` and `failedAgain` reused for `FAILED` shares), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweep.java (after the `FAILED` rows: claim, read and extract outside any transaction, `recordRerun`, error path, stop, then `closeFinishedReruns`; `runRound()` returns `RoundResult`; `Settings` + `rerunBatchSize`, `rerunMaxAttempts`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerSweepObserver.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepObserverConfig.java (unconditional `SweepObserver` bean), src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepProperties.java (+ `rerunBatchSize` default 200, 1 to 1,000; `rerunMaxAttempts` default 3, 1 to 10), src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfig.java (passes the observer and settings), src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedule.java (only if the `runRound` return type needs it), src/main/resources/application.yaml (the two sweep settings)
  - Cases: `ExtractionSweepTest` (mocked ports): `rerun_items_should_be_worked_after_the_failed_rows`; `the_claim_should_ask_for_the_rerun_batch_size`; `an_ok_row_whose_re_read_fails_should_be_recorded_kept`; `a_payload_read_failure_should_go_to_the_error_path_and_count_error`; `the_error_that_reaches_the_limit_should_count_abandoned_not_error`; `a_stop_should_leave_unstarted_items_and_count_them_cancelled`; `requests_should_be_closed_at_the_end_of_every_round_and_counted_finished`; `every_outcome_should_reach_the_sweep_observer`; `no_log_line_should_hold_a_reason_or_payload` (`support/CapturedLog`). `RerunSweepIT` (Testcontainers; the store and sweep built over the test's data source; shares stored through `JdbcShareStore` or inserted with chosen key details): `an_ok_share_with_wrong_key_details_should_be_reextracted_in_place_stay_ok_and_keep_its_stored_seq`; `the_share_should_be_returned_by_a_court_search_before_during_and_after_its_item` (spec 003's search constant; SC-001); `matching_key_details_youth_and_defendants_at_the_current_version_should_be_unchanged_with_no_share_write` (`xmin` unchanged); `an_older_stored_version_should_be_unchanged_with_version_attempts_and_time_written`; `a_failed_share_should_be_fixed_or_failed_again_whatever_its_attempts`; `a_true_youth_subject_should_be_youth_kept`; `false_to_true_should_be_youth_raise_held_and_nothing_written` (D-YOUTH-RAISE); `null_to_false_and_null_to_true_should_be_written_and_the_day_flag_recomputed`; `false_to_null_should_be_written` (D-NEVER-BLANK); `a_key_detail_moved_to_null_should_be_written`; `a_newer_stored_version_should_be_newer_kept` (settings with an older version); `missing_defendant_rows_should_be_added_and_none_removed`; `an_item_no_longer_pending_should_be_skipped`; `two_sweeps_should_never_work_one_item_twice` (two `ExtractionSweep` instances, 1,000 items, batch 100, concurrent rounds; every item done once; SC-003); `an_item_failing_operationally_three_times_should_be_abandoned_and_its_request_close` (a test-only `BEFORE UPDATE` trigger on `extraction_rerun_item` raises for one share id when `NEW.state = 'DONE' AND NEW.outcome <> 'ABANDONED'`, so its write transaction fails every time while the error path still records; dropped after the test); `a_claim_should_stamp_tried_at_and_skip_items_another_transaction_holds`; `the_item_should_be_marked_done_after_the_share_write_in_one_transaction`. `RerunConcurrencyIT`: `a_rerun_write_and_intake_storing_a_new_share_of_the_same_day_should_serialise_on_the_day_lock_with_youth_consistent` (critique improvement 4); `a_request_being_written_should_not_make_the_sweep_wait` (the request's `FOR KEY SHARE` against the sweep's `FOR NO KEY UPDATE`). `MicrometerSweepObserverTest`: `every_rerun_outcome_and_the_finished_counter_should_be_registered_at_start`; `each_callback_should_move_exactly_its_meter`; `no_tag_value_should_parse_as_a_uuid_or_date`. `SweepObserverConfigTest` (`ApplicationContextRunner`): `the_sweep_observer_should_exist_with_the_subscription_and_the_sweep_off`. `ConfigurationValidationTest`: `rerun_batch_size_should_be_1_to_1000`; `rerun_max_attempts_should_be_1_to_10`. `SweepSchedulingConfigTest`: the existing cases green with the new constructor.
  - Notes: `RerunRowOutcome` decisions and the store's outcome switch are single-exit. The `FAILED` path's statements and `SweepRowOutcome` are untouched.
  - Covers: FR-017–FR-028; FR-049 (sweep meters); research R7–R11; data-model.md invariants 1, 4; SC-001, SC-003.
  - Done when: the seven test classes green; `ExtractionSweepIT` and every other existing sweep test green; the gate green.

- [ ] T006 [US2] (D-SWEEP-ROUND default) Test first: `JdbcSweepRoundsIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcSweepRoundsIT.java, `ExtractionSweepTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweepTest.java, `MicrometerSweepObserverTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerSweepObserverTest.java, `ConfigurationValidationTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcSweepRounds.java (data-model.md *Record the round*), src/main/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionSweep.java (+ `SweepRounds`, the pod name and a monotonic clock; records after every round), src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepProperties.java (+ `podName`, default `${HOSTNAME:local}`, pattern-checked), src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfig.java (builds `JdbcSweepRounds` with the sweep; `pod-recent` read from `resultsstore.operations.status.pod-recent` through a `@Value` default of `1d` until T007 binds `OperationsProperties`), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerSweepObserver.java (+ `resultsstore.sweep.round.record.failed`), src/main/resources/application.yaml (`pod-name`)
  - Cases: `JdbcSweepRoundsIT`: `a_first_round_should_insert_the_pod_s_row`; `a_later_round_should_update_it`; `an_empty_round_should_move_finished_at_and_keep_last_worked_at`; `times_should_be_the_database_clock_with_started_at_the_finish_minus_the_length`; `other_pods_older_than_pod_recent_should_be_deleted_by_the_upsert`; `the_extractor_version_should_be_recorded`; `a_pod_name_outside_the_pattern_should_be_refused_by_the_check`. `ExtractionSweepTest`: `every_round_should_be_recorded_with_its_counts_including_an_empty_one`; `a_failed_record_should_be_counted_and_the_round_complete`; `a_stopped_round_should_still_be_recorded_with_its_cancelled_rows`. `MicrometerSweepObserverTest`: `round_record_failed_should_be_registered_at_start`. `ConfigurationValidationTest`: `pod_name_should_match_the_pattern` (`UPPER`, `-x`, 254 characters refused).
  - Covers: FR-032, FR-033 (data half), FR-049 (round counter); research R12.
  - Done when: the four test classes green; the gate green.

---

## Phase C: services, web, end to end (T007–T009)

**Purpose**: wire the operations beans first, so the controllers that follow never break a context;
then the controllers, parsers and advice changes, removing the `x-planned` markers; then the API end to
end with authorisation and audit on. Depends on phase B.

**Independent test**: the service tests prove every rule and refusal on plain mocks; the controller
slices prove mapping and errors; `OperationsApiIT` proves US1–US6 end to end; `AuditIT` pins the audit
behaviour.

- [ ] T007 [US1] [US2] [US3] [US4] [US6] (D-RERUN-BOUNDS, D-RECON-CLOCK, D-R1-WINDOW, D-R2 defaults) Test first: `RerunServiceTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/RerunServiceTest.java, `ExtractionStatusServiceTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionStatusServiceTest.java, `ReceiptsServiceTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ReceiptsServiceTest.java, `ReconciliationServiceTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/application/ReconciliationServiceTest.java, `MicrometerOperationsObserverTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerOperationsObserverTest.java, `OperationsConfigTest` (`ApplicationContextRunner`, stub `DataSource`) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/OperationsConfigTest.java, `ConfigurationValidationTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/config/ConfigurationValidationTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/application/RerunService.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ExtractionStatusService.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ReceiptsService.java, src/main/java/uk/gov/hmcts/cp/resultsstore/application/ReconciliationService.java (each refusal a spec 003 `BadParameterException` carrying a `ProblemReason`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ProblemReason.java (+ the operations reasons of contracts/operations-api.md §7), src/main/java/uk/gov/hmcts/cp/resultsstore/config/OperationsProperties.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/OperationsConfig.java (unconditional: the operations `JdbcTemplate` with the query timeout, `JdbcOperationsQueries`, the request `TransactionTemplate` and `JdbcRerunRequests`, the four services, `MicrometerOperationsObserver`, `Clock`; the cross-setting rules), src/main/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerOperationsObserver.java, src/main/java/uk/gov/hmcts/cp/resultsstore/config/SweepSchedulingConfig.java (`pod-recent` from `OperationsProperties`), src/main/resources/application.yaml (the `resultsstore.operations.*` block of contracts/configuration.md)
  - Cases: `RerunServiceTest` (plain mocks): `exactly_one_selector_should_be_required` (none, two, three); `only_one_of_stored_from_and_stored_to_should_be_range_invalid`; `a_range_longer_than_max_range_should_be_range_too_long_and_31_days_accepted`; `hearing_and_share_id_lists_should_be_bounded_and_deduplicated` (0, 1, 200, 201; 1,000, 1,001); `the_reason_should_be_checked_before_the_port_is_called`; `range_not_settled_should_be_range_invalid`; `too_wide_should_be_selector_too_wide`; `created_and_repeat_should_map_to_rerun_accepted_with_queued_derived`; `the_effective_lag_should_be_passed_to_the_port`; `meters_should_move_for_created_and_repeat_and_queued_only_on_created`; `no_refusal_should_carry_a_caller_value`. `ExtractionStatusServiceTest`: `should_assemble_counts_requests_held_abandoned_and_pods_with_the_serving_version_and_max_attempts`; `twenty_one_rows_should_show_twenty_and_truncated`; `last_finished_and_last_worked_should_be_the_latest_across_pods_or_null`. `ReceiptsServiceTest`: `limit_plus_one_should_set_truncated`; `no_match_should_be_an_empty_list`. `ReconciliationServiceTest`: `the_window_should_be_london_midnight_to_midnight`; `a_future_date_should_be_date_in_future`; `today_should_be_partial`; `r1_cut_off_should_be_the_clock_minus_received_give_up`; `r2_should_be_counts_only_with_sampled_false`. `MicrometerOperationsObserverTest`: `every_meter_and_tag_combination_should_be_registered_at_start`; `each_callback_should_move_exactly_its_meter`; `no_tag_value_should_parse_as_a_uuid_or_date`. `OperationsConfigTest`: `the_operations_beans_should_exist_with_the_subscription_off`; `the_operations_template_should_carry_the_statement_timeout`; `the_request_transaction_should_carry_its_timeout`. `ConfigurationValidationTest`: one case per bound of contracts/configuration.md (each property just outside its range stops the service naming it); `request_lock_above_statement_or_statement_above_transaction_should_stop_the_service`; `a_statement_timeout_at_the_socket_timeout_should_stop_the_service`; `no_message_should_hold_a_value`.
  - Covers: FR-005–FR-016 (service half), FR-031, FR-033, FR-035, FR-037–FR-041, FR-047, FR-049 (operations meters), FR-050; SC-009, SC-010.
  - Done when: the seven test classes green; every context test green; the gate green.

- [ ] T008 [US1] [US2] [US3] [US4] [US5] [US6] Test first: `RerunBodyParserTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/RerunBodyParserTest.java, `OperationsParametersTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OperationsParametersTest.java, `ExtractionOperationsControllerTest` (`@WebMvcTest` slice) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ExtractionOperationsControllerTest.java, `ReceiptsControllerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReceiptsControllerTest.java, `ReconciliationControllerTest` in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReconciliationControllerTest.java, `ReadApiExceptionHandlerTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ReadApiExceptionHandlerTest.java, `ProblemReasonTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/ProblemReasonTest.java, `OpenApiContractTest` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiContractTest.java; then src/main/java/uk/gov/hmcts/cp/resultsstore/api/RerunBodyParser.java (64 KiB; tree read with the service's Jackson 3 mapper; field by field), src/main/java/uk/gov/hmcts/cp/resultsstore/api/OperationsParameters.java (strict parsing as spec 003's `ShareParameters`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/OperatorMissingException.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ExtractionOperationsController.java (`POST` with `consumes = application/json` and `@RequestBody(required = false) String`; `202`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReceiptsController.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReconciliationController.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/RerunAcceptedResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ExtractionStatusResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReceiptsResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReceiptResponse.java, src/main/java/uk/gov/hmcts/cp/resultsstore/api/DailyReconciliationResponse.java (nested records as contracts/operations-api.md; times through `InstantFormat`), src/main/java/uk/gov/hmcts/cp/resultsstore/api/ReadApiExceptionHandler.java (+ `HttpMediaTypeNotSupportedException` → `415 unsupported_content_type`; `OperatorMissingException` → `401 unauthenticated`; every `4xx` on an operations route counted through `OperationsObserver.refused(endpoint, reason)`), src/main/resources/results-store-openapi.yaml (the four `x-planned` markers removed)
  - Cases: `RerunBodyParserTest`: `an_empty_body_null_not_json_or_not_an_object_should_be_unreadable_body`; `a_field_of_the_wrong_json_type_should_be_unreadable_body` (`reason: 5`, `shareIds: "x"`, an id that is a number); `an_unknown_field_should_be_unknown_field` (also `storedfrom`); `a_body_over_64_kib_should_be_body_too_large` (65,537 bytes; 65,536 accepted); `each_selector_and_the_reason_should_be_read`; `a_bad_id_should_be_invalid_hearing_id_or_invalid_share_id`; `an_instant_without_offset_should_be_range_invalid`. `OperationsParametersTest`: `an_unknown_or_repeated_parameter_should_be_refused`; `both_receipt_forms_should_be_conflicting_parameters`; `hearing_id_alone_or_nothing_should_be_missing_parameter`; `a_message_id_of_257_characters_a_space_or_a_control_character_should_be_invalid_message_id`; `a_bad_date_should_be_invalid_date`; `status_should_take_no_parameter`. `ExtractionOperationsControllerTest`: `a_rerun_should_answer_202_with_every_field`; `a_repeat_should_answer_202_with_repeat_true`; `a_missing_or_non_uuid_cjscppuid_should_be_401_unauthenticated_with_the_service_never_called`; `text_plain_should_be_415_unsupported_content_type`; `the_status_should_map_every_field_with_nulls_present`; `every_answer_should_carry_cache_control_no_store_and_no_etag`; `no_problem_body_should_echo_a_caller_value_or_the_reason`. `ReceiptsControllerTest`: `should_map_every_receipt_field_with_nulls_present`; `an_empty_list_should_be_200`. `ReconciliationControllerTest`: `should_map_every_field_including_give_up_after_as_an_iso_duration`. `ReadApiExceptionHandlerTest`: `http_media_type_not_supported_should_be_415_unsupported_content_type`; `operator_missing_should_be_401_unauthenticated`; `a_4xx_on_an_operations_route_should_count_operations_refused_once_with_endpoint_and_reason`; `a_4xx_on_a_read_route_should_count_no_operations_meter`; spec 003's cases unchanged. `ProblemReasonTest`: `the_reasons_should_be_exactly_the_read_and_operations_contract_lists`. `OpenApiContractTest`: `no_path_should_carry_x_planned`; spec 003's two-way cases now cover the four operations paths.
  - Notes: controllers never log the body, the reason or `CJSCPPUID`. Single exit in the parsers (PMD).
  - Covers: FR-002–FR-016 (web half), FR-034, FR-035, FR-037, FR-046; contracts/operations-api.md §3–§7; SC-007 (slice half).
  - Done when: the eight test classes green; the gate green.

- [ ] T009 [US1] [US2] [US3] [US4] [US5] [US6] Test first: `OperationsApiIT` in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/OperationsApiIT.java, `AuditIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/AuditIT.java, `NoPayloadInLogsIT` (extended) in src/test/java/uk/gov/hmcts/cp/resultsstore/integration/NoPayloadInLogsIT.java; then any fix the tests find in the files of T001 to T008
  - Cases (`OperationsApiIT`: Testcontainers Postgres, MockMvc, `authz.http.enabled=true`, in-process WireMock usersgroups matched on `CJSCPPUID` with a "Second Line Support" id, a "System Users" id and an "Other Group" id; shares stored through a `JdbcShareStore` built over the context's data source; the sweep driven directly by an `ExtractionSweep` built the same way): `each_endpoint_should_serve_a_second_line_support_caller` (`202` or `200`); `each_endpoint_should_refuse_a_system_users_caller_403_with_a_bounded_body`; `each_endpoint_should_refuse_no_identity_401`; `a_system_users_caller_sending_an_operations_action_in_cpp_action_content_type_or_accept_should_still_be_403` (route × spoof; SC-008); `a_second_line_support_caller_with_a_read_api_vendor_accept_should_still_be_served`; `an_unmapped_operations_path_should_be_404_route_not_found`; `get_on_the_rerun_should_be_405_with_allow_post`; `multipart_on_the_rerun_should_be_415`; `rerun_then_a_sweep_round_should_reextract_the_share_in_place_and_close_the_request` (status shows the outcome; the share's `storedSeq` unchanged through `GET /results-store/v1/shares/{shareId}`); `a_repeat_should_return_the_same_rerun_id`; `the_status_receipts_and_reconciliation_should_match_seeded_data` (SC-006); `no_response_body_should_hold_a_message_text_payload_reason_or_operator_marker` (SC-007); `a_query_held_by_a_lock_should_give_503_store_unavailable_with_retry_after`; `every_problem_body_should_be_the_four_fields`; `the_operations_meters_should_move_as_contracts_metrics_says`. `AuditIT` (audit on, `EmbeddedBrokerSupport`): `an_operations_request_should_be_audited_with_its_derived_action`; `the_rerun_request_event_s_body_capture_should_be_pinned` (asserts what the library does with the request body; the result is recorded under this task and in contracts/operations-api.md §2.4; FR-045); `an_operations_response_event_should_carry_its_body_with_no_payload_or_message_text`; `a_403_on_an_operations_route_should_publish_nothing`. `NoPayloadInLogsIT`: `posting_and_working_a_rerun_should_log_no_reason_operator_id_or_payload_marker_at_any_level` (root at DEBUG).
  - Covers: US1–US6 end to end; FR-003, FR-004, FR-011, FR-043–FR-046; SC-006, SC-007, SC-008, SC-009.
  - Done when: the three test classes green; the gate green.

---

## Phase D: smoke and documents (T010–T011)

**Purpose**: prove the endpoints in the compose stack; then bring the constitution, design rules, spec
001, spec 003's contract and the page notes in line with what was built. Depends on phase C.

**Independent test**: the smoke script prints `PASS`; the review grep shows no stale wording.

- [ ] T010 [US1] [US2] [US3] [US4] [US5] Test first: extend scripts/container-smoke.sh so it fails on the pre-004 build, with the checks of spec FR-053 after the published share is stored, as the synthetic "Second Line Support" caller `22222222-2222-4222-8222-222222222222`: `GET /operations/extraction/status` `200` with `extractorVersion`; `GET /operations/receipts?hearingId=…&hearingDay=…` shows `STORED` with the share's `shareId` and no field holding the message text; `GET /operations/reconciliation/daily?date=<London today>` shows `receipts.received` ≥ 1 and `shares.stored` ≥ 1; `POST /operations/extraction/rerun` `{"shareIds":[<the share>],"reason":"Container smoke rerun check"}` gives `202` with `matched` 1; posting it again gives `repeat` true; then `psql` waits (bounded) for the item `DONE` and checks the share still `OK` with its `stored_seq` and `court_centre_id` unchanged; with the "System Users" id `403` on the status; with no header `401`; `GET /operations/nope` `404` with reason `route_not_found`; `resultsstore_operations_rerun_requests_total` and `resultsstore_sweep_rerun_rows_total` present; then docker-compose.yml (app service: `RESULTSSTORE_SWEEP_INITIALDELAY` and `RESULTSSTORE_SWEEP_FIXEDDELAY` through `${VAR:-default}` with short values, contracts/configuration.md *Compose*), docker/wiremock/mappings/identity-second-line-support.json (new: matched on `CJSCPPUID` `22222222-2222-4222-8222-222222222222`, priority 1, groups "Second Line Support")
  - Covers: FR-053; SC-011.
  - Done when: `scripts/container-smoke.sh` prints `PASS` with every check `ok` (the RED run quoted: the operations checks fail on the build before phase A), with the 001, 002 and 003 checks still `ok`; the gate green.

- [ ] T011 [US1] [US5] (D-PRINCIPLE-I-BUMP default) Documents. Test first: the review grep (RED: the hits before the edits) for `OK is final`, `final in 001`, `never re-extracted`, `marking rows for a rerun is spec 004`, `reconciliation_finding`, `are final` across `specs/`, `.specify/memory/constitution.md` and `.claude/rules/`; each hit is either changed, marked historical, or carries a forward note to 004 (V3's comment in `src/main/resources/db/migration/V3__create_share_store.sql` is never edited); then
  - .specify/memory/constitution.md: version 2.2.0 → 2.3.0 (MINOR; or the next free MINOR if spec 003's T013 made its own amendment first, recorded under this task); Principle I's sweep bullet replaced by research R21's text; the *Rationale* gains research R21's sentence; Sync Impact Report (version change, bump rationale, Principle I changed, the cross-reference to Principle II's working copy, templates checked, follow-ups: the youth-raised feed, the nightly reconciliation, erasure of the rerun tables); **Last Amended** set;
  - .claude/rules/design_rules.md: the *What may be updated?* row gains "an `OK` share's, only while a pending rerun item names it, staying `OK`"; the `reconciliation_finding` row marked "deferred: a later spec with the nightly job"; the *Components* Reconciliation row says R1 and R2 are on demand in 004; the *Operations API* section gains one line each on the rerun (a request the sweep works; bounds; repeats) and on the status (each pod's last round); the *Data model* table gains `extraction_rerun`, `extraction_rerun_item` and `sweep_round`;
  - specs/001-share-intake/data-model.md (*Projection (extraction) status*, "final in 001" and "marking rows for a rerun is spec 004") and specs/001-share-intake/spec.md (*Out of scope*, "The operations API … spec 004"): an *Amended by spec 004* note pointing at `specs/004-operations-api`; specs/001-share-intake/contracts/metrics.md *Not in 001*: a pointer to specs/004-operations-api/contracts/metrics.md;
  - specs/003-read-api/contracts/read-api.md §5.5: the rerun's rules (spec FR-028): key details rewritten in place with no new `storedSeq`; `false`→`true` youth held (pending Sachin); unknown→known and `false`→unknown written, so a day may leave or join the `notFalse` view; `projectionVersion` and `projectedAt` show the last write;
  - specs/004-operations-api/contracts/*.md and quickstart.md checked against what was built and corrected (the audit request-body answer of T009 in operations-api.md §2.4); specs/004-operations-api/page-notes.md reconciled; specs/004-operations-api/plan.md constitution reference 2.3.0; specs/004-operations-api/spec.md status set to Implemented and the D-items Sachin has ruled on updated;
  - `/speckit-analyze` (read-only) over spec.md, plan.md and tasks.md with the constitution, research, data-model and contracts as context; every CRITICAL and HIGH finding resolved, MEDIUM fixed or listed under this task with a reason; `.specify/scripts/bash/check-prerequisites.sh --require-tasks --include-tasks --json` exits 0;
  - the Deferred list below, completed with anything the phases left open.
  - Covers: FR-051, FR-052; SC-012.
  - Done when: the review grep shows no hit that is not reworded, marked historical or forward-noted; `/speckit-analyze` reports no CRITICAL or HIGH finding; the gate green.
  - Deferred (not in 004):
    - a youth-raised feed or notification, and re-queuing the shares held as `YOUTH_RAISE_HELD` (D-YOUTH-RAISE);
    - an operator cancel endpoint for rerun requests (D-RERUN-CANCEL);
    - the nightly reconciliation job, the `reconciliation_finding` table, R2 sampling, and the Azure alert "reconciliation does not run" (D-NIGHTLY, D-R2);
    - retention, purge and erasure of `extraction_rerun` and `extraction_rerun_item` (staff-entered reason, operator id; delete items before requests and before shares) (D-RERUN-ERASURE);
    - removing stale `share_defendant` rows after a rerun, with the defendant view;
    - a stricter guard forbidding value-to-null moves on a rerun, if Sachin rules so (D-NEVER-BLANK);
    - restricting insert rights on `extraction_rerun_item` to the role that serves the rerun, if roles become separable (D-RERUN-GUARD);
    - `CREATE INDEX CONCURRENTLY` for V6's three indexes if it deploys after live capture starts (D-PG-VERSION);
    - the R1 give-up value from the broker's redelivery settings (D-R1-WINDOW; platform team);
    - deploy values in `cpp-aks-deploy` (the gateway route to `/operations`) and the Azure Monitor alert rules for `resultsstore.sweep.rerun.rows{outcome=abandoned|youth_raise_held}` and `resultsstore.sweep.round.record.failed`.

---

## Dependencies & Execution Order

### Phase dependencies

- Spec 003 implemented → Before phase A (done) → Phase A → Phase B → Phase C → Phase D. Each phase
  starts only after the previous phase-gate run has ended at PASS.
- Phase B needs from phase A: V6 (T002), the types and ports (T003).
- Phase C needs from phase B: `JdbcRerunRequests` and `JdbcOperationsQueries` (T004), the sweep's rerun
  path and `SweepObserver` (T005), the round record (T006).
- Phase D needs phase C complete.

### Within phases

- Phase A: T001 first (the contract); T002 and T003 touch disjoint files and may run in any order after
  it.
- Phase B: T004 → T005 (its ITs seed requests through `JdbcRerunRequests`) → T006 (changes the same
  `ExtractionSweep` constructor again).
- Phase C: T007 → T008 (controllers need T007's beans) → T009 (end to end).
- Phase D: T010 → T011 (it records the analysis of the finished range).

### User story → tasks

| Story | Tasks | Independently proven by |
|---|---|---|
| US1 Re-run extraction (P1, MVP) | T002, T003, T004, T005, T007, T008, T009, T010, T011 | `OperationsSchemaIT`; `RerunSweepIT`; `OperationsApiIT` rerun case; smoke rerun |
| US2 Extraction and sweep status (P2) | T004, T006, T007, T008, T009, T010 | `JdbcOperationsQueriesIT`; `JdbcSweepRoundsIT`; smoke status |
| US3 Receipts (P3) | T003, T004, T007, T008, T009, T010 | `JdbcOperationsQueriesIT` marker case; smoke receipts |
| US4 Daily reconciliation (P4) | T003, T004, T007, T008, T009, T010 | `ReconciliationWindowTest`; `JdbcOperationsQueriesIT` R1; smoke reconciliation |
| US5 Second Line Support only; nothing leaks (P5) | T001, T002, T008, T009, T010, T011 | `ResultsStoreRulesTest`; `OperationsApiIT` spoofing and markers; smoke `401`/`403`/`404` |
| US6 Alerts on the rerun and the sweep (P6) | T005, T006, T007, T008, T009 | `MicrometerSweepObserverTest`; `MicrometerOperationsObserverTest`; `OperationsApiIT` meters |

## Parallel examples

```text
Phase A (after T001): "T002 OperationsSchemaIT / FlywayMigrationIT, then V6__operations.sql"
                      "T003 RerunSelectorTest / … / ReconciliationWindowTest, then the types and ports"
```

Phases B, C and D have no parallel tasks: each task uses the one before it. Within a phase-gate run there
is one implementer, so `[P]` marks independence (the order is free), not concurrent agents in one tree.

## Implementation strategy

1. Phase A puts the contract and the database backstop in place before any code can rewrite a share.
2. Phase B delivers the rerun end to end at the data level (US1's MVP in `RerunSweepIT`), with the reads
   and the round record.
3. Phase C serves it, with authorisation and audit proven in `OperationsApiIT`.
4. Phase D proves it in the compose stack and reconciles the constitution, the design rules, specs 001
   and 003 and the page notes.

## Notes

- Ticks (`[X]`) and the RED / GREEN lines are written by the implementer in the commit that completes
  the task.
- A task's commit message follows Conventional Commits and names the task id.
- Where a task finds the design documents silent, the implementer takes the option that changes the
  least behaviour and records it under the task.
- A D-item Sachin rules on after this file is written changes only the tasks named in its row of spec.md
  *Decisions pending Sachin*; the implementer records the ruling under the task.
