# Feature Specification: Enrichment

**Feature Branch**: `002-enrichment`
**Created**: 2026-10-03
**Status**: Implemented (phases A to C, 2026-10-03)
**Input**: User description: "Enrichment: add finalised application results from progression to each stored share"

**Sources**: the Results Store design page (CRA 321061800, v45), sections *Intake* (*Adding finalised application results*, *Why not the raw event only*, *How many transactions*, *Retries*), *Write path* (step *Enrich*), *Data model and versioning*, *Read API* and *Observability* (alert *Progression lookups failing*); the approved implementation plan for spec 002, including its "Decisions taken with Sachin (2026-10-03)", "Settled by Fable from the facts", "Facts established", "Design" and "404 ruling"; the fact-finding maps of results' enricher (cpp-context-results @063472490) and progression's application-only query; spec 001 (*Share intake*). Quotes in *italics* are the page's wording.

### Scope

**In scope.** An enrichment step between the receipt and the store transaction. The progression client and its configuration. The enriched working copy kept in the parsed copy (`payload_json`), with the arrived text (`payload_text`) unchanged. `enrichment_applied`. Metrics. The extraction and the sweep reading the enriched copy. An end-to-end check through the compose stack with a progression stub. Documentation changes: Principle II, the spec-001 requirements this feature amends, and the page wording, written as forward notes.

**Out of scope.**

- Indexing defendants who appear only as court-application parties (spec 001 FR-019). It stays deferred; 002 does not change the defendant index.
- The read API: spec 003. 002 only sets constraints for it (FR-041).
- The operations API: spec 004.
- Re-enriching shares stored before 002. Nothing enriches them later.
- Any database migration or backfill. There is no schema change.
- Deployment values in `cpp-aks-deploy` (`CP_BASE_URL`, the system-user secret). That is a separate task.

### Why

*Results does one thing to the payload before it stores it, and the store does the same, so what the store keeps matches what results keeps today.* *Why not the raw event only. To maintain parity with results payload shared by legacy results service.*

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A share whose application lacks results is stored enriched (Priority: P1)

A share arrives with a court application that has no `judicialResults`. The store asks progression for the application. Progression says it is `FINALISED` and has results. The store copies those results into its working copy of the payload, without the three amendment fields, and stores the share.

**Why this priority**: This is the feature. Without it the store keeps less than results keeps today, and consumers moving off results lose application results.

**Independent Test**: Publish a share with one application that has no `judicialResults`, with the progression stub answering `FINALISED` with results. Check `enrichment_applied` is true, `payload_json` holds the results without `amendmentDate`, `amendmentReason` and `amendmentReasonId`, and `payload_text` and `payload_sha256` match the arrived message byte for byte.

**Acceptance Scenarios**:

1. **Given** a share whose application A has no `judicialResults`, **When** progression answers `FINALISED` with results for A, **Then** `payload_json` holds A with those results and `enrichment_applied` is true.
2. **Given** progression's results carry `amendmentDate`, `amendmentReason` and `amendmentReasonId`, **Then** the stored results do not, and every other field of each result is kept.
3. **Given** the same arrival, **Then** `payload_text` is the message exactly as it arrived and `payload_sha256` is the checksum of that text.
4. **Given** a share whose application has `"judicialResults": []`, **Then** it is looked up and enriched the same way.
5. **Given** a share with two applications lacking results, **Then** progression is asked about each, one at a time, in the order they appear.

---

### User Story 2 - No lookup when none is needed (Priority: P2)

A share with no court applications, or whose applications already carry results, is stored with no call to progression.

**Why this priority**: Most shares have no applications. They must not wait on progression or fail when it is down.

**Independent Test**: Publish a share with no `courtApplications` and one whose application already has results. Check both are stored with `enrichment_applied` false and the progression stub received 0 requests.

**Acceptance Scenarios**:

1. **Given** a share with no `courtApplications`, **When** it arrives, **Then** progression is not called and the share is stored as in 001.
2. **Given** every application already has a non-empty `judicialResults`, **Then** progression is not called and `enrichment_applied` is false.
3. **Given** enrichment is switched off, **Then** progression is not called and every share is stored un-enriched.

---

### User Story 3 - Progression has nothing to add (Priority: P3)

