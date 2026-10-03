# Research: Enrichment

**Feature**: `002-enrichment` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

Each entry gives the decision, why, and what else was looked at. R1 to R4 settle where the results
come from and what the store may rely on; R5 to R10 the working copy; R11 to R15 the progression
call and its answers; R16 to R19 persistence and the sweep; R20 to R25 configuration, metrics, the
build and the constitution wording. Every open point is settled here; none is left for the
implementer.

Sources: the approved orchestration plan for 002 (decisions taken with Sachin on 2026-10-03, the
points settled from the facts, the 404 ruling and the error table); the design review and its
critic; the fact-finding maps of results' enricher (cpp-context-results @063472490) and
progression's application-only query; the cross-session source check and the local-stack test of
2026-10-03; and this repository at `28c329c`.

---

## R1. Where the application results come from: progression

**Decision.** The store asks progression's query API for each application that arrives without
results: `GET /progression-query-api/query/api/rest/progression/applications/{applicationId}`,
media type `application/vnd.progression.query.application-only+json`. This is the same query results
makes today (`ProgressionService.getApplicationDetails`, cpp-context-results
`ProgressionService.java:88-100`, action `progression.query.application-only`).

**Rationale.** Hearing does not hold the results the store needs, and progression does:

- **Hearing has no source.** Hearing has no endpoint that returns application-level
  `JudicialResult` objects (`hearing-query-api.raml:108-152`; `HearingQueryView.java:208-262`).
  `PublishResultsDelegateV3.updateApplicationLevelJudicialResults` (`:243-310`) builds
  `courtApplications[].judicialResults` from the current share only, and sets the array only when
  it is non-empty; otherwise it sets only `applicationStatus`
  (`ApplicationStatusHelper.java:26-41`). `HearingDelegate.initiate` strips any application results
  a new hearing inherited (`HearingResultsCleanerUtil.java:66-77`). So a later hearing of an
  application arrives without the results an earlier hearing gave it.
- **Progression keeps the copy.** `ApplicationAggregate.hearingResulted` (`:840-871`) is fed by
  `hearing-resulted` and the legacy `public.hearing.resulted`. It carries results forward under the
  CCT-2260 rule (`:845-854`). `FINALISED` is set only from an application-level `FINAL` result
  (`:889-893`); a later non-`FINAL` result turns the status back to `LISTED`.
- **What progression's view drops.** The viewstore leaves out results marked `publishedForNows`
  (`CourtApplicationEventListener.java:507-551`). Results has the same gap today, because it asks
  the same view, so parity is unaffected.
- **`FINALISED` without results is possible** (a status patch, a summons rejection). That is the
  `no_results` outcome (R13).
- **Timing.** The lookup matters only for a later hearing of an application already finalised by
  an earlier one. Progression has handled the earlier hearing long before the later one is shared,
  so there is no race between the share arriving and progression's copy being written.

**Alternatives considered.**

- *The push event* `public.progression.hearing-resulted-application-updated`
  (`CourtApplicationProcessor.java:751-763`). Not used in 002: it would need a second subscription
  and a join with the share, and the page names the query.
- *Deriving the results from the store's own history.* A possible future option, once the store's
  history is complete and only if Principle IV is amended to allow it. Not now: the history has a
  gap (shares before go-live), the carry-forward rules would drift from progression's, the store has
  no index by application, and parity is defined by what progression answers.
- *Hearing's query API.* Has no such endpoint (above).

---

## R2. The seam: between the receipt and the store transaction

**Decision.** Enrichment runs in `IntakeService.store(...)`, after the receipt transaction has
committed and the settled check has passed (`IntakeService.receive`, the `Share` branch), and
before `extractor.extract(...)`. No database transaction is open there. Order per share:

1. receipt transaction (unchanged);
2. settled check (unchanged): a settled receipt makes no lookups (001 FR-004);
3. a pure scan of `hearing.courtApplications[]` for the ids that need a lookup (R7);
4. if none, or enrichment is off: no read and no call;
5. otherwise the read-only existence check (R19); if the share is already stored, no call;
6. otherwise the lookups, one at a time, in array order;
7. the enriched tree on a deep copy (R5);
8. `KeyDetailsExtractor` on the enriched tree;
9. the `StoreRequest`: arrived text, checksum and byte count unchanged; the parsed copy (R6); the
   flag;
10. the store transaction, which binds `enrichment_applied`; the fallback re-run if the database
    refuses the enriched copy (R18).

**Rationale.** Principle VI: the HTTP call is never inside a database transaction, so a slow
progression never holds the hearing-day lock or a pooled connection. The receipt is already
committed, so a failed lookup still leaves the attempt count raised (001 FR-002).

**Alternatives considered.** Before the receipt: a failed lookup would leave no receipt and no
attempt count. Inside the store transaction: forbidden by Principle VI.

---

## R3. The 404 ruling, confirmed on the local stack

