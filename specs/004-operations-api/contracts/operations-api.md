# Contract: Results Store operations API

**For**: Second Line Support, and the people who build their dashboards and runbooks.
**Owner**: the Results Store (`service-cp-crime-results-store`).
**Status**: Draft with spec 004. Points marked *pending Sachin* carry the default shown and may change
before release; spec.md *Decisions pending Sachin* lists them.
**Machine-readable form**: the contract repository `hmcts/api-cp-crime-results-store`, the same jar as
the read API (`uk.gov.hmcts.cp:api-cp-crime-results-store`, tag `operations`, generated `OperationsApi`;
release `0.3.0`; D-OPS-CONTRACT, pending Sachin), mirrored in the service's
`src/main/resources/results-store-openapi.yaml` (spec 004, task T001). Where they disagree with this
document, this document is corrected.

The words MUST, MUST NOT, SHOULD and MAY are used in their usual sense. "The store" means the service;
"you" means the support caller.

---

## 1. Overview

| # | Method and path | Purpose | Action |
|---|---|---|---|
| 1 | `POST /operations/extraction/rerun` | Ask the store to read some shares' key details again | `results-store-operations.rerun-extraction` |
| 2 | `GET /operations/extraction/status` | Extraction health, rerun progress, each pod's last sweep round | `results-store-operations.get-extraction-status` |
| 3 | `GET /operations/receipts` | The receipts of one hearing day, or one receipt by message id | `results-store-operations.list-receipts` |
| 4 | `GET /operations/reconciliation/daily` | One London day's intake counts and findings | `results-store-operations.get-daily-reconciliation` |

Nothing else is served under `/operations`. There is no replay endpoint: a message on the broker's
dead-letter queue is moved back with the broker's tools, and a share already stored is dropped by its
unique key.

**Nothing here returns a payload, a message's text, a rerun reason or an operator's id.**

---

## 2. Calling the API

### 2.1 Identity and authorisation

- Send `CJSCPPUID: <your user id>` (a UUID) on every request. No header → `401 unauthenticated`.
- Your user must be in **"Second Line Support"**. Any other caller, "System Users" included →
  `403 forbidden`. Each action has its own allow rule naming that group only.
- The store works out the action from the method and path. A `CPP-ACTION` header, or a vendor media
  type (`application/vnd.…`) in `Content-Type` or `Accept`, changes nothing.
- In deployed environments the API gateway sets `CJSCPPUID`; only the gateway reaches the service.

### 2.2 Requests

- `Accept`: `application/json`, `*/*`, or absent. A vendor media type is treated as `application/json`.
  Anything else the store cannot produce → `406 not_acceptable`.
- Only the rerun takes a body: `Content-Type: application/json` (a vendor JSON type is treated as
  `application/json`). Another type → `415 unsupported_content_type`; `multipart/*` on any route →
  `415 unsupported_content_type`.
- Query parameter names are case-sensitive. An unknown name → `400 unknown_parameter`; a name given
  twice → `400 repeated_parameter`.
- Ids are canonical UUIDs (`8-4-4-4-12`, hex). Dates are `yyyy-MM-dd`.
- A path not listed in §1 under `/operations` → `404 route_not_found`, before authorisation. A listed
  path with another method, `HEAD` and `OPTIONS` included → `405 method_not_allowed` with `Allow`.

### 2.3 Responses

- `Content-Type: application/json` and `Cache-Control: no-store` on every `2xx`. No `ETag`, no
  `Location`.
- Every field is always present; a missing value is `null`.
- Times are ISO-8601 UTC instants with exactly six fraction digits (`2026-10-03T09:15:00.120000Z`).
  Durations are ISO-8601 (`PT1H`).
- New fields may be added without notice. Ignore fields you do not know.

### 2.4 Audit

