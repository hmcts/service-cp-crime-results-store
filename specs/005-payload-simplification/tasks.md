---

description: "Task list for feature 005: payload simplification"
---

# Tasks: Payload Simplification

**Input**: Design documents from `/specs/005-payload-simplification/`
**Prerequisites**: spec.md, plan.md; specs 001–003 built and merged (`main` at `2ba9a8b`, contract
`0.2.0`); the api repo's `team/rs` draft `rs-8fb8ea6` published to `hmcts-lib` (needed from T004).

**Tests**: Mandatory (Principle X). Each task names its test cases first, then the production files.
The implementer writes the tests, runs them, records the RED run under the task (a failing assertion,
never a compile error: land compile-safe seams first), then writes the minimum production code and
records the GREEN run. One commit per task, the test at or before the production code. A task is
ticked (`[X]`) on a green run of the test classes it names, in the commit that completes it. The
full build (`./gradlew build pmdMain pmdTest jacocoTestReport`) runs ONCE, after T007 and before the
gate, to save time; a failure found there is fixed in a follow-up commit named after the task.

**Organisation**: one phase, T001–T007, one run of the phase-gate workflow. The write side first
(T001–T003, on the 0.2.0 jar), the contract (T004, one indivisible commit), the read-side cleanup
(T005), the smoke (T006), the documents (T007). T008 follows the api release and is the
orchestrator's step.

**Every Gradle invocation**: `flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew <args>`. The
full build is `./gradlew build pmdMain pmdTest jacocoTestReport`. Docker must be running.

**Conventions**: no wildcard imports; constructor injection; no AI attribution anywhere; Conventional
Commits (`feat`, `fix`, `test`, `refactor`, `docs`, `build`; scope `intake`, `read`, `authz`, `smoke`,
`spec`); no payload content or personal data in any log line or assertion message (a failing invariant
names a share id only).

## Phase: the write side