Progression answers, but has nothing to copy: the application is unknown (`200 {}`), not `FINALISED`, or has no results. The share is stored as it arrived and the outcome is counted.

**Why this priority**: *Otherwise leave the application as it arrived.* These are normal answers, not failures.

**Independent Test**: Publish shares whose applications the stub answers with `200 {}`, with `LISTED`, and with `FINALISED` but no results. Check each is stored with `enrichment_applied` false, `payload_json` equal to the arrived payload, and the matching outcome counted.

**Acceptance Scenarios**:

1. **Given** progression answers `200 {}` or with no `courtApplication`, **Then** the application is left as it arrived and counted as not found.
2. **Given** progression's `applicationStatus` is not `FINALISED` (including a status that is not a string), **Then** the application is left as it arrived and counted.
3. **Given** progression answers `FINALISED` with no results, **Then** the application is left as it arrived and counted.
4. **Given** a share with two applications where only one receives results, **Then** `enrichment_applied` is true.

---

### User Story 4 - Progression is unavailable (Priority: P4)

Progression returns a server error, times out or cannot be reached. Nothing is stored. The message waits the capped pause and rolls back, and the broker redelivers it. Once progression answers, the share is stored once, enriched.

**Why this priority**: *If progression cannot be reached, the share is retried; it is not stored half-enriched.*

**Independent Test**: Make the stub answer 503 to the first request and 200 `FINALISED` with results to the next. Check nothing is stored after the first delivery, the receipt stays `RECEIVED`, and after the redelivery there is one share, enriched, with the receipt `STORED` and an attempt count of 2.

**Acceptance Scenarios**:

1. **Given** progression answers 408, 429 or any 5xx, or does not answer in time, or the connection fails, **When** a share needs a lookup, **Then** no share, payload, defendant or day row is written and the receipt stays `RECEIVED`.
2. **Given** that failure, **Then** the listener pauses as in 001 (min(2^n seconds, 30 seconds)) and rolls the message back.
3. **Given** progression answers on a later delivery, **Then** exactly one share is stored, enriched.
4. **Given** progression stays unavailable, **Then** the message rolls back until the broker's attempts run out and the broker dead-letters it; the failures are counted for the *Progression lookups failing* alert.

---

### User Story 5 - A misroute or refusal fails closed (Priority: P5)

Progression's answer shows a fault in routing, access or contract: a 404, a 401 or 403, any other unexpected status, or a 200 body that cannot be read. The store does not guess. It treats the share as not yet storable, rolls the message back, and counts the cause so the dashboard shows it.

**Why this priority**: Storing a share un-enriched because a route or user is wrong would silently break parity for every share with an application. Failing closed makes the fault visible and loses nothing.

**Independent Test**: Make the stub answer 404 for an application id. Check nothing is stored, the message is redelivered, and the failure is counted with cause `progression_rejected`. Repeat for 403 (`progression_refused`) and an HTML 200 body (`progression_malformed`).

**Acceptance Scenarios**:

1. **Given** progression answers 404, **Then** nothing is stored and the message is redelivered, counted `progression_rejected`.
2. **Given** progression answers 401 or 403, **Then** nothing is stored and the message is redelivered, counted `progression_refused`.
3. **Given** progression answers a 3xx, another 2xx, or 400, 405, 406, 410 or 415, **Then** the same as a 404.
4. **Given** progression answers 200 with a body that is not JSON, not an object, has `courtApplication` of the wrong type, or has results that are not an array, or the body is cut short, **Then** nothing is stored and the message is redelivered, counted `progression_malformed`.

---

### User Story 6 - A share already stored makes no lookups (Priority: P6)

The broker redelivers a stored share, or hearing re-publishes it with a new message id. The store finds it already stored before asking progression, and makes no calls.

**Why this priority**: Redelivery is normal. It must not load progression or depend on it being up.

**Independent Test**: Publish a share needing one lookup, then publish the same share again with a new message id. Check the stub received exactly one request for that application, and the second receipt is `DUPLICATE`.

**Acceptance Scenarios**:

1. **Given** share S is stored, **When** S arrives again under a new message id, **Then** progression is not called and the receipt is `DUPLICATE`, as in 001.
2. **Given** a receipt already in an end state, **When** its message is delivered again, **Then** progression is not called (001 FR-004).
3. **Given** the existence check itself fails because the database fails, **Then** the attempt is counted at stage `store` and the message rolls back.