**Decision.** `200 {}` means "no such application" (`not_found`, left as it arrived). A 404 fails
closed with cause `progression_rejected`.

**Evidence from source.**

- `ApplicationQueryView.getApplicationOnly` (`:349-365`) catches `NoResultException` and returns
  an envelope whose payload is an empty object.
- The framework turns only a `null` payload into 404 (`ResponseStrategyHelper.java:44-47`). An
  empty object is not `null`.
- Response-schema validation is off unless `rest.dispatcher.response.json.validation.enabled` is
  set (default `false`, `DispatcherConfiguration.java:12`), and no environment sets it, so `{}`
  is not refused for lacking the schema's required `courtApplication`.
- A 404 can therefore come only from the mesh (there is no catch-all route in `istio-config`), the
  gateway, or RESTEasy on a wrong sub-path: a routing or contract fault.
- Results' generated client treats a 404 as "not found" (`DefaultRestClientProcessor.java:307-322`
  swallows it as an empty answer). That weakness is not copied: a wrong route would otherwise store
  every application-bearing share un-enriched, with no failure counted.

**Evidence from the local stack (2026-10-03).** `cpp-dev-environment`, nginx gateway on
`localhost:8080`, progression and usersgroups WildFly behind Envoy:

| Request | Answer |
|---|---|
| unknown application id, valid system user | `200 {}`, `Content-Type: application/vnd.progression.query.application-only+json` |
| no `CJSCPPUID`, or an unknown one | `403` with an access-control body |
| wrong sub-path under `/applications/` | `404`, empty body (from WildFly) |
| prefix the gateway does not route | `404` with the gateway's body |
| wrong `Accept` | `406` |

This confirms the ruling: `200 {}` is "not found"; a 404 is a routing or contract fault and fails
closed. Spec FR-022's fallback clause (turning the 404 row into *not found*) does not apply.

---

## R4. Parity: equal JSON content, proven on results' fixtures

**Decision.** The enriched tree must equal results' enriched payload as JSON content
(JSONAssert `STRICT`: no extra fields, arrays in order; object key order is not compared). Proven
in `ApplicationResultsParityTest` against results' own fixtures, copied into
`src/test/resources/progression/` from
`cpp-context-results@063472490:results-event/results-event-processor/src/test/resources/testdata/application-final-results-enricher/`:

| File | Size | Role |
|---|---|---|
| `app1_adjourned_hearing.json` | 21.6 KB | share; application `9bfd5897-…` without `judicialResults` |
| `app1_progression_finalised.json` | 18.8 KB | progression's `courtApplication`: `FINALISED`, 1 result |
| `app1_adjourned_hearing_enriched.json` | 23.3 KB | expected: the result added, last in the application |
| `app2_adjourned_hearing.json` | 17.2 KB | share; application `47663a56-…` without results |
| `app2_progression_finalised_resultsamended.json` | 9.6 KB | `FINALISED`, 1 result carrying the three amendment fields |
| `app2_adjourned_hearing_enriched.json` | 19.0 KB | expected: the result without the three fields |
| `app3_resulted_hearing.json` | 31.9 KB | share whose application already has results: no call |
| `app_progression_listed.json` | 19.2 KB | `LISTED`, 1 result: application left as it arrived |

The progression files hold the `courtApplication` object alone; results' test wraps each as
`{"courtApplication": <file>}` and its fake answers any id (`ApplicationFinalResultsEnricherTest.java:42-55`).
The store's fake port does the same wrapping; the listed fixture's own id (`18299a7a-…`) differs
from app1's, so the fake answers by the share's id, as results' `any(UUID.class)` does.

Cases (FR-013): app1 enriched; app2 amendment fields removed; app3 no call; app1 with the listed
answer unchanged; app1 with `200 {}` unchanged; and a several-applications case built from these
files: app1's and app2's applications plus a third answered `LISTED`, whose expected form takes
each enriched application from results' own enriched fixtures, so the expected content comes from
results' output, not from the store's code. The fixtures carry no `_metadata`; the enricher works on
the body tree, so that does not matter.

**Rationale.** Results rebuilds the whole payload through JSON-P and writes it with `toString()`,
so byte parity is impossible. Content parity is what consumers see.

**Key placement.** `STRICT` does not check key order, and jsonb does not keep it either. Key
placement is asserted separately, on the tree, by iterating `fieldNames()` (R5).

**Deliberate deviations (FR-014).** Results throws where the store must not, because the store never
refuses to store (Principle V). Each is a documented difference, not parity:

| Input | Results (`ApplicationFinalResultsEnricher.java:54-81`) | Store |
|---|---|---|
| `judicialResults: null` | `getJsonArray` throws `ClassCastException` | treated as missing: looked up |
| application `id` missing or not a UUID | `UUID.fromString` throws | skipped, left as it arrived, counted `invalid_id` |
| `courtApplications` not an array | throws | no work |
| answer's `courtApplication` is JSON `null` | throws | `not_found` |
| non-object element inside a valid results array | `getJsonObject` throws | copied unchanged |
| the same id twice in one share | called twice | looked up once, by parsed UUID (R8) |