Every request that reaches an endpoint is audited by the estate's audit library with your user id; the
response event holds the response body (ids, counts, times, bounded codes). Only the read API's payload
endpoints have their body replaced by a marker; nothing here returns a payload, so nothing here needs
it. A request refused before the endpoint (`401`, `403`, `404`, `405`, a multipart `415`, or a request
the HTTP connector rejects) is counted, not audited (constitution VII). Whether the audit record holds
the rerun's request body, and so its reason, has not been verified: the library's request event carries
the body, but no `POST` has been audited yet. Task T009 records the answer here. **Write no personal
data in a rerun reason.**

---

## 3. Re-run extraction: `POST /operations/extraction/rerun`

### 3.1 What it does

The store records a request and one item per matched share, and answers at once. It changes no share
at that moment. Each sweep round on each pod then claims up to 200 pending items, reads each share's
stored working copy again, and rewrites the share's key details in place. You follow progress with the
status (§4).

### 3.2 Body

```json
{
  "reason": "Extractor 2 reads courtRoomId from hearingDays; refresh September's shares",
  "storedFrom": "2026-09-01T00:00:00Z",
  "storedTo": "2026-10-01T00:00:00Z"
}
```

| Field | Type | Rule |
|---|---|---|
| `reason` | string | Required. Trimmed, 10 to 500 characters, no control characters. Stored; never returned, logged or counted. **No personal data** |
| `storedFrom` | string, RFC 3339 instant with an offset, at most six fraction digits | With `storedTo`, the stored-range selector: shares whose `storedAt` is ≥ `storedFrom` and < `storedTo` |
| `storedTo` | as `storedFrom` | After `storedFrom`; at or before the database's current time minus the read API's visibility lag (90 seconds at the defaults; longer if the deployment sets a longer lag). Why: a share stored inside the lag may still be committing, so a range that has not ended before it could miss one. Span ≤ 31 days (pending Sachin) |
| `hearingIds` | array of 1 to 200 UUIDs (pending Sachin) | The hearing-list selector: every share of every day of those hearings. Duplicates are removed |
| `shareIds` | array of 1 to 1,000 UUIDs (pending Sachin) | The share-list selector. Duplicates are removed. Ids the store does not hold are counted in `unknownShareIds`, not refused |

Exactly one selector: the stored range (both fields), `hearingIds`, or `shareIds`. No other field. At
most 64 KiB. No query parameters. The selector may match at most 200,000 shares (pending Sachin).

A share stored while the request is being written may or may not be included in a hearing or share
list. A stored range cannot gain a share, because it ended before the lag.

### 3.3 `202 Accepted`

```json
{
  "rerunId": "0b6f4a8e-6d1c-4c55-9a1e-3f2d7c8b9a10",
  "status": "OPEN",
  "selectorKind": "STORED_RANGE",
  "matched": 148210,
  "queued": 148190,
  "alreadyPending": 20,
  "unknownShareIds": 0,
  "repeat": false
}
```

(Ids and values are made up.)

| Field | Type | Meaning |
|---|---|---|
| `rerunId` | UUID | The request |
| `status` | `"OPEN"` \| `"DONE"` | `DONE` at once when `matched` is 0; otherwise `OPEN` until no item is pending |
| `selectorKind` | `"STORED_RANGE"` \| `"HEARING_IDS"` \| `"SHARE_IDS"` | Which selector you sent |
| `matched` | integer | Shares the selector matched |
| `queued` | integer | Items this request created: `matched` − `alreadyPending` |
| `alreadyPending` | integer | Matched shares already waiting under another open request; not queued again. When that request works them, they are not worked again for this one |
| `unknownShareIds` | integer | `SHARE_IDS` only: ids the store does not hold; else 0 |
| `repeat` | boolean | `true` when an open request with the same selector already existed; then every other field is that request's and nothing was written |