---

### User Story 7 - Results the database cannot hold (Priority: P7)

Progression's results contain something the parsed copy cannot hold, such as `\u0000`. The store falls back to the arrived copy, stores the share un-enriched, and counts it.

**Why this priority**: *Never refuse to store.* An odd character in progression's data must not block a share.

**Independent Test**: Make the stub answer `FINALISED` with a result containing `\u0000`. Check the share is stored with `payload_json` equal to the arrived payload, `enrichment_applied` false, and the fallback counted `unstorable_results`.

**Acceptance Scenarios**:

1. **Given** the enriched copy cannot be held in the parsed copy, **Then** the share is stored with the arrived copy and `enrichment_applied` false, counted `unstorable_results`.
2. **Given** the arrived copy cannot be held either, **Then** `payload_json` is empty, as in 001.
3. **Given** any share, **Then** `enrichment_applied` is never true while `payload_json` is empty.

---

### User Story 8 - Operators can see enrichment outcomes (Priority: P8)

Support staff see how many applications were looked up and with what outcome, how long lookups take, how many shares were enriched, and why intake attempts failed at the enrichment stage. Logs carry ids only.

**Why this priority**: The page's *Progression lookups failing* alert depends on these metrics. Progression's answers hold case data and must never reach a log.

**Independent Test**: Run the scenarios of stories 1 to 7 and read the metrics endpoint. Check each counter moved by the expected amount, every tag value comes from a fixed list, and no captured log line contains a response body or the system user id.

**Acceptance Scenarios**:

1. **Given** one enriched share and one share whose application was not found, **Then** the applications counter shows one enriched and one not found, and the enriched-shares counter shows 1.
2. **Given** a progression failure, **Then** the failed-intake counter goes up with stage `enrich` and a cause from the fixed list.
3. **Given** any lookup, **Then** log lines hold the message id, `hearingId`, `shareId` and the application id, and never a response body or the user id.

### Edge Cases

- `judicialResults` present as JSON `null`: treated as missing and looked up. Results would throw here; this is a deliberate deviation.
- An application with no `id`, or an `id` that is not a UUID: skipped, left as it arrived, counted `invalid_id`. Results would throw here; this is a deliberate deviation.
- Two applications with the same id (also when written in different letter case): looked up once; the answer applies to both.
- Progression's results array holding elements that are not objects: copied unchanged. Only objects lose the three amendment fields.
- A number in progression's results such as `1.50`: kept with its value and written precision.
- A response that arrives slowly, a byte at a time, past the read timeout: treated as a timeout.
- A redirect from progression: not followed; treated as a 3xx (FR-021).
- An unknown application id: progression answers `200 {}`, not 404 (*404 ruling*). Research confirms both on the local stack before code.
- Nested results (`courtApplicationCases[].offences[].judicialResults`, court-order offences): neither checked nor changed, as in results.
- A hearing with no `courtApplications` key: no lookup, payload unchanged.

## Requirements *(mandatory)*

### Functional Requirements

**Trigger**

- **FR-001**: Enrichment MUST run after the receipt is committed and the receipt is found not settled, and before the store transaction opens, with no database transaction open (*It is an HTTP call, so it cannot be inside a database transaction*).
- **FR-002**: The store MUST look only at `hearing.courtApplications[]` (*If the hearing has court applications, look at each one*).
- **FR-003**: An application MUST be looked up when its top-level `judicialResults` is missing, JSON `null`, or an empty array (*If an application has no `judicialResults`, ask progression*). Results treats missing and `[]` alike; treating `null` as missing is a deliberate deviation (FR-014).
- **FR-004**: Lookups MUST be made one at a time, in array order. Applications whose ids are equal as UUIDs MUST be looked up once per share.
- **FR-005**: When no application needs a lookup, or enrichment is switched off, the store MUST make no progression call and MUST store the share un-enriched.
- **FR-006**: Before the first lookup, the store MUST check, read-only, whether a share with the same identity is already stored. If it is, the store MUST make no progression call and MUST go on to the store transaction, which marks the receipt `DUPLICATE` (001 FR-014). The unique key with `ON CONFLICT DO NOTHING` stays the real guard.

**Lookup**