Results' fail-closed behaviour on progression errors is kept (R13).

---

## R5. The working copy: a deep copy changed with `ObjectNode.set`

**Decision.** `ApplicationResultsEnricher` works on `body.deepCopy()`, never on the parsed body
itself, and only when at least one application is to receive results. For each application whose id
answered `enriched`, it builds a new array: each object element is deep-copied and loses
`amendmentDate`, `amendmentReason` and `amendmentReasonId` (top level of the result only); any other
element is deep-copied unchanged. It then calls `application.set("judicialResults", array)`.

**Rationale.** `ObjectNode` is backed by a `LinkedHashMap`: `set` on an existing key (a `null` or
`[]`) keeps its position, and a missing key is added last. Checked on Jackson 3.1.7:
`{"id":"x","judicialResults":[],"z":1}` becomes `{"id":"x","judicialResults":[1],"z":1}`, and
`{"id":"x","z":1}` becomes `{"id":"x","z":1,"judicialResults":[1]}`. That matches results' JSON-P
builder (`app1_adjourned_hearing_enriched.json:315`, the key after `id`) and FR-012. The parsed body
stays as it arrived, so tests can assert it was not changed.

**Alternatives considered.** Changing the parsed body in place: the arrived tree would be lost and
tests could not compare. Rebuilding the payload: needless; nothing else changes.

---

## R6. Serialising the working copy: compact, `ESCAPE_NON_ASCII`

**Decision.** When at least one application received results, the parsed copy sent to the database
is the enriched tree written by a writer derived from the application's mapper:
`mapper.writer().without(SerializationFeature.INDENT_OUTPUT).with(JsonWriteFeature.ESCAPE_NON_ASCII)`.
When nothing was added, the parsed copy is the arrived text itself, exactly as in 001.

**Rationale.**

- `NulSafety` checks `\uXXXX` escapes only. Jackson reads a progression `"\udc00"` into a raw lone
  surrogate and, by default, writes it back raw; `NulSafety` would pass it and pgjdbc would turn it
  into `?` or fail. With `ESCAPE_NON_ASCII` every non-ASCII character is written as an escape that
  `NulSafety` sees. Checked on 3.1.7: `"\udc00 é"` is written as `"\uDC00 é"`; without the
  feature it is written raw. `NulSafety` reads hex digits in either case (`Character.digit`).
- jsonb decodes escapes, so the stored content is the same either way.
- A derived writer keeps `spring.jackson.*` settings (indentation and so on) out of the stored copy
  and never changes the shared mapper.
- Writing the arrived text when nothing was added keeps the 001 path byte for byte the same.

---

## R7. What triggers a lookup

**Decision.** Only `hearing.courtApplications[]` is scanned. An element that is an object is a
candidate when its top-level `judicialResults` is missing, JSON `null`, or an empty array. A
present non-empty array, or a value of another type (string, object, number), is left alone. Its
`id` must be a textual canonical UUID (`CanonicalUuid`); otherwise the application is skipped and
counted `invalid_id` (R4). If `courtApplications` is missing or not an array, there is no work.
Nested results (`courtApplicationCases[].offences[].judicialResults`, court-order offences) are
neither checked nor changed.

**Rationale.** Results checks `judicialResults == null || isEmpty()`, which covers missing and `[]`
(`ApplicationFinalResultsEnricher.java:54-55`); `null` is the deliberate deviation (R4).

---

## R8. Lookups one at a time, de-duplicated by UUID

**Decision.** Distinct ids, by parsed `UUID`, in first-seen array order (`LinkedHashSet<UUID>`).
Each is looked up once per attempt; its answer applies to every application with that id, also when
written in a different letter case. No caching across shares or attempts.

**Rationale.** `CanonicalUuid` accepts upper and lower case, so `ABC…` and `abc…` are one
application. One at a time keeps progression's load and the store's failure handling simple (FR-004).

---

## R9. Reader features for progression's answers and for the share

**Decision.** Both readers are derived from the application's mapper, never by changing it:

```java
mapper.reader()
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
```

The progression client reads answers with it, and `ShareIdentityParser`'s reader gains the second
and third features (it already has the first, `ShareIdentityParser.java:63`).

**Feature names checked on the classpath** (`tools.jackson.core:jackson-databind:3.1.7`,
`jackson-core:3.1.7`, from the Boot 4.1.1 BOM):