**Repeats.** The selector is compared in a canonical form (ids lower-case, sorted, duplicates removed;
instants in UTC). While a request with the same selector is `OPEN`, posting it again answers `202` with
that request and `repeat: true`; your new reason is not stored. Once it is `DONE`, the same selector
makes a new request. Posting the same selector twice at the same moment leaves one request.

### 3.4 What the sweep does with each item

| Outcome | When | The share |
|---|---|---|
| `REEXTRACTED` | the share is `OK` and the new reading differs | rewritten in place: key details, `anySubjectIsYouth`, `projectionVersion`, `projectedAt`; missing defendant rows added; the day's youth flag recomputed. Stays `OK`; same `storedSeq` |
| `UNCHANGED` | the share is `OK` and the new reading matches its key details, youth subject and defendant rows | not changed, except the version and time when its stored version was older |
| `FIXED` | the share was `FAILED` and now reads | as the sweep's normal retry |
| `FAILED_AGAIN` | the share was `FAILED` and still does not read | as the sweep's normal retry |
| `KEPT` | the share is `OK` and the new reading fails | not changed |
| `YOUTH_KEPT` | the share's youth subject is `true` and the new reading's is not | not changed |
| `YOUTH_RAISE_HELD` | the share's youth subject is `false` and the new reading's is `true` | not changed; listed in the status until a later release can tell consumers (pending Sachin) |
| `NEWER_KEPT` | the share was read by a newer extractor than the sweep's (a rolling deploy) | not changed |
| `ABANDONED` | the item failed for an operational reason 3 times (pending Sachin) | not changed; listed in the status |

A rerun never moves a share to `FAILED` and never lowers a youth subject that is `true`. It may set a
key detail to `null` when the new reading no longer finds it, and may move a youth subject from `false`
to unknown (pending Sachin). Consumers learn of any change only by re-reading the share: a rerun gives
no new `storedSeq` (spec 003 contract §5.5).

### 3.5 Errors

`400` with `unreadable_body`, `body_too_large`, `unknown_field`, `unknown_parameter`, `repeated_parameter`,
`selector_not_exactly_one`, `range_invalid`, `range_too_long`, `hearing_ids_out_of_range`,
`invalid_hearing_id`, `share_ids_out_of_range`, `invalid_share_id`, `invalid_reason`,
`selector_too_wide`; `401 unauthenticated` (also when `CJSCPPUID` is not a UUID); `403 forbidden`;
`405`; `415 unsupported_content_type`; `503`; `500`. Nothing is written on any error.

---

## 4. Extraction status: `GET /operations/extraction/status`

No parameters.

```json
{
  "computedAt": "2026-10-03T09:15:00.120000Z",
  "extractorVersion": 2,
  "maxAttempts": 3,
  "failed": { "retryable": 4, "exhausted": 1, "awaitingNewExtractor": 0 },
  "rerun": {
    "pendingItems": 1830,
    "openRequests": 1,
    "abandoned": { "count": 2, "shareIds": ["…", "…"], "truncated": false },
    "youthRaiseHeld": { "count": 0, "shareIds": [], "truncated": false }
  },
  "requests": {
    "items": [
      {
        "rerunId": "0b6f4a8e-6d1c-4c55-9a1e-3f2d7c8b9a10",
        "selectorKind": "STORED_RANGE",
        "status": "OPEN",
        "requestedAt": "2026-10-03T08:00:00.000000Z",
        "finishedAt": null,
        "matched": 148210,
        "queued": 148190,
        "alreadyPending": 20,
        "unknownShareIds": 0,
        "pending": 1830,
        "outcomes": {
          "reextracted": 140000, "unchanged": 6300, "fixed": 50, "failedAgain": 8, "kept": 0,
          "youthKept": 0, "youthRaiseHeld": 0, "newerKept": 0, "abandoned": 2
        }
      }
    ],
    "truncated": false
  },
  "sweep": {
    "lastFinishedAt": "2026-10-03T09:14:31.002000Z",
    "lastWorkedAt": "2026-10-03T09:14:31.002000Z",
    "pods": [
      {
        "pod": "service-cp-crime-results-store-7d9f8-abcde",
        "extractorVersion": 2,
        "startedAt": "2026-10-03T09:14:02.551000Z",
        "finishedAt": "2026-10-03T09:14:31.002000Z",
        "lastWorkedAt": "2026-10-03T09:14:31.002000Z",
        "rows": { "failed": 4, "rerun": 200, "fixed": 3, "failedAgain": 1, "error": 0, "cancelled": 0 }
      }
    ],
    "truncated": false
  }
}
```

