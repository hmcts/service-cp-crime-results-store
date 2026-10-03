# Contract: progression lookup

The one outbound call the store makes: progression's application-only query, once per court
application that arrives without results (research R1, R11 to R15). The store is the client;
progression owns the endpoint (`cpp-context-progression`,
`progression-query-api.raml:528-578`).

## Request

```http
GET {base-url}/progression-query-api/query/api/rest/progression/applications/{applicationId}
Accept: application/vnd.progression.query.application-only+json
CJSCPPUID: {system-user-id}
```

| Part | Value | Source |
|---|---|---|
| `{base-url}` | the stack's internal mesh host, e.g. `http://{stack}-internal.ingress01.{env}.nl.cjscp.org.uk` | `resultsstore.progression.base-url` = `${CP_BASE_URL}`, no default |
| `{applicationId}` | `hearing.courtApplications[i].id`, accepted by `CanonicalUuid` first; sent as a URI template variable, never concatenated | the share |
| `Accept` | `application/vnd.progression.query.application-only+json` | constant; maps to action `progression.query.application-only` |
| `CJSCPPUID` | the store's own system user id, a member of "System Users" | `resultsstore.progression.system-user-id` = `${RESULTS_STORE_SYSTEM_USER_ID}` (Key Vault `RESULTS-STORE-SYSTEM-USER-ID`), no default |

No body, no query string, no other headers set by the store. Never another service's user, never the
sharing user. Progression's access rule for the action admits "System Users"
(`query-access-control.drl:394-401`).

## Transport

| Setting | Value |
|---|---|
| Client | Spring `RestClient` over `HttpComponentsClientHttpRequestFactory` (Apache HttpClient 5), subclassed as `NoRedirectRequestFactory`; no connection reuse |
| Connect timeout | `resultsstore.progression.connect-timeout`, default 5 s |
| Read timeout | `resultsstore.progression.read-timeout`, default 10 s, per socket read |
| Response deadline | the same read timeout, measured from creating the request to the end of the body: status line, headers and body. The factory cancels the request (closing its connection) when it passes, and the body is also read through `DeadlineInputStream`; a slow drip that keeps each read short still ends at the deadline as `progression_timeout` |
| Redirects | not followed (`disableRedirectHandling()`, `redirectsEnabled=false`); a 3xx is an answer, and `CJSCPPUID` is never sent to another location |
| Retries | none in the store and none in the transport (`disableAutomaticRetries()`; `HttpURLConnection` resends a `GET` after a failure before the status line, which is why it is not used); failures go back to the broker (Principle VI) |
| Calls per share | one per distinct application id (by UUID), one at a time, in array order |

## Response

Progression's RAML declares only `200`. Body on success:

```json
{"courtApplication": { "id": "…", "applicationStatus": "FINALISED", "judicialResults": [ { … } ], … }}
```

`courtApplication` follows core `courtApplication.json` (status enum `DRAFT`, `UN_ALLOCATED`, `LISTED`,
`IN_PROGRESS`, `FINALISED`, `EJECTED`; `judicialResults` `minItems: 1`). For an unknown id progression
answers `200 {}` (research R3, confirmed on the local stack).

The body is read with `FAIL_ON_TRAILING_TOKENS` and `USE_BIG_DECIMAL_FOR_FLOATS`, and with
`JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES` off (research R9).

## Status and body table

| Answer | Outcome | Tag |
|---|---|---|
| 200; object; `courtApplication` an object; `applicationStatus` the string `FINALISED`; `judicialResults` a non-empty array | results copied into the working copy | `applications{outcome=enriched}` |
| 200; `courtApplication` an object; status missing, not a string, or not `FINALISED` | left as it arrived | `applications{outcome=not_finalised}` |
| 200; `FINALISED`; `judicialResults` missing, `null` or `[]` | left as it arrived | `applications{outcome=no_results}` |
| 200 `{}`; or `courtApplication` missing or `null` | left as it arrived | `applications{outcome=not_found}` |
| 200 with a body that is empty, not JSON (HTML included), has trailing content, is not an object, is cut short; or `courtApplication` neither object nor `null`; or `judicialResults` neither array nor `null` | fail closed | `intake.failed{stage=enrich,cause=progression_malformed}` |
| 404 | fail closed | `cause=progression_rejected` |
| 3xx; 2xx other than 200; 400, 405, 406, 410, 415; any status not listed here | fail closed | `cause=progression_rejected` |
| 401, 403 | fail closed | `cause=progression_refused` |
| 408, 429, 500-599 | fail closed | `cause=progression_unavailable` |
| connection refused; unknown host; connection reset or any other I/O failure before the status line | fail closed | `cause=progression_unreachable` |
| connect or read `SocketTimeoutException`; response deadline passed | fail closed | `cause=progression_timeout` |

*Fail closed*: the share is not stored; `RetryableIntakeException(ENRICH, cause)` with no chained
cause; the listener pauses `min(2^n s, cap)` and rethrows; the message rolls back; the broker
redelivers, and dead-letters after its attempts; the receipt stays `RECEIVED`.

## What is copied

From an `enriched` answer only `courtApplication.judicialResults`. Each object element loses
`amendmentDate`, `amendmentReason` and `amendmentReasonId` (top level only); every other field and
the array's order are kept; a non-object element is copied unchanged. Nothing else in the answer is
used (not `applicationStatus`, not nested results).

## Logging

A lookup logs, at most: the message id, `hearingId`, `hearingDay`, `sharedTime`, `shareId` (from the
MDC), the application id, the status code, the outcome or cause, and the failure's class name. Never
the body or any part of it, never the system user id, never a Jackson message (research R14).

## Tests that hold this contract

`ProgressionApplicationClientTest` (in-process WireMock): the path, both headers, the id as a path
variable; one test per row of the table above, including `Fault.MALFORMED_RESPONSE_CHUNK`,
`Fault.CONNECTION_RESET_BY_PEER`, a fixed delay past the read timeout, a chunked dribble past the
deadline, a 302 with a `Location` (not followed: WireMock sees no request at the target), an HTML 200,
an empty 200, trailing tokens; exactly one request on a 503; decimals read exactly; captured logs
hold no body and no user id.