| Feature | Class | Default in 3.1.7 | Set to |
|---|---|---|---|
| `FAIL_ON_TRAILING_TOKENS` | `tools.jackson.databind.DeserializationFeature` | **true** (changed from 2.x) | on, stated explicitly |
| `USE_BIG_DECIMAL_FOR_FLOATS` | `tools.jackson.databind.DeserializationFeature` | false | on |
| `STRIP_TRAILING_BIGDECIMAL_ZEROES` | `tools.jackson.databind.cfg.JsonNodeFeature` (not `DeserializationFeature`) | **false** (it was true in late 2.x) | off, stated explicitly |
| `ESCAPE_NON_ASCII` | `tools.jackson.core.json.JsonWriteFeature` | false | on (writer, R6) |
| `INDENT_OUTPUT` | `tools.jackson.databind.SerializationFeature` | false | off on the derived writer |

3.1.7 also has `JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS`. Setting the `DeserializationFeature`
alone was enough for `readTree` in the check below, so the plan uses that one; a test pins the
behaviour so a later Jackson cannot change it silently.

**Checked behaviour (3.1.7).** Input `{"a":1.50,"b":1e3,"c":12345678901234567890.123}`:

- default reader and writer: `{"a":1.5,"b":1000.0,"c":1.2345678901234567E19}` (precision lost);
- with the reader above: `{"a":1.50,"b":1E+3,"c":12345678901234567890.123}`.

`1.50` keeps its written precision and the long value its exact digits (FR-015). `1e3` is written
`1E+3`: the same value, and jsonb stores both as `1000`. Trailing content (`{"a":1} x`) is refused
with `StreamReadException`; a cut-short body (`{"a":`) with `UnexpectedEndOfInputException`; an empty
body reads as a `MissingNode` and the JSON `null` as a `NullNode`, neither an object (R13:
`progression_malformed`).

**Why the share's reader changes too.** The enriched copy is written from the share's parsed tree.
Without `USE_BIG_DECIMAL_FOR_FLOATS`, every decimal in the arrived share would be rewritten through a
`double` in an enriched copy. The identity and the key details read strings, booleans and UUIDs, so
nothing else changes. Integers already widen to `long`/`BigInteger` without loss.

---

## R10. The arrived text stays as it arrived

**Decision.** `payload_text`, `text_bytes` and `payload_sha256` are computed over the arrived text,
unchanged from 001 (FR-016). Only the parsed copy changes meaning (data-model.md).

**Rationale.** The checksum must always be reproducible against the broker message. Decision taken
with Sachin, 2026-10-03.

---

## R11. The client: `RestClient` over Apache HttpClient 5 (`NoRedirectRequestFactory`), redirects and retries off, `exchange()`

**Decision.** `adapter/progression/ProgressionApplicationClient` implements the
`ProgressionApplications` port with a Spring `RestClient` built in `ProgressionConfig`:

- base URL `resultsstore.progression.base-url`; path constant
  `/progression-query-api/query/api/rest/progression/applications/{applicationId}`, the id passed as a
  URI template variable after `CanonicalUuid` has accepted it;
- headers `Accept: application/vnd.progression.query.application-only+json` and
  `CJSCPPUID: <resultsstore.progression.system-user-id>`; never another service's user and never the
  sharing user (FR-008);
- request factory (as built, after the amendment below): `NoRedirectRequestFactory`, a subclass of
  `HttpComponentsClientHttpRequestFactory` over Apache HttpClient 5 with automatic retries and
  redirects off and a fresh connection for every lookup; connect and socket timeouts from the
  properties; one absolute deadline per lookup (R15). The first draft, a subclass of
  `SimpleClientHttpRequestFactory` calling `setInstanceFollowRedirects(false)`, was replaced; its
  reasons are kept below as history;
- `exchange((request, response) -> …)`, so status and body are classified in one method and Spring's
  default status handlers (whose exception messages carry the body) never run;
- the body is read through a deadline guard (R15), then parsed with the reader of R9.

**Rationale.**

- The YOT client (`ResultsQueryHearingPayloadClient`, built in `LivePayloadConfig.java:127-173`) is
  the closest working pattern on the estate. Two differences: no retry loop, and always the store's
  own system user.
- Spring 7.0.9's `SimpleClientHttpRequestFactory.prepareConnection` sets
  `setInstanceFollowRedirects(true)` for `GET` (checked with `javap`). `HttpURLConnection` would then
  follow a redirect and send the user-set `CJSCPPUID` to the new location. With redirects off, a 3xx
  reaches the classifier and fails closed (`progression_rejected`).
- `DefaultRestClient` turns an `IOException` thrown inside the exchange function into a
  `ResourceAccessException`, whose message holds the URL. So the client catches `IOException` inside
  its own function and throws its own classified exception (R13); a runtime exception passes through
  `exchange` unchanged.

**Alternatives considered.** `retrieve()` with status handlers: the default handlers put the body in
the exception message. `JdkClientHttpRequestFactory`: its read timeout bounds only the wait for the
headers, not the body, so it would still need the guard in R15; the YOT pattern is kept.
`RestTemplate`: older API, no gain.