| Field | Meaning |
|---|---|
| `computedAt` | when this answer was worked out |
| `extractorVersion`, `maxAttempts` | the answering pod's extractor version and retry limit. **The `failed` counts use them**; during a rolling deploy pods can differ: compare each pod's `extractorVersion` below |
| `failed.retryable` | `FAILED` shares the sweep will retry: read by an older extractor, or an unexpected failure with attempts left |
| `failed.exhausted` | `FAILED` shares with an unexpected failure and no attempts left |
| `failed.awaitingNewExtractor` | `FAILED` shares the current extractor cannot read (a payload fault it reports by path); retried when the extractor version rises, or by a rerun |
| `rerun.pendingItems`, `rerun.openRequests` | items waiting; requests not yet `DONE` |
| `rerun.abandoned`, `rerun.youthRaiseHeld` | all items ever ended `ABANDONED` or `YOUTH_RAISE_HELD`: `count`, the newest 50 `shareIds`, `truncated` when there are more |
| `requests.items` | the 20 most recent requests, newest first, any status: the fields of §3.3 (without `repeat`), `requestedAt`, `finishedAt` (null while `OPEN`), `pending`, and one count per stored outcome |
| `sweep.lastFinishedAt`, `sweep.lastWorkedAt` | across pods shown: the latest round end, and the latest round that worked any row; `null` when no pod is shown |
| `sweep.pods` | pods whose last round finished in the last day (pending Sachin), newest first, at most 20. `rows.failed` and `rows.rerun` are the rows each path worked; `fixed`, `failedAgain`, `error` count both paths; `cancelled` rows the pod's stop left unstarted |

The rerun reason and the operator id are never shown. A pod whose sweep is off writes no row: a stale or
missing `lastFinishedAt` means no sweep is running. Errors: `400 unknown_parameter`,
`400 repeated_parameter`, `401`, `403`, `405`, `503`, `500`.

---

## 5. Receipts: `GET /operations/receipts`

Exactly one form:

| Form | Parameters |
|---|---|
| One hearing day | `hearingId` (UUID) and `hearingDay` (`yyyy-MM-dd`) |
| One message | `messageId`: 1 to 256 printable ASCII characters, no space; matched exactly (for example `ID:abc-123` or `sha256:<hex>`) |

```json
{
  "receipts": [
    {
      "messageId": "ID:abc-123",
      "status": "STORED",
      "hearingId": "1a2b3c4d-0000-4000-8000-000000000001",
      "hearingDay": "2026-10-02",
      "sharedTime": "2026-10-02T16:41:07.512000Z",
      "attempts": 1,
      "deliveryCount": 1,
      "firstReceivedAt": "2026-10-02T16:41:07.900000Z",
      "lastReceivedAt": "2026-10-02T16:41:07.900000Z",
      "settledAt": "2026-10-02T16:41:08.010000Z",
      "reason": null,
      "shareId": "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b"
    }
  ],
  "truncated": false
}
```