- **FR-007**: The store MUST call `GET {base-url}/progression-query-api/query/api/rest/progression/applications/{applicationId}` with `Accept: application/vnd.progression.query.application-only+json` (*`GET /applications/{applicationId}`, media type `application/vnd.progression.query.application-only+json`*).
- **FR-008**: The call MUST carry the store's own system user id in the `CJSCPPUID` header (*with the store's own system user id in the CJSCPPUID header*). It MUST NOT use another service's user. Progression's access rule for this query admits "System Users".
- **FR-009**: The call MUST have a connect timeout and a read timeout, MUST NOT follow redirects, and MUST NOT retry inside the store (*Failures are handled by the broker, not by a loop in the store*).

**Merge and parity**

- **FR-010**: When progression answers with `applicationStatus` `FINALISED` and a non-empty `judicialResults`, the store MUST copy those results into the application, removing `amendmentDate`, `amendmentReason` and `amendmentReasonId` from each result (*copy those results into the payload, leaving out `amendmentDate`, `amendmentReason` and `amendmentReasonId`*). Every other field MUST be kept, and progression's order MUST be kept.
- **FR-011**: Only `judicialResults` MUST be copied from the answer. Only the three amendment fields MUST be removed, and only at the top level of each result. Nothing nested in the share is checked or changed.
- **FR-012**: An added `judicialResults` MUST go last in the application object; a replaced empty array MUST keep its place. Every other part of the payload MUST be unchanged.
- **FR-013**: The enriched copy MUST equal results' enriched payload as JSON (same content; key order and spacing aside). This MUST be proven against results' own fixtures: `app1_adjourned_hearing` with `app1_progression_finalised` (enriched); `app2` (amendment fields removed); `app3_resulted_hearing` (no call); `app_progression_listed` (unchanged); `200 {}` (unchanged); and a share with several applications.
- **FR-014**: The store MUST make these documented, deliberate deviations from results, because the store never refuses to store: explicit `null` results trigger a lookup; an application with a missing or invalid id is skipped and counted `invalid_id`, not thrown; elements of a valid results array that are not objects are copied unchanged.
- **FR-015**: Numbers in progression's results MUST keep their exact value and written precision.

**Persistence**

- **FR-016**: `payload_text` MUST stay the message exactly as it arrived, and `payload_sha256` MUST stay the SHA-256 of that text, unchanged from 001.
- **FR-017**: `payload_json` MUST hold the enriched working copy (the arrived payload when nothing was added). It is permanent: it is no longer a copy that may be dropped after NFT. Key details and the defendant index MUST be read from the enriched copy.
- **FR-018**: `enrichment_applied` MUST be true only when at least one application received results, and MUST be fixed at insert (001 FR-044). It MUST never be true while `payload_json` is empty.
- **FR-019**: If the enriched copy cannot be held in `payload_json` (for example `\u0000`, or the database refuses it as invalid data inside the store transaction), the store MUST roll the store transaction back and run it once more with the arrived copy and `enrichment_applied` false, counted `unstorable_results`. `payload_json` MUST be empty only if the arrived copy cannot be held either (001 FR-015).
- **FR-020**: The store MUST NOT store a share half-enriched. Either every lookup the share needs has answered, or nothing is stored.

**Errors**

- **FR-021**: The store MUST read progression's answer as follows. Every row marked *fail closed* MUST throw a retryable intake failure at stage `enrich` with the stated cause, so the listener pauses (001 FR-045), rolls the message back and the broker redelivers it, and dead-letters it after its attempts. The receipt stays `RECEIVED`.

  | Progression's answer | Outcome |
  |---|---|
  | 200, object with a `courtApplication` object, `FINALISED`, non-empty results | enriched |
  | 200, `courtApplication` not `FINALISED` (including a status that is not a string) | left as it arrived, counted |
  | 200, `FINALISED` with no results | left as it arrived, counted |
  | 200 `{}`, or `courtApplication` missing or `null` | not found: left as it arrived, counted |
  | 404, 3xx, any other 2xx, 400, 405, 406, 410, 415, any other unexpected status | fail closed, `progression_rejected` |
  | 401, 403 | fail closed, `progression_refused` |
  | 408, 429, 5xx | fail closed, `progression_unavailable` |
  | connection refused, unknown host, connection reset | fail closed, `progression_unreachable` |
  | no answer within the read timeout (including a slow drip) | fail closed, `progression_timeout` |
  | 200 with a body that is not JSON, not an object, cut short, has `courtApplication` of the wrong type, or results that are not an array | fail closed, `progression_malformed` |