**Amended after gate round 1 of the implementation (T002).** `HttpURLConnection` resends a `GET` once
when the connection fails before the status line (a reset gave two requests in
`ProgressionApplicationClientTest`), and no setting turns that off, which breaks FR-009 and R12. Its
per-read timeout also cannot bound a status line and headers sent a byte at a time. The transport is
therefore `HttpComponentsClientHttpRequestFactory` over Apache HttpClient 5 (`httpclient5`, version
from the Boot BOM), subclassed as `NoRedirectRequestFactory`: automatic retries, redirects, content
compression, cookies and protocol upgrades off; no connection reuse; connect and socket timeouts
from the properties; and each request cancelled once the response deadline (R15) has passed. The
rest of this decision (headers, `exchange()`, the deadline guard on the body, the reader) stands. On
any status other than 200 the client aborts the exchange (`ProgressionExchange.abort()`), so a body
the status has already decided is never read or drained.

---

## R12. No in-process retries

**Decision.** One request per application per attempt. Every failure throws at once; the listener's
capped pause (001 FR-045, `min(2^n s, cap)`) and the broker's redelivery do the retrying.
`Retry-After` is ignored.

**Rationale.** Principle VI: "There is no retry loop in the store." The receipt's attempt count then
records every try.

---

## R13. The error table, with causes

**Decision.** One lookup's answer is read as follows. *Fail closed* means: throw
`RetryableIntakeException(ENRICH, cause)`; `IntakeService.counted` moves
`resultsstore.intake.failed{stage=enrich,cause}` once; the listener pauses and rethrows; the JMS
session rolls back; the broker redelivers and, after its attempts, dead-letters. The receipt stays
`RECEIVED`.

| Progression's answer | Outcome | Cause or counter tag | Why |
|---|---|---|---|
| 200, object, `courtApplication` an object, `applicationStatus` the string `FINALISED`, `judicialResults` a non-empty array | results copied | `enriched` | the case results handles |
| 200, `courtApplication` an object, status missing, not a string, or not `FINALISED` | left as it arrived | `not_finalised` | results reads `getString(…, "")` |
| 200, `FINALISED`, `judicialResults` missing, `null` or `[]` | left as it arrived | `no_results` | nothing to copy |
| 200 `{}`, or `courtApplication` missing or JSON `null` | left as it arrived | `not_found` | progression's answer for an unknown id (R3) |
| 404 | fail closed | `progression_rejected` | routing or contract fault (R3) |
| 3xx (not followed), 2xx other than 200, 400, 405, 406, 410, 415, any other status not listed | fail closed | `progression_rejected` | the request or route is wrong; a retry alone will not fix it, but nothing is lost |
| 401, 403 | fail closed | `progression_refused` | the system user is missing, unknown, or not in "System Users" |
| 408, 429, 500-599 | fail closed | `progression_unavailable` | progression or the mesh is struggling |
| connection refused, unknown host, connection reset, any other I/O failure before the status line is read | fail closed | `progression_unreachable` | network or DNS |
| `SocketTimeoutException` on connect or on a read, or the response deadline passed while reading the body (R15) | fail closed | `progression_timeout` | slow or stuck |
| 200 whose body is not JSON (HTML included), is empty, has trailing content, is not an object (array, string, number, `null`), is cut short, or whose `courtApplication` is present and neither an object nor `null`, or whose `courtApplication.judicialResults` is present and neither an array nor `null` | fail closed | `progression_malformed` | the contract is broken; never guess |

An I/O failure while reading the body of a 200 (other than a timeout) is a cut-short body:
`progression_malformed`. Before the status line it is `progression_unreachable`. WireMock pins both:
`Fault.CONNECTION_RESET_BY_PEER` gives `unreachable`, `Fault.MALFORMED_RESPONSE_CHUNK` on a 200 gives
`malformed`.

The `judicialResults` type check applies whatever the status, as the spec's table states (FR-021).
A status that is not a string is `not_finalised`, never `malformed`.

Share-level cases:

| Case | Outcome |
|---|---|
| existence check fails (`DataAccessException`) | `RetryableFailures.classify(STORE, …)`: `stage=store`, cause `lock_timeout`, `statement_timeout` or `database`; rolled back |
| existence check finds the share | no call; the store transaction gives `Duplicate`; receipt `DUPLICATE`; `enrichment.skipped{reason=already_stored}` |
| enrichment off | no call; stored un-enriched; `enrichment.skipped{reason=disabled}` |
| the database cannot hold the enriched copy | fallback (R18); `enrichment.skipped{reason=unstorable_results}` |
| an unexpected `RuntimeException` in the client or the enricher (a bug) | `counted` moves `intake.failed{stage=enrich,cause=other}`; rethrown; rolled back; never swallowed |

**Rationale.** Failing closed loses nothing: the message is redelivered and, at worst, waits on the
dead-letter address with its receipt `RECEIVED`, visible to reconciliation. Storing un-enriched on a
fault would break parity silently for every share with an application. Distinct causes let the
*Progression lookups failing* alert and the dashboard tell a down service from a wrong route or user.