- [X] T001 [US1] `NulSafety.strip` (unit only). Seam first so the tests compile:
  `public static String strip(final String text) { return text; }` in
  src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/NulSafety.java. Tests in
  src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/NulSafetyTest.java:
  - `strip_should_remove_the_nul_escape_and_each_unpaired_surrogate_escape` (`@ParameterizedTest`
    with `@CsvSource` or `@MethodSource`, input → expected, JSON text shown as Java string literals):
    `\u0000` → empty; `x\u0000y` → `xy`; `{"a\u0000":1}` → `{"a":1}`; `\uDC00x` → `x`; `\uDBFFx` → `x`;
    `"\uD800"` → `""`; `\uD800` alone at the end of the text → empty; `\uD800𐀀` →
    `𐀀`; `\uD800A` → `A`; `\uD800\n` → `\n`; `\uD800\u0000\uDC00` → empty.
    RED against the seam.
  - `strip_should_keep_text_jsonb_can_hold_unchanged`: the existing safe `@ValueSource` of
    `text_that_jsonb_can_hold_should_keep_its_parsed_copy` (pairs `😀` and `😀`,
    `\\u0000`, `\\uD800`, `\n`, `\"`, `\/`, a bare `\`, `\u00`, `\uZZZZ`): green already.
  - `strip_should_be_idempotent`: `strip(strip(t))` equals `strip(t)` over both sources. RED.
  - Production: the strip reuses the scanner (`unicodeEscape`, `isStorable`, `escapeLength`, `hex`)
    and appends each kept run to a `StringBuilder`. Lengths: a non-escape character 1; an escape with
    no `\uXXXX` unit (`\n`, a truncated one) 2; a kept pair 12; any other unit (ordinary, a dropped
    NUL, a dropped lone half) 6. An unpaired high escape advances by 6 (today's loop advances 12 for
    every high escape, which only works because it stops at the first unsafe one). A removed escape
    starts and ends on an escape boundary, so removing one never creates another: idempotent.
    `isJsonbSafe` and its tests stay until T002. Class javadoc rewritten: what the strip removes
    and why (`jsonb` refuses them; the text column keeps them).
  - Done when: `NulSafetyTest` green; `./gradlew test --tests '*NulSafetyTest'` quoted red then green.
  - RED: `./gradlew test --tests '*NulSafetyTest.strip_should_remove*'` against the seam:
    `strip_should_remove_the_nul_escape_and_each_unpaired_surrogate_escape [1] text = "\\u0000", expected = "" FAILED`
    `AssertionFailedError: expected: "" but was: "\u0000"` (and `[2] expected: "xy" but was: "x\u0000y"`, ...);
    `strip_should_be_idempotent [14] text = "\\u0000" FAILED` (the stripped text still holds an escape
    `jsonb` refuses); 54 tests completed, 14 failed (`failFast`).
  - GREEN: `./gradlew test --tests '*NulSafetyTest'`: BUILD SUCCESSFUL, 78 tests, 0 failures, 0 skipped;
    `./gradlew test`: BUILD SUCCESSFUL, 2025 tests, 0 failures.

- [X] T002 [US1] Store the stripped working copy for every share. Tests first:
  - src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStoreIT.java:
    `payload_with_an_escaped_nul_or_unpaired_surrogate_should_store_its_text_unchanged_and_the_stripped_working_copy`
    replaces `payload_that_jsonb_refuses_should_be_stored_as_text_with_no_parsed_copy` (same three
    notes `a\\u0000b`, `\\uD800`, `\\udc00x`; asserts `payload_text` equals the text sent,
    `payload_sha256` its SHA-256, `payload_json` equals `CAST(NulSafety.strip(text) AS jsonb)`, bound
    as a parameter and compared in SQL, never printed); 
    `enriched_copy_holding_an_escaped_nul_should_be_stored_stripped_with_the_flag_true` replaces
    `enriched_copy_holding_an_escaped_nul_should_be_refused_before_any_transaction_and_write_nothing`;
    `payload_whose_number_jsonb_refuses_should_throw_retryable_at_store_and_leave_nothing` replaces
    `payload_whose_number_jsonb_refuses_should_be_stored_as_text_with_no_parsed_copy` (`1e1000000`,
    `-1e1000000`, `1e-1000000`: `RetryableIntakeException`, stage `STORE`, no share row, no payload
    row, the receipt still `RECEIVED`); 
    `enriched_copy_the_database_refuses_should_throw_retryable_and_leave_the_connection_usable`
    replaces `enriched_copy_the_database_refuses_should_roll_back_and_leave_the_connection_usable`
    (keep the single-connection re-store half: a good share stores afterwards on the same connection);
    `payload_for_extraction_of_a_text_with_an_escaped_nul_should_be_its_stripped_working_copy`
    replaces `payload_for_extraction_should_fall_back_to_the_text_when_there_is_no_working_copy`;
    the two `*_not_a_data_exception_*` tests merge into
    `payload_insert_failure_should_throw_retryable_and_leave_nothing` (the trigger no longer needs
    the `payload_json IS NOT NULL` condition); drop the `NulSafety.isJsonbSafe(...)` asserts (lines
    about 308 and 478). Invariants (`@AfterEach dataModelInvariantsHold`): `FLAG_WITHOUT_COPY` becomes
    `NO_WORKING_COPY` (`SELECT count(*) FROM hearing_share_payload WHERE payload_json IS NULL`, must
    be 0); `UNENRICHED_COPY_DIFFERS` cannot cast `payload_text` any more (a NUL text throws), so it
    selects `share_id, payload_text` of unenriched shares and checks each
    `payload_json = CAST(:copy AS jsonb)` with `copy = NulSafety.strip(text)`, failing with the share
    id only. The `payload(...)` helper (about line 671) does the same.
  - src/test/java/uk/gov/hmcts/cp/resultsstore/adapter/publicevents/IntakeIT.java:
    `results_holding_an_escaped_nul_should_be_stored_stripped_with_the_flag_true_and_counted_applied`
    replaces `results_jsonb_cannot_hold_should_store_the_arrived_copy_with_the_flag_false_and_count_it`
    (`enrichment_applied` true; `resultsstore.enrichment.applied` moved; nothing skipped).
  - src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/ExtractionSweepIT.java:
    `row_whose_text_held_an_escaped_nul_should_be_re_extracted_from_its_stripped_working_copy`
    replaces `row_with_no_parsed_copy_should_be_read_from_its_stored_text` (asserts
    `payload_json IS NOT NULL`, then the sweep fixes the row); add
    `row_stored_without_a_working_copy_should_be_read_from_its_text`: a pre-005 row made on a test
    connection with `SET session_replication_role = replica` (skips the update guard) and
    `UPDATE hearing_share_payload SET payload_json = NULL`; the sweep still fixes it (spec FR-008;
    keeps the `COALESCE` covered).
  - src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueriesIT.java:
    `payload_of_a_text_with_an_escaped_nul_should_return_the_stripped_working_copy` replaces
    `payload_should_return_payload_text_and_the_arrived_form_when_the_working_copy_is_null` (form
    `WORKING_COPY` until T005 removes the form).
  - src/test/java/uk/gov/hmcts/cp/resultsstore/integration/ReadApiIT.java:
    `payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key`: the `a\\u0000b` share is served
    as `working-copy` (the header assertion goes in T005).
  - Production: src/main/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareStore.java: one
    `insertPayload(request)` binding `NulSafety.strip(request.parsedCopy())` as `parsedCopy`;
    `payload_text`, `text_bytes` from `request.text()`; delete the `store()` pre-check and the
    `EnrichedCopyRefused` branch of `storeLocked` (pass `false` for `parsedCopySkipped` until T003),
    `insertEnrichedPayload`, `insertPayloadParsed`, `isDataException`, `DATA_EXCEPTION_CLASS`, the
    `TransactionStatus` parameter where it is then unused; class javadoc. Delete
    `NulSafety.isJsonbSafe` and its two tests. Javadoc: `application/ShareStore.payloadForExtraction`
    ("or `payload_text` for a row stored without a copy before spec 005"); `application/StoreRequest`
    `@param parsedCopy` ("the store removes the escapes `jsonb` refuses before writing it").
  - Done when: the named classes green.
  - Also in this commit: `ReadApiIT.an_arrived_text_that_does_not_parse_should_give_500_internal_error_and_never_the_text`
    and the unreadable-arrived-text `500` half of `NoPayloadInLogsIT.serving_a_payload_should_log_no_payload_marker_at_any_level`
    (both listed for T004) go now: each stored a text that is not JSON, which the one payload insert refuses
    as a retryable store failure, so their set-up can no longer be built.
  - RED (one class per run, `failFast` stops each at its first failure):
    `ExtractionSweepIT`: `row_whose_text_held_an_escaped_nul_should_be_re_extracted_from_its_stripped_working_copy() FAILED`
    `Expecting value to be true but was false` (`payload_json IS NOT NULL`);
    `JdbcShareStoreIT`: `payload_for_extraction_of_a_text_with_an_escaped_nul_should_be_its_stripped_working_copy() FAILED`
    `Expecting value to be true but was false`;
    `IntakeIT`: `results_holding_an_escaped_nul_should_be_stored_stripped_with_the_flag_true_and_counted_applied() FAILED`
    `to contain entries: ["enrichment_applied"=true] but the following map entries had different values`;
    `JdbcShareQueriesIT`: `payload_of_a_text_with_an_escaped_nul_should_return_the_stripped_working_copy() FAILED`
    `expected: WORKING_COPY but was: ARRIVED_TEXT`;
    `ReadApiIT`: `payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key() FAILED`
    `Expecting actual: Optional[arrived-text] to contain: "working-copy"`.
  - GREEN: `./gradlew test --tests '*JdbcShareStoreIT' --tests '*IntakeIT' --tests '*ExtractionSweepIT'
    --tests '*JdbcShareQueriesIT' --tests '*ReadApiIT' --tests '*NulSafetyTest' --tests '*NoPayloadInLogsIT'`:
    BUILD SUCCESSFUL, 240 tests, 0 failures, 0 skipped.

- [X] T003 [US1] Remove the skip and refusal types. Tests first:
  - src/test/java/uk/gov/hmcts/cp/resultsstore/config/MicrometerIntakeObserverTest.java: drop
    `resultsstore.intake.parsed.copy.skipped` and the `unstorable_results` reason from the
    `containsExactly` lists, the event rows and `everyEventWithEveryValue`. RED (the meter is still
    registered).
  - src/test/java/uk/gov/hmcts/cp/resultsstore/domain/EnrichmentSkipTest.java: values are `disabled`
    and `already_stored` only. RED.
  - src/test/java/uk/gov/hmcts/cp/resultsstore/application/IntakeServiceTest.java: delete
    `receive_whose_parsed_copy_was_skipped_should_report_it`,
    `enriched_copy_refused_should_be_stored_once_more_with_the_arrived_copy_and_counted`,
    `enriched_copy_refused_again_should_not_be_run_a_third_time_but_counted_and_thrown`;
    `a_duplicate_or_refused_copy_should_never_count` becomes `a_duplicate_should_never_count`; add
    `an_enriched_store_failure_should_be_counted_once_at_store_and_rethrown_after_one_store`
    (`shareStore.store` throws `RetryableIntakeException`: exactly one store call, one
    `intakeFailed(STORE, DATABASE)`, rethrown; green already).
  - Production: `application/StoreResult` (remove `EnrichedCopyRefused` and `Stored.parsedCopySkipped`;
    fix `withInsertToCommit`); `application/IntakeService` (one `counted(STORE, ...)` call and a
    two-arm switch; delete `storedProjection` and the `parsedCopySkipped` branch);
    `application/IntakeObserver.parsedCopySkipped`; `config/MicrometerIntakeObserver`
    (`PARSED_COPY_SKIPPED_METER`, its registration and increment); `domain/EnrichmentSkip.UNSTORABLE_RESULTS`.
    Same commit: every `new Stored(` call site (`IntakeServiceTest`, `JdbcShareStoreIT`,
    `JdbcShareStore`) and the switch arm in `persistence/ShareChainIT` (about line 177).
  - Done when: the named classes green.
  - RED (one class per run): `MicrometerIntakeObserverTest`:
    `no_tag_in_the_registry_should_hold_a_value_off_the_lists_an_id_or_a_date() FAILED`
    `[reason] Expecting SetN: [...] to contain: ["unstorable_results"]` (the reason is still registered);
    `EnrichmentSkipTest`: `every_tag_should_come_from_the_fixed_list() FAILED`
    `Expecting actual: ["disabled", "already_stored", "unstorable_results"] to contain exactly (and in same order):
    ["disabled", "already_stored"]`; `IntakeServiceTest` green already (the new
    `an_enriched_store_failure_should_be_counted_once_at_store_and_rethrown_after_one_store` included).
  - GREEN: `./gradlew test --tests '*.MicrometerIntakeObserverTest' --tests '*.EnrichmentSkipTest'
    --tests '*.IntakeServiceTest' --tests '*JdbcShareStoreIT' --tests '*ShareChainIT'`: BUILD SUCCESSFUL,
    105 tests, 0 failures, 0 skipped.

## Phase: the contract

- [X] T004 [US2] The service follows the contract. ONE commit: the pin removes
  `getShareArrivedPayload` from `SharesApi`, so `SharesController`'s override and
  `SharesControllerTest`'s `PATH_GET_SHARE_ARRIVED_PAYLOAD` stop compiling, and
  `OpenApiContractDriftTest` is red whenever the yaml and the jar differ. Record the RED run on the
  0.2.0 jar with the assertion tests below, before any production change. Tests first:
  - src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiDocumentTest.java:
    `the_arrived_path_should_not_be_described` (no path ends `/payload/arrived`);
    `the_payload_operation_should_declare_no_payload_form_header`; delete
    `the_arrived_operation_should_declare_the_etag_and_results_store_headers_with_the_arrived_form`
    and the `ARRIVED` constant. RED.
  - src/test/java/uk/gov/hmcts/cp/resultsstore/api/OpenApiContractTest.java and
    src/test/java/uk/gov/hmcts/cp/resultsstore/api/SharesControllerTest.java: `hasSize(4)`;
    the controller test's path list without `PATH_GET_SHARE_ARRIVED_PAYLOAD`. RED on the 0.2.0 jar.
  - src/test/java/uk/gov/hmcts/cp/resultsstore/filters/ApiRouteTest.java: add
    `/results-store/v1/shares/{uuid}/payload/arrived` to the paths that match nothing; delete
    `the_arrived_path_should_resolve_to_its_own_action_and_tag_and_not_to_the_payload`; the action
    and endpoint lists without the arrived entries. RED.
  - src/test/java/uk/gov/hmcts/cp/resultsstore/acl/ResultsStoreRulesTest.java:
    `the_arrived_payload_action_should_be_refused_for_both_groups` replaces
    `the_arrived_payload_action_should_be_allowed_on_its_own_path_for_both_groups_only`. RED.
  - `domain/ReadEndpointTest` (tags without `arrived_payload`), `domain/ReadOutcomeTest`
    (`NOT_MODIFIED` applies to `PAYLOAD` only), `config/MicrometerReadObserverTest` (drop the
    `arrived_payload` clause and `arrived_payload_should_be_registered_with_not_modified`),
    `api/SharePayloadControllerTest` (`the_payload_should_carry_no_payload_form_header` replaces the
    `working-copy` header assertion; delete the three arrived cases),
    `integration/ReadApiIT` (`the_removed_arrived_path_should_be_404_route_not_found_and_counted`;
    delete the four arrived cases, `arrivedPath`, the `GET_SHARE_ARRIVED_PAYLOAD` arm of `path`),
    `integration/AuditIT` (the `@ValueSource` without `/payload/arrived`; delete
    `arrived_response_event_should_carry_the_marker`), `integration/NoPayloadInLogsIT` (the loop over
    `/payload` only; delete the unreadable-arrived-text `500` case). RED where the assertion runs.
  - Production, in this order: src/main/resources/results-store-openapi.yaml (delete the arrived
    path block; on `/payload` delete the `Results-Store-Payload-Form` header and the "or the text as
    it arrived when the database could not hold a working copy" wording, exactly as api commit
    `8fb8ea6`; the drift test is red here); gradle/libs.versions.toml `api-results-store = "rs-8fb8ea6"`
    with its comment (the drift test green); src/main/java/uk/gov/hmcts/cp/resultsstore/api/SharesController.java
    (delete `getShareArrivedPayload`); filters/ApiRoute.java (delete `GET_SHARE_ARRIVED_PAYLOAD`;
    the previous constant ends with `;`); domain/ReadEndpoint.java (delete `ARRIVED_PAYLOAD`);
    domain/ReadOutcome.java (`appliesTo`: `this != NOT_MODIFIED || endpoint == ReadEndpoint.PAYLOAD`;
    javadoc); api/PayloadResponses.java (delete `PAYLOAD_FORM` and its `.header(...)`);
    src/main/resources/acl/results-store-rules.drl (delete the rule
    `Allow - results-store.get-share-arrived-payload`); filters/PayloadBodyFreeAuditPayloadGenerationService.java
    (`PAYLOAD_ACTIONS` with the payload action only; javadoc); src/test/java/uk/gov/hmcts/cp/resultsstore/support/ApiRouteSamples.java
    (the two switch arms). Then the `@EnumSource(names = ...)` lists in `api/ContentNegotiationTest`
    (and its `arrivedPayload` mocks), `api/ShareParametersInterceptorTest`,
    `filters/PayloadBodyFreeAuditPayloadGenerationServiceTest`. `ShareReadService.arrivedPayload`,
    `ShareQueries.arrivedText` and `JdbcShareQueries.ARRIVED_SQL` stay until T005 (they compile
    without the route).
  - Done when: `OpenApiContractDriftTest`, `OpenApiContractTest`, `ResultsStoreRulesTest`,
    `ApiRouteTest`, `ReadApiIT`, `AuthzIT`, `AuditIT`, `NoPayloadInLogsIT` green on the draft jar.
  - Also in this commit: `ReadApiIT.payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key` asserts
    the form header absent (its T002 `working-copy` assertion cannot hold once `PayloadResponses` stops
    sending it); the service yaml is the api commit's diff applied as is (one hunk re-wrapped by hand).
  - RED (on the 0.2.0 jar, before any production change; one class per run):
    `OpenApiDocumentTest`: `the_payload_operation_should_declare_no_payload_form_header() FAILED`
    `Expecting actual: {"Cache-Control"=...} not to contain key: "Results-Store-Payload-Form"`;
    `OpenApiContractTest`: `every_controller_mapping_should_be_described() FAILED` `Expected size: 4 but was: 5`;
    `SharesControllerTest`: `the_controller_should_be_the_only_sharesapi_implementation_and_register_each_mapping_once() FAILED`
    `Expected size: 4 but was: 5`;
    `ApiRouteTest`: `every_route_should_name_its_endpoint_tag() FAILED` `Expecting actual: [PULL, SEARCH, SHARE,
    PAYLOAD, DAY_VERSIONS, ARRIVED_PAYLOAD] to contain exactly (and in same order): [...]`;
    `ResultsStoreRulesTest`: `the_arrived_payload_action_should_be_refused_for_both_groups() FAILED`
    `Expecting value to be false but was true`;
    `ReadEndpointTest`: `every_tag_should_come_from_the_fixed_list() FAILED` (`arrived_payload` still listed);
    `ReadOutcomeTest`: `not_modified_should_apply_to_the_payload_endpoint_only(ReadEndpoint) > [6] endpoint =
    ARRIVED_PAYLOAD FAILED` `expected: false but was: true`;
    `MicrometerReadObserverTest`: `every_meter_and_tag_combination_should_be_registered_at_start() FAILED`
    (`arrived_payload/not_modified` registered);
    `SharePayloadControllerTest`: `the_payload_should_carry_no_payload_form_header() FAILED`;
    `ReadApiIT`: `payload_bytes_should_hash_to_the_etag_and_have_no_metadata_key() FAILED`
    `Expecting an empty Optional but was containing value: "working-copy"`;
    `AuditIT`, `NoPayloadInLogsIT`: green (their changes only remove arrived cases).
  - GREEN (on `rs-8fb8ea6`): `./gradlew test` over `OpenApiContractDriftTest`, `OpenApiContractTest`,
    `OpenApiDocumentTest`, `ResultsStoreRulesTest`, `ApiRouteTest`, `ReadApiIT`, `AuthzIT`, `AuditIT`,
    `NoPayloadInLogsIT`, `SharesControllerTest`, `ReadEndpointTest`, `ReadOutcomeTest`,
    `MicrometerReadObserverTest`, `SharePayloadControllerTest`, `ContentNegotiationTest`,
    `ShareParametersInterceptorTest`, `PayloadBodyFreeAuditPayloadGenerationServiceTest`,
    `ActionHeaderFilterTest`: BUILD SUCCESSFUL, 422 tests, 0 failures, 0 skipped.

## Phase: the read side

- [X] T005 [US1] Remove the read fallback. Tests first:
  - src/test/java/uk/gov/hmcts/cp/resultsstore/persistence/JdbcShareQueriesTest.java:
    `the_payload_constant_should_read_only_the_working_copy` (`PAYLOAD_SQL` names neither
    `payload_text` nor `arrived_text`; RED on the `CASE`); delete
    `the_arrived_constant_should_read_payload_text_and_never_payload_sha256_or_payload_json`.
  - src/test/java/uk/gov/hmcts/cp/resultsstore/application/ShareReadServiceTest.java: delete the
    nested `Arrived` class and `the_arrived_text_form_should_be_served_without_metadata_and_hashed_after_the_strip`,
    `an_arrived_text_that_fails_to_parse_should_be_internal_error_never_the_text`,
    `payload_form_should_follow_the_stored_form`; `a_working_copy_should_be_served_as_read` stays
    (drop its form argument).
  - `persistence/JdbcShareQueriesIT`: delete `arrived_text_should_return_payload_text_with_the_identity_columns`
    and the `arrivedText` call in `an_unknown_share_payload_or_day_should_be_empty`; drop `.form()`
    asserts. `api/ContentNegotiationTest`, `api/SharePayloadControllerTest`: drop the form constructor
    argument. `api/ReadApiExceptionHandlerTest` (about lines 149–159): the unreadable-payload case
    becomes a plain `RuntimeException` → `500 internal_error`. Delete
    `domain/EnvelopeMetadataTest` and `domain/PayloadFormTest`.
  - Production: persistence/JdbcShareQueries.java (`PAYLOAD_SQL` body is
    `(p.payload_json - '_metadata')::text AS body`, no `arrived_text` column; delete `ARRIVED_SQL`,
    `arrivedText`, the form mapping; class javadoc); application/ShareQueries.java (delete
    `arrivedText`; javadoc); application/ShareReadService.java (delete `arrivedPayload` and the
    strip branch of `payload`); delete domain/PayloadForm.java, domain/EnvelopeMetadata.java;
    domain/StoredPayload.java and application/ServedPayload.java lose `form`;
    api/ReadApiExceptionHandler.java (delete the `UnreadablePayloadException` handler and import).
  - Done when: the named classes green (the coverage gate is checked by the one full build after
    T007).
  - `ShareReadServiceTest.payload_form_should_follow_the_stored_form` keeps its share-facts half as
    `the_served_payload_should_carry_the_stored_share_s_facts`; `ReadApiExceptionHandler` had no handler of its
    own for `UnreadablePayloadException` (the `RuntimeException` handler took it), so only its javadoc and import go.
  - RED: `./gradlew test --tests '*.JdbcShareQueriesTest'`:
    `the_payload_constant_should_read_only_the_working_copy() FAILED` `Expecting actual: "SELECT ... CASE WHEN
    p.payload_json IS NULL THEN p.payload_text ELSE (p.payload_json - '_metadata')::text END AS body ..." to contain:
    "(p.payload_json - '_metadata')::text AS body"`.
  - GREEN: `./gradlew test` over `JdbcShareQueriesTest`, `ShareReadServiceTest`, `JdbcShareQueriesIT`,
    `ContentNegotiationTest`, `SharePayloadControllerTest`, `ReadApiExceptionHandlerTest`, `ReadApiIT`,
    `NoPayloadInLogsIT`: BUILD SUCCESSFUL, 270 tests, 0 failures, 0 skipped.

## Phase: the smoke and the documents

- [X] T006 [US1] [US2] The compose smoke. scripts/container-smoke.sh: delete the arrived block (the
  `stored_checksum` line through the Second Line Support arrived check) and the
  `resultsstore_read_requests_total{endpoint="arrived_payload",...}` metric line; the header comment
  no longer names the arrived text; add a refusal check that
  `GET /results-store/v1/shares/${share_id}/payload/arrived` as a system user is `404` with
  `reason` `route_not_found` (the existing refusal helper); delete the `Results-Store-Payload-Form`
  check on `/payload`; **keep** `bool_and(p.payload_json IS NOT NULL)`; if the smoke publishes a
  `\u0000` share, assert it is served from its working copy. RED: the arrived block fails on the
  T005 image (quoted); GREEN: `./scripts/container-smoke.sh` prints `PASS` (quoted).
  - There was no `Results-Store-Payload-Form` check on `/payload` left to delete (only the arrived block's),
    and the smoke publishes no `\u0000` share; the new refusal makes `route_not_found` 2, so its metric line
    expects `2.0`.
  - RED (the 004/005 image, the script unchanged): `[container-smoke] FAIL: arrived: 200: expected '200', found
    '404'` ... `FAIL: metric line missing: resultsstore_read_requests_total{endpoint="arrived_payload",
    outcome="not_modified"} 1.0`; `[container-smoke] FAIL: 13 read API check(s) failed`.
  - GREEN: `[container-smoke] PASS: readiness reported UP within the 60s budget`;
    `[container-smoke] PASS: intake stored the share enriched and the read API served it to admitted callers only`.

- [X] T007 [US1] [US2] The documents (spec FR-009, FR-010). .specify/memory/constitution.md:
  version 2.2.0 → 2.3.0 (MINOR); Principle II reworded: the working copy is the text parsed, with
  the finalised application results set in at intake and with the `\u0000` escape and every
  unpaired surrogate escape removed, so every stored share has a working copy; that removal is the
  one exception to "nothing else is added, removed or changed"; "Every indexed column is read from
  the working copy" without the "(from the text when the working copy is empty)" clause; the read
  API sentence becomes "The read API serves the working copy without the message envelope's
  metadata (`_metadata`). No read-API response carries `_metadata` or a value taken from it";
  the rationale's last sentence kept. Principle V gains, after "Extraction failure never drops a
  share.": "The store's own code validates and refuses nothing. A text the database itself cannot
  hold as `jsonb` (a number beyond its numeric range) fails like any database error: retried by the
  broker, then dead-lettered after its attempts." Sync Impact Report at the top (version change,
  rationale, principles changed II and V, templates checked, follow-up: spec 004 renumbers to
  2.4.0 / `v0.4.0`); footer `**Version**: 2.3.0 | **Ratified**: 2026-10-01 | **Last Amended**: 2026-10-07`.
  .claude/rules/workflow.md gate 2 gains the same sentence; .claude/rules/design_rules.md: the
  read-API table's payload row ("The working copy (`payload_json`) without `_metadata`; `ETag` over
  the exact bytes served") and the arrived row deleted, the "What may be updated?" and data-model
  rows unchanged, the `hearing_share_payload` row ("`payload_json`: the working copy, the text parsed,
  enriched at intake, the two escapes `jsonb` refuses removed; held for every share");
  .claude/rules/technical-rules.md the "arrived text stays as received" bullet gains "the `\u0000`
  escape and unpaired surrogate escapes are removed from the working copy only". *Amended by spec
  005* notes (one or two sentences each, in the existing amendment style of those files):
  specs/001-share-intake/spec.md FR-015 and the edge case; specs/001-share-intake/research.md R8;
  specs/001-share-intake/contracts/metrics.md (the counter withdrawn);
  specs/002-enrichment/spec.md FR-019, FR-031, FR-041; specs/002-enrichment/data-model.md
  invariants 2 and 4; specs/002-enrichment/contracts/metrics.md (`unstorable_results` withdrawn);
  specs/003-read-api/spec.md FR-033, FR-041 and US8 (phase D withdrawn); specs/003-read-api/contracts/read-api.md
  §4.4 (the form header) and §4.6 (the arrived text, withdrawn); specs/003-read-api/contracts/metrics.md
  (`arrived_payload`). No code change; exempt from TDD; one commit `docs(spec): ...`.
  - Done when: every file above carries the note; `grep -rn "parsed.copy.skipped\|get-share-arrived-payload\|Payload-Form" src scripts` is empty; the constitution footer reads 2.3.0.
  - Result: every file above carries its note; the grep is empty over `src/main` and `scripts`, and over
    `src/test` finds only the four negative assertions T004 itself asks for (`OpenApiDocumentTest`,
    `ResultsStoreRulesTest`, `SharePayloadControllerTest`, `ReadApiIT`: the header absent, the action
    refused); the constitution footer reads 2.3.0.

## After the api release (the orchestrator's step)

- [ ] T008 Pin `api-results-store = "0.3.0"` in gradle/libs.versions.toml (comment updated);
  `./gradlew validateApiSpecVersions` and the full build green; `OpenApiContractDriftTest` green
  against the released jar. Commit `build(read): take the released read API contract 0.3.0`.

## Dependencies

T001 → T002 → T003 (the write side; no contract dependency). T004 needs `rs-8fb8ea6` published.
T005 after T004 (the form and `EnvelopeMetadata` go only once the arrived route has). T006 after
T005. T007 last before the gate. T008 after the api release.

Story map: US1 → T001, T002, T003, T005, T006, T007; US2 → T004, T006, T007, T008.