| Field | Meaning | Null when |
|---|---|---|
| `messageId` | the broker's message id as received, or `sha256:<hex>` when the broker gave none | never |
| `status` | `RECEIVED`, `STORED`, `DUPLICATE`, `UNREADABLE` or `NO_IDENTITY` | never |
| `hearingId`, `hearingDay`, `sharedTime` | the identity the message carried | `UNREADABLE` or `NO_IDENTITY` without it |
| `attempts` | deliveries the store has seen | never |
| `deliveryCount` | the broker's delivery count last seen | the broker gave none |
| `firstReceivedAt`, `lastReceivedAt` | first and last delivery | never |
| `settledAt` | when it reached an end state | still `RECEIVED` |
| `reason` | a bounded code (at most 120 characters), never text from the message | no reason |
| `shareId` | the stored share (`STORED`) or the share already stored (`DUPLICATE`) | other statuses |

Ordered by `firstReceivedAt`, then `messageId`; at most 200 (pending Sachin), `truncated` true when more
exist. No match → `200` with an empty list. **The message text is never returned.** An `UNREADABLE` or
`NO_IDENTITY` receipt usually has no hearing identity: look it up by `messageId`.

Errors: `400 conflicting_parameters` (both forms), `400 missing_parameter` (neither, or half of the first),
`400 invalid_hearing_id`, `400 invalid_hearing_day`, `400 invalid_message_id`, `400 unknown_parameter`,
`400 repeated_parameter`, `401`, `403`, `405`, `503`, `500`.

---

## 6. Daily reconciliation: `GET /operations/reconciliation/daily?date=yyyy-MM-dd`

`date` is required: a London calendar day, not after today in London (pending Sachin). Today is allowed
and `partial`.

```json
{
  "date": "2026-10-02",
  "clock": "Europe/London",
  "from": "2026-10-01T23:00:00.000000Z",
  "to": "2026-10-02T23:00:00.000000Z",
  "partial": false,
  "computedAt": "2026-10-03T09:15:00.120000Z",
  "receipts": { "total": 4822, "stored": 4790, "duplicate": 29, "unreadable": 1, "noIdentity": 0, "stillReceived": 2 },
  "shares": { "stored": 4791, "outOfOrder": 12, "extractionFailed": 3, "failedExhausted": 1, "staleVersion": 0 },
  "findings": {
    "r1": { "giveUpAfter": "PT1H", "count": 1, "messageIds": ["ID:def-456"], "truncated": false },
    "r2": { "extractionFailed": 3, "staleVersion": 0, "sampled": false }
  }
}
```