**Alternatives considered.** 404 as `not_found` (results' and YOT's choice): rejected (R3). 401/403 or
`rejected` as non-retryable with a straight dead-letter: the listener has no such path, and the
broker's attempts are cheap after the capped pause; the counter carries the distinction.

---

## R14. No exception text or body in failures and logs

**Decision.** The client's `RetryableIntakeException` chains no cause. A new constructor takes the
stage, the cause and the failed exception's class name, and the message is
`intake failed at ENRICH: PROGRESSION_MALFORMED (UnexpectedEndOfInputException)`. Log lines from the
client hold the message id and share ids from the MDC, the application id, the status code and the
cause; never the body, any part of it, the URL query or the system user id.

**Rationale.** A `JacksonException` message quotes the offending token (for example
`Unrecognized token '<html>…'`), and a chained cause would print it in the listener's stack trace.
Principle XI forbids a payload fragment in a log or a reason. `NoPayloadInLogsIT` gains a malformed
body, an HTML 500 and a 403 case, each holding a marker string that must appear in no captured line.

---

## R15. Timeouts and the slow drip

**Decision (as built).** Connect timeout 5 s and read timeout 10 s by default (FR-026). The read
timeout is also one absolute deadline for the whole lookup, fixed when `NoRedirectRequestFactory`
creates the request: a daemon thread cancels the request once it has passed (closing the connection,
which ends a blocked read in the status line, headers or body), and the body stream is wrapped in a
guard (`adapter/progression/DeadlineInputStream`) that throws `SocketTimeoutException` on any read
after it. The per-read socket timeout stays as well. Apache HttpClient 5 makes the call, with
retries and redirects off and a fresh connection per lookup (R11); a non-200 status aborts the
exchange. The first draft (a guard on the body alone, timed from when the request was sent, over
`HttpURLConnection`) is kept below as history.

**Rationale.** `HttpURLConnection`'s read timeout is per read: a server sending one byte every few
seconds never trips it. The spec treats a slow drip past the read timeout as a timeout. The guard
bounds one lookup to about connect + read timeout + one socket read (about 15 to 25 s at the
defaults), with no extra thread. WireMock's chunked dribble delay pins it.

**Amended after gate round 1 (T002).** The guard on the body cannot see a slow status line or slow
headers. The request factory now also cancels each request once the deadline has passed since it was
created, which closes the connection and ends a blocked read at once; the client reads any I/O
failure after its deadline as `progression_timeout`. This needs one daemon thread per factory, which
only ever cancels requests. One lookup is then bounded by about the connect timeout plus the read
timeout.

**Worst case per share.** N lookups × that bound. Shares with applications usually carry one or two;
the broker has no processing timeout on a consumer, and the store transaction opens only after the
lookups, so a long run holds no lock or connection.

---

## R16. Existence check before any lookup

**Decision.** `ShareStore` gains `Optional<UUID> storedShareId(ShareIdentity)`: the existing
`EXISTING_SHARE` select (`JdbcShareStore.java:88-91`) run as one autocommit read, outside any
transaction, with no lock. It runs only when at least one lookup is needed and enrichment is on.
Found: no lookups, and the store transaction gives `Duplicate`. A failure is classified at stage
`store`.

**Rationale.** A share re-published under a new message id is the case that matters: a redelivery of
the same message id already stops at the settled receipt (001 FR-004) before any scan. The unique key
with `ON CONFLICT DO NOTHING` stays the real guard (FR-006): a share stored between the check and the
transaction is still caught there.

**Test.** The critic's point: `FailingFirstCommitConnectionFactory` redelivers the same message id,
so it proves the settled check, not this one. The IT publishes the same share twice under two message
ids and asserts one progression request in total and `enrichment.skipped{reason=already_stored}` = 1.

---

## R17. `enrichment_applied` and the stored flag

**Decision.** `INSERT_SHARE` binds `:enrichmentApplied` from the request. `StoreResult.Stored` gains
`enrichmentApplied`, the value actually stored, and `IntakeService` counts
`resultsstore.enrichment.applied` after the commit from that value only.

**Rationale.** The column is fixed at insert (`hearing_share_guard`, V3 `:135-146`), so it must be
right in the insert. Reporting the stored value, not the requested one, keeps the counter true after
the fallback (R18).

---

## R18. One fallback for an enriched copy the database cannot hold

**Decision.** If the enriched copy cannot be held in `payload_json`, the store runs the whole store
transaction again, once, with the arrived text as the parsed copy and `enrichment_applied = false`,
and counts `enrichment.skipped{reason=unstorable_results}`. Two triggers, one path:

1. before the transaction: `NulSafety.isJsonbSafe(parsedCopy)` is false for the enriched copy
   (`\u0000`, or an unpaired surrogate escape, now always visible thanks to R6);
2. inside it: the payload insert of the enriched copy raises SQLSTATE class 22.