- **FR-022**: A 404 MUST fail closed. Progression answers an unknown id with `200 {}`; a 404 comes only from a wrong route or path (*404 ruling*). Results treats a 404 as not found; the store does not copy that. Research MUST confirm both answers on the local stack before code; if an unknown id gives 404 there, the 404 row becomes *not found*.
- **FR-023**: A failure's recorded cause MUST be a bounded code and the exception's class, never a message from the parser or a fragment of the body (Principle XI).

**Configuration**

- **FR-024**: Enrichment MUST have an on/off switch, on by default and off in the test profile. Tests that cover enrichment switch it on against a stub.
- **FR-025**: The progression base URL MUST come from `CP_BASE_URL` (the validator's pattern) and MUST have no default. The system user id MUST come from `RESULTS_STORE_SYSTEM_USER_ID` (Key Vault secret `RESULTS-STORE-SYSTEM-USER-ID`) and MUST have no default.
- **FR-026**: When enrichment is on, the service MUST NOT start if the base URL or the system user id is blank, or a timeout is not a positive duration. Defaults: connect 5 seconds, read 10 seconds.

**Metrics and logging**

- **FR-027**: The failed-intake counter MUST gain stage `enrich` with causes `progression_rejected`, `progression_refused`, `progression_unavailable`, `progression_unreachable`, `progression_timeout` and `progression_malformed`. A failed existence check MUST count as stage `store`.
- **FR-028**: An applications counter MUST count each application looked at, by outcome from this fixed list: `enriched`, `not_found`, `not_finalised`, `no_results`, `invalid_id`. Failures are not outcomes of this counter; they are counted on the intake failure counter (FR-027).
- **FR-029**: A lookup timer MUST record each progression call's duration, tagged by outcome. It is recorded when the call ends, not after a commit; the metrics contract MUST state this exception to 001 FR-040.
- **FR-030**: An enriched-shares counter MUST go up by one per share stored with `enrichment_applied` true, after the store transaction commits, using the flag actually stored.
- **FR-031**: The `unstorable_results` fallback MUST be counted.
- **FR-032**: Every new tag MUST come from a fixed, small list (001 FR-040). Log lines MAY hold the application id; they MUST NOT hold a response body, part of one, or the system user id.

**Sweep and extraction**

- **FR-033**: The extraction at intake and the sweep MUST read `payload_json`, and MUST read `payload_text` only when `payload_json` is empty.
- **FR-034**: Shares stored before 002 MUST keep `payload_json` as the un-enriched parsed copy. They MUST NOT be re-enriched. The extractor version MUST NOT change.

**Documentation**

- **FR-035**: Principle II MUST be reworded under the constitution's amendment procedure: `payload_text` is the message as it arrived, with the checksum over it; `payload_json` is the working copy, enriched, and jsonb keeps its content but not key order, spacing or duplicate keys; the removal of the three amendment fields is named. The bump is MINOR (2.0.0 → 2.1.0): the principle gains the working-copy clause; no rule is reversed.
- **FR-036**: Spec 001's FR-015, FR-016, FR-036 and Key Entities MUST be updated to match *Changes to spec 001* below, as must `code-reviewer.md` (*Payload altered*), the metrics and configuration contracts, and the data model. The data model MUST note that the V3 column comments are now stale; no migration changes them.
- **FR-037**: The page wording for *Data model* bullet 2, the `hearing_share_payload` row and the read API's payload row MUST be written as forward notes for the page owner, with the same meaning as FR-035.

**End-to-end**

- **FR-038**: The integration tests MUST run against a progression stub and cover: enriched; no applications (0 calls); application already resulted (0 calls); `200 {}`; 503 then 200 (redelivered, then stored once); 404 (redelivered); re-publish under a new message id (one request in total); and a malformed or HTML body with no body text in the logs.
- **FR-039**: The container smoke script MUST run a progression stub in the compose stack, publish a share with an application lacking results, and check `enrichment_applied` true, the results in `payload_json`, and the stub's request count for that path. The 001 smoke cases MUST still pass.

**Forward constraints**

- **FR-040**: Nothing in 002 serves a payload. Spec 003 MUST follow FR-041.
- **FR-041**: The read API MUST serve `payload_json`, with the `ETag` computed over the exact bytes served; and MUST serve `payload_text` when `payload_json` is empty.

### Changes to spec 001

002 amends these parts of spec 001. Spec 001 is updated to match at the end of 002 (FR-036); until then this section is the record.

- **FR-015**: the parsed copy is no longer unread. It holds the enriched working copy, is permanent, and is the copy key details are read from. The text stays exactly as it arrived.
- **FR-016**: unchanged in meaning; stated plainly that `payload_sha256` is over the arrived text, not the enriched copy.
- **FR-036**: the sweep reads the parsed copy (`payload_json`), and the text only when the parsed copy is empty.
- **FR-019**: still deferred. Application-party defendants are not indexed by 002.
- **Key Entities**: *Payload* and *Share* as below.

### Key Entities *(include if feature involves data)*

- **Payload (`hearing_share_payload`)**: one row per share. `payload_text` is the message exactly as it arrived, with its size in bytes. `payload_json` is the working copy: the arrived payload with any finalised application results added, without the three amendment fields. It is permanent, the copy key details and the sweep read, and the copy the read API will serve. It is empty only when the database cannot hold even the arrived copy. Never updated.
- **Share (`hearing_share`)**: `payload_sha256` is the checksum of `payload_text`. `enrichment_applied` is true when at least one application received results at intake; false otherwise, including when progression had nothing to add, enrichment was off, or the fallback was used. Fixed at insert.
- **No schema change.** No new table, column or migration.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The parity suite on results' fixtures (app1, app2, app3, listed, `200 {}`) and the several-applications case is 100 % green.
- **SC-002**: In the integration tests, shares with no applications, or with applications already resulted, cause 0 progression calls.
- **SC-003**: Every row of the error table (FR-021) has a passing test, including a slow drip, a cut-short body and a redirect.
- **SC-004**: In the integration test, the 404 case is redelivered and nothing is stored; the 503-then-200 case ends with exactly 1 share, enriched, and a receipt with 2 attempts.
- **SC-005**: A share re-published under a new message id causes exactly 1 progression request in total.
- **SC-006**: For 100 % of enriched shares in the tests, `payload_text` and `payload_sha256` are byte-identical to the arrived message.
- **SC-007**: 0 shares in the tests have `enrichment_applied` true with `payload_json` empty.
- **SC-008**: 0 log lines captured in the tests contain a progression response body or the system user id; 0 metric tags hold an id.
- **SC-009**: The service fails to start in 100 % of the startup tests with enrichment on and a blank base URL, blank user id or bad timeout.
- **SC-010**: The container smoke check passes with `enrichment_applied=true`, the results in `payload_json`, and the 001 cases still passing.
- **SC-011**: The build gate passes: line coverage at least 0.88, branch coverage at least 0.85 (JaCoCo), with PMD clean.

## Assumptions

Settled implementation choices from the approved plan, stated so they are visible:

- Parity means equal JSON content, not equal bytes; results rebuilds the whole payload, so byte parity is not possible.
- The enriched copy is written as compact JSON with non-ASCII characters escaped; the arrived text is kept when nothing was added.
- The progression client is a Spring `RestClient` over `HttpComponentsClientHttpRequestFactory` (Apache HttpClient 5, subclassed as `NoRedirectRequestFactory`), with automatic retries and redirects off and a fresh connection for every lookup. Each lookup has one absolute deadline (the read timeout, from when the request is created) that cancels it, and the client aborts the exchange on any status other than 200, so that body is never read. Status and body are read in one place. (Amended after gate round 1 of T002: research R11, R15.)
- The existence check reuses the store's existing-share lookup.
- The `unstorable_results` fallback re-runs the whole store transaction once.
- No environment outside tests holds 001 rows yet, so leaving them un-enriched loses nothing.
- The V3 column comments are left as they are.
- Principle II is reworded (FR-035). Principle VI's retryable-failure wording is also widened in the same 2.1.0 amendment, to cover a progression answer the store cannot accept (a routing, access or contract fault), which fails closed and goes back to the broker (FR-021, FR-022). No other principle changes, and no rule is reversed.