| Field | Meaning |
|---|---|
| `from`, `to` | the window as instants: the date's London midnight to the next. 23 hours on the day clocks go forward, 25 on the day they go back |
| `partial` | `true` while `to` is after `computedAt`: the day is not over. Shares stored in the last 90 seconds (the read API's visibility lag at the defaults) may still be committing |
| `receipts` | messages **first received** in the window: `total` all of them, then by their **current** status (`stillReceived` is status `RECEIVED`) |
| `shares` | shares **stored** in the window: all; out of order; extraction `FAILED`; `FAILED` with no attempts left; read by an older extractor than the answering pod's |
| `findings.r1` | receipts first received in the window, still `RECEIVED`, whose **last delivery** is older than `giveUpAfter` (1 hour, pending Sachin, to be set from the broker's redelivery give-up time): the count, the oldest 50 message ids, `truncated` |
| `findings.r2` | counts only (pending Sachin): `extractionFailed` and `staleVersion` as in `shares`; `sampled` is always `false` |

`receipts.stored` and `shares.stored` can differ: a message received before midnight and stored after it
counts in the receipts of one day and the shares of the next. Nothing is stored by this call.

Errors: `400 missing_parameter`, `400 invalid_date`, `400 date_in_future`, `400 unknown_parameter`,
`400 repeated_parameter`, `401`, `403`, `405`, `503`, `500`.

---

## 7. Errors

Every `4xx` and `5xx` has spec 003's body and nothing else:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "reason": "selector_not_exactly_one" }
```

The body never holds your input, a path, an exception message or any payload or message content. Branch
on `status` and `reason`.

| Status | `reason` | When | Endpoints |
|---|---|---|---|
| 400 | `unknown_parameter` | a query parameter the endpoint does not take | all |
| 400 | `repeated_parameter` | a query parameter given twice | all |
| 400 | `unreadable_body` | the body is empty, not JSON, not an object, or a field has the wrong JSON type | rerun |
| 400 | `body_too_large` | the body is over 64 KiB | rerun |
| 400 | `unknown_field` | a body field other than the five | rerun |
| 400 | `selector_not_exactly_one` | no selector, or more than one. `storedFrom` or `storedTo` alone counts as the stored-range selector, and this check comes before `range_invalid`: `storedFrom` with `hearingIds` gives this reason | rerun |
| 400 | `range_invalid` | only one of `storedFrom`/`storedTo` and no other selector; not an RFC 3339 instant with an offset; more than six fraction digits; `storedFrom` not before `storedTo`; `storedTo` inside the visibility lag | rerun |
| 400 | `range_too_long` | the range is longer than 31 days | rerun |
| 400 | `hearing_ids_out_of_range` | `hearingIds` empty or over 200 | rerun |
| 400 | `share_ids_out_of_range` | `shareIds` empty or over 1,000 | rerun |
| 400 | `invalid_hearing_id` | an id that is not a canonical UUID | rerun, receipts |
| 400 | `invalid_share_id` | an id that is not a canonical UUID | rerun |
| 400 | `invalid_reason` | missing, under 10 or over 500 characters after trimming, or a control character | rerun |
| 400 | `selector_too_wide` | more than 200,000 shares matched | rerun |
| 400 | `conflicting_parameters` | both receipt forms | receipts |
| 400 | `missing_parameter` | a required parameter is absent | receipts, reconciliation |
| 400 | `invalid_hearing_day` | not a date | receipts |
| 400 | `invalid_message_id` | empty, over 256 characters, a space or a non-printable character | receipts |
| 400 | `invalid_date` | not a date | reconciliation |
| 400 | `date_in_future` | after today in London | reconciliation |
| 400 | `bad_request` | any other malformed request, including a request target the HTTP connector rejects (an encoded slash, a backslash) | all |
| 401 | `unauthenticated` | no `CJSCPPUID`, or (rerun) one that is not a UUID | all |
| 403 | `forbidden` | you are not in "Second Line Support" | all |
| 404 | `route_not_found` | the path is not served | — |
| 405 | `method_not_allowed` | a served path with another method; see `Allow` | all |
| 406 | `not_acceptable` | the store cannot produce what `Accept` asks | all |
| 415 | `unsupported_content_type` | a body that is not JSON; `multipart/*` | all |
| 500 | `internal_error` | anything else | all |
| 503 | `store_unavailable` | the database cannot be reached or a query timed out; carries `Retry-After: 5` | all |

---

## 8. Bounds at a glance

| Bound | Default | Setting |
|---|---|---|
| stored range span | 31 days | `resultsstore.operations.rerun.max-range` |
| `hearingIds` | 1 to 200 | `resultsstore.operations.rerun.max-hearing-ids` |
| `shareIds` | 1 to 1,000 | `resultsstore.operations.rerun.max-share-ids` |
| shares matched | 200,000 | `resultsstore.operations.rerun.max-matched` |
| reason | 10 to 500 characters | constant (the table's check) |
| body | 64 KiB | constant |
| items per sweep round | 200 | `resultsstore.sweep.rerun-batch-size` |
| operational attempts per item | 3 | `resultsstore.sweep.rerun-max-attempts` |
| receipts per answer | 200 | `resultsstore.operations.receipts.max-rows` |
| R1 give-up | 1 hour | `resultsstore.operations.reconciliation.received-give-up` |
| pods shown | last day, at most 20 | `resultsstore.operations.status.pod-recent`; 20 is a constant |
| requests shown | 20 | constant |
| ids shown per list | 50 | constant |
| `messageId` | 1 to 256 characters | constant |