In both, `JdbcShareStore.store` returns a new `StoreResult.EnrichedCopyRefused`: in case 1 without
opening a transaction; in case 2 after `status.setRollbackOnly()`, so nothing is written. The
enriched insert takes no savepoint. `IntakeService` then calls `store` once more with the arrived copy.
The 001 rule then applies as it is: if the arrived text cannot be held either, `payload_json` is NULL
(savepoint path, `parsed.copy.skipped`).

**Rationale.** The critic found the two earlier fallbacks inconsistent (arrived copy in one, NULL in
the other) for the same situation. Re-running a rolled-back transaction is safe: nothing committed.
It also leaves the chain, defendant and savepoint code of 001 (`JdbcShareStore.java:215-240`,
`:399-416`) as it is. Result: `enrichment_applied` is never true with `payload_json` NULL (FR-018).

**Alternatives considered.** Moving the savepoint before the share insert and re-inserting the share
with the flag false: more change to tested code for the same result.

---

## R19. The sweep and the extraction read `payload_json`

**Decision.** `ShareStore.payloadText(UUID)` becomes `payloadForExtraction(UUID)`:
`SELECT COALESCE(payload_json::text, payload_text) FROM hearing_share_payload WHERE share_id = :shareId`.
`ExtractionSweep.readAndExtract` parses that and extracts as before. At intake, the extractor runs on
the enriched tree. The sweep never calls progression. `EXTRACTOR_VERSION` does not change.

**Rationale.** FR-033 and FR-017: the working copy is what key details are read from. The extractor
does not read `courtApplications`, so today the projection is the same either way; reading the
working copy keeps that true if a later version reads application fields. jsonb normalises key order,
spacing and numbers, which does not affect extraction.

**Pre-002 rows.** Their `payload_json` is the un-enriched cast of the arrived text; the sweep reads it
and gets the same projection. No component ever looks them up again. No environment outside tests
holds 001 rows: the store has no deploy values yet. A backfill, if ever wanted, would need a migration
that relaxes `hearing_share_guard` (the flag is a fixed column) and the payload guard.

---

## R20. Configuration: `CP_BASE_URL` with no default

**Decision.**

| Property | Source | Default |
|---|---|---|
| `resultsstore.enrichment.enabled` | `RESULTSSTORE_ENRICHMENT_ENABLED` | `true` (`false`, literally, in the test profile) |
| `resultsstore.progression.base-url` | `${CP_BASE_URL:}` | none |
| `resultsstore.progression.system-user-id` | `${RESULTS_STORE_SYSTEM_USER_ID:}` (Key Vault `RESULTS-STORE-SYSTEM-USER-ID`) | none |
| `resultsstore.progression.connect-timeout` | `RESULTSSTORE_PROGRESSION_CONNECTTIMEOUT` | `5s` |
| `resultsstore.progression.read-timeout` | `RESULTSSTORE_PROGRESSION_READTIMEOUT` | `10s` |

With enrichment on, the service does not start when the base URL is blank, not an absolute http(s)
URL, or has a path, query or fragment; when the system user id is blank or not a canonical UUID; or
when a timeout is outside its range (contracts/configuration.md).

**Rationale.**

- **`CP_BASE_URL`.** In `cpp-aks-deploy`, Spring Boot services reach other contexts through
  `CP_BASE_URL`, set per environment in `ansible/group_vars/<service>_values.yaml.j2` to the stack's
  internal Istio host (`http://{stack}-internal.ingress01.{env}.nl.cjscp.org.uk`; `lv` for prp, prd
  and prx; STE and MDV overrides). The VirtualServices route `/progression-query-api/...` by path.
  This is the validator's pattern.
- **No default.** `http://localhost:8080` is Spring Boot's default port (the compose file sets 8082,
  but a deployed pod may not). A missing value in an environment could send the call to the store
  itself, get a 404 and fail every share with an application; or reach nothing at all. Failing at
  start is clearer. `localhost:8080` reaches progression only
  from WildFly pods (Istio `egress-localhost-bind` sidecar listener), which is how results' generated
  client does it, so it is wrong for this service.
- **System user.** The store's own user, from Key Vault through the chart's `secretProvider` block,
  member of "System Users" (progression's access rule for this query admits that group,
  `query-access-control.drl:394-401`). Secrets are never defaulted (constitution, *Secrets*). The env
  var is `RESULTS_STORE_SYSTEM_USER_ID` (the critic found two spellings in the design; this one is
  chosen, matching the Key Vault secret's name).
- **Deployment.** The store has no values in `cpp-aks-deploy` yet; adding
  `resultsstore-service_values.yaml.j2` per environment with `CP_BASE_URL`, the Artemis and database
  bindings and the system-user secret is a separate task, and blocks STE, not 002.

---

## R21. Metrics: lookup meters fire at call time

**Decision.** New meters (contracts/metrics.md):

- `resultsstore.intake.failed` gains `stage=enrich` and six causes `progression_*`;
- counter `resultsstore.enrichment.applications{outcome}`: `enriched`, `not_found`, `not_finalised`,
  `no_results`, `invalid_id`;
- timer `resultsstore.enrichment.lookup{outcome}`: `enriched`, `not_found`, `not_finalised`,
  `no_results`, `failed` (no `invalid_id`: no call is made);
- counter `resultsstore.enrichment.skipped{reason}`: `disabled`, `already_stored`,
  `unstorable_results`;
- counter `resultsstore.enrichment.applied`: shares stored with the flag true, after the commit.

The applications counter and the timer describe the HTTP call, not a transaction, so they fire when
the call ends: the stated exception to 001's "after commit" rule. They count per attempt: a
redelivered share is looked up and counted again. All tag values come from enums with `tag()` and are
pre-registered.

**Rationale.** FR-027 to FR-032; Principle VIII (every failure path moves a counter); the
*Progression lookups failing* alert reads `intake.failed{stage=enrich}`.

---

## R22. JSONAssert is already on the test classpath

**Decision.** No dependency change. The parity test uses `org.skyscreamer.jsonassert.JSONAssert`
with `JSONCompareMode.STRICT`.

**Evidence.** `flock -w 7200 /tmp/resultsstore-gradle.lock ./gradlew dependencies --configuration
testRuntimeClasspath` (JAVA_HOME `/usr/lib/jvm/java-25-openjdk`, 2026-10-03) shows
`org.skyscreamer:jsonassert:1.5.3` (with `com.vaadin.external.google:android-json`), brought in by
`org.springframework.boot:spring-boot-starter-test:4.1.1` through
`spring-boot-starter-webmvc-test`. Results uses the same library.

---

## R23. WireMock for the client tests, the ITs and compose

**Decision.**

- Unit and contract tests: an in-process `WireMockServer` on a dynamic port
  (`org.wiremock:wiremock-standalone:3.13.2`, already in `build.gradle`), as `AuthzIT` does; static
  server fields declared first.
- `IntakeIT`: a static `support/ProgressionStub` started before the context, with
  `@DynamicPropertySource` setting the base URL, a synthetic system user id and
  `resultsstore.enrichment.enabled=true`. Each test uses its own application ids, and stubs and
  `verify()` match the per-id URL, never global counts or `resetAll()`, because a late redelivery
  from an earlier test can still reach the shared server. Scenarios (503 then 200) are scoped to one
  id. The dead-letter case stays fast: the test profile has no pause and the embedded broker's
  max-delivery setting is low.
- Compose: `docker/wiremock/mappings/progression-application.json` on the existing `wiremock`
  service, which `CP_BASE_URL` already points at (`http://wiremock:8080`). The smoke check counts
  requests with `POST /__admin/requests/count` filtered by the progression path, so the usersgroups
  stub's calls are not counted.

---

## R24. PMD and coverage traps

**Decision.**

- `OnlyOneReturn`: one result variable per method, notably the status classifier and the outcome
  mapping.
- `AvoidCatchingGenericException`: the client catches `IOException`, `JacksonException` and
  `RestClientException` by name. The only generic catch stays in `IntakeService.counted`, already
  suppressed with its reason.
- `FieldDeclarationsShouldBeAtStartOfClass`: constants (path, media type, header name, the three
  field names) and fields before constructors, in nested records and in tests' static servers too.
- `AvoidDuplicateLiterals` in tests: fixture paths and ids as constants.
- Coverage: `config/**` is excluded from JaCoCo, so the client, the enricher and the classification
  live in `adapter/` and `application/`, where the gate measures them; `ProgressionConfig` only wires.
- No migration: V1 to V4 are never edited (Flyway checksums).

---

## R25. Principle II rewording (2.0.0 → 2.1.0)

**Decision.** Reworded in T010 under the amendment procedure, MINOR bump (FR-035). Proposed text:

> The store keeps each message exactly as it arrived, byte for byte, header included, in
> `payload_text`; the payload checksum is SHA-256 over that text. Beside it the store keeps one
> working copy, `payload_json`: that text parsed, with the finalised application results from
> progression set into `courtApplications[].judicialResults` at intake, each result without
> `amendmentDate`, `amendmentReason` and `amendmentReasonId`. Nothing else in the content is added,
> removed or changed. The working copy is held as jsonb, which keeps content but not key order,
> spacing or duplicate keys. Every indexed column is read from the working copy (from the text when
> the working copy is empty), and can be rebuilt from it if the extraction rules change.

**Rationale.** The 2.0.0 text ("exactly as it was received, plus the finalised application results …
Nothing else is added, removed or reformatted") cannot be literally true of a jsonb copy, and does
not say which column holds what. The new text adds the working-copy clause and names the field
removal; no rule is reversed, so MINOR. The page owner gets the matching forward notes (FR-037).
