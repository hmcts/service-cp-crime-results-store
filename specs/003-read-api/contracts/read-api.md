# Contract: Results Store read API, version 1

**For**: teams that read results from the store (YOT results distribution, probation results
distribution, court register, support staff). This is the document to review for gate G2.
**Owner**: the Results Store (`service-cp-crime-results-store`).
**Status**: Draft with spec 003. Points marked *pending Sachin* carry the default shown and may change
before release; spec.md *Decisions pending Sachin* lists them.
**Machine-readable form**: `src/main/resources/results-store-openapi.yaml` in the store's repository
(written by spec 003, task T001). Where the two disagree, this document is corrected.

The words MUST, MUST NOT, SHOULD and MAY are used in their usual sense. "The store" means the service;
"you" means the consumer.

---

## 1. Overview

| # | Method and path | Purpose | Action |
|---|---|---|---|
| 1 | `GET /results-store/v1/shares?storedAfterSeq=…` | **Pull**: shares stored after a sequence number, in stored order, key details only. The feed | `results-store.pull-shares` |
| 2 | `GET /results-store/v1/shares?courtCentreId=…&sharedDayFrom=…&sharedDayTo=…` | **Search**: shares of one court over London days. A query, not a feed | `results-store.search-shares` |
| 3 | `GET /results-store/v1/shares/{shareId}` | One share's current key details, chain and youth facts | `results-store.get-share` |
| 4 | `GET /results-store/v1/shares/{shareId}/payload` | The payload the store holds for one share, with an `ETag` | `results-store.get-share-payload` |
| 5 | `GET /results-store/v1/hearings/{hearingId}/days/{hearingDay}/shares` | Every version of one hearing day, in `sharedTime` order | `results-store.list-hearing-day-shares` |
| 6 | `GET /results-store/v1/shares/{shareId}/payload/arrived` | The text exactly as hearing sent it. **Only if D-RAW is accepted** (pending Sachin) | `results-store.get-share-arrived-payload` |

Every route is `GET` only. Nothing else is served under `/results-store/v1`.

**Words used here.**

- A **share** is one version of one hearing day: `hearingId` + `hearingDay` + `sharedTime`. Each share
  is a full snapshot of the day's results at that time.
- **`storedSeq`** is the order in which the store received shares. It has gaps; the gaps mean nothing.
- **`sharedTime`** orders versions of a day. **Latest** is the share with the greatest `sharedTime`,
  never the last to arrive.

---

## 2. Calling the API

### 2.1 Identity and authorisation

- Send `CJSCPPUID: <your user id>` on every request. No header → `401 unauthenticated`.
- Your user must be in **"System Users"** or **"Second Line Support"**. Otherwise → `403 forbidden`.
  Every action has its own allow rule naming these two groups; there is no youth scoping.
- The store works out the action from the method and path. A `CPP-ACTION` header you send is ignored,
  and so is any vendor media type (`application/vnd.…`) in `Content-Type` or `Accept`. You cannot pick
  your own action.
- In deployed environments the API gateway sets `CJSCPPUID`; only the gateway reaches the service.

### 2.2 Requests

- `Accept`: `application/json`, `*/*`, or absent. A vendor media type is treated as `application/json`.
  Anything else the store cannot produce → `406 not_acceptable`.
- No request has a body. `multipart/*` → `415 unsupported_content_type`.
- Query parameter names are **case-sensitive**. An unknown name → `400 unknown_parameter` (so a typo
  such as `storedAfterseq` never quietly turns a pull into a search). A name given twice →
  `400 repeated_parameter`.
- Ids are canonical UUIDs (`8-4-4-4-12`, hex). Dates are `yyyy-MM-dd`.
- A path the store does not serve → `404 route_not_found`, before authentication. A served path with
  another method, `HEAD` and `OPTIONS` included → `405 method_not_allowed` with an `Allow` header.

### 2.3 Responses

- `Content-Type: application/json` for every `200`.
- **Every field is always present.** A missing value is written as `null`, never left out.
- **Times** are ISO-8601 UTC instants with exactly six fraction digits, for example
  `2026-10-03T09:15:00.120000Z`. You may compare them as strings.
- **Dates** are `yyyy-MM-dd`.
- `storedSeq` is a JSON integer that fits a signed 64-bit integer.
- New fields may be added to `/v1` responses without notice. Ignore fields you do not know. Removing
  or changing the meaning of a field needs `/v2`.

### 2.4 Audit

Every request that reaches an endpoint is audited by the estate's audit library, with your user id and
the action. A request refused by the store's filters (`404 route_not_found` and `405` before
authorisation, `415` after it) or by authorisation (`401`, `403`) never reaches the audit filter: it is
counted, not audited (pending Sachin). The payload endpoints' audit record holds a
fixed marker in place of the payload body (D-AUDIT, pending Sachin; the default).

---

## 3. The share item

Every list and `GET /shares/{shareId}` return items of this shape. No item ever holds the payload.

```json
{
  "shareId": "6f1c2a3b-0d4e-5f60-8a7b-9c0d1e2f3a4b",
  "hearingId": "1a2b3c4d-0000-4000-8000-000000000001",
  "hearingDay": "2026-10-02",
  "sharedTime": "2026-10-02T16:41:07.512000Z",
  "storedSeq": 48213,
  "storedAt": "2026-10-02T16:41:08.003117Z",
  "sharedDayLondon": "2026-10-02",
  "sharedDayUtc": "2026-10-02",
  "keyDetails": {
    "courtCentreId": "2b3c4d5e-0000-4000-8000-000000000002",
    "courtRoomId": "3c4d5e6f-0000-4000-8000-000000000003",
    "ljaCode": "2577",
    "jurisdictionType": "MAGISTRATES",
    "isSjp": false,
    "isGroupProceedings": false,
    "youthCourtId": null,
    "isReshare": false
  },
  "anySubjectIsYouth": true,
  "dayYouthSeen": true,
  "isLatest": true,
  "predecessorShareId": "5e6f7a8b-0000-4000-8000-000000000005",
  "arrivedOutOfOrder": false,
  "enrichmentApplied": false,
  "projectionStatus": "OK",
  "projectionVersion": 1,
  "projectedAt": "2026-10-02T16:41:08.004002Z",
  "versionNumber": 2
}
```

(Ids and values are made up.)

| Field | Type | Meaning | Null when |
|---|---|---|---|
| `shareId` | UUID | The share's key. **Use it as your idempotency key.** Take it from the store; never derive it | never |
| `hearingId` | UUID | The hearing | never |
| `hearingDay` | date | The hearing day this share is a version of | never |
| `sharedTime` | instant | When the share was shared, as stored (cut to the microsecond) | never |
| `storedSeq` | integer | Order of receipt by the store; the pull cursor | never |
| `storedAt` | instant | When the store stored it | never |
| `sharedDayLondon` | date | The London calendar day of `sharedTime` (the register day) | never |
| `sharedDayUtc` | date | The UTC calendar day of `sharedTime`. Differs from the London day between 00:00 and 01:00 BST | never |
| `keyDetails` | object | Facts read from the payload, exactly as it states them | the whole object is `null` exactly when `projectionStatus` is `FAILED` |
| `keyDetails.courtCentreId` | UUID | Court centre | the payload did not state it |
| `keyDetails.courtRoomId` | UUID | Courtroom | not stated |
| `keyDetails.ljaCode` | string | Local justice area code | not stated |
| `keyDetails.jurisdictionType` | string | As stated (no fixed list) | not stated |
| `keyDetails.isSjp` | boolean | `true` = an SJP share, `false` = INT. **There is no `eventType` field; this is its source** | not stated |
| `keyDetails.isGroupProceedings` | boolean | Group proceedings | not stated |
| `keyDetails.youthCourtId` | UUID | Youth court | not stated |
| `keyDetails.isReshare` | boolean | The share is a re-share | not stated |
| `anySubjectIsYouth` | boolean | Any defendant in this share was flagged `isYouth` | unknown (including while `FAILED`) |
| `dayYouthSeen` | boolean | The day's youth flag: `true` if any share of the day is `true`; else `null` if any is unknown; else `false` | unknown for the day |
| `isLatest` | boolean | This share is the day's latest (greatest `sharedTime`) | never |
| `predecessorShareId` | UUID | The share of the same day with the next earlier `sharedTime` | the first share of the day |
| `arrivedOutOfOrder` | boolean | The share arrived after a share of the same day with a later `sharedTime` | never |
| `enrichmentApplied` | boolean | Finalised application results from progression were added at intake | never |
| `projectionStatus` | `"OK"` \| `"FAILED"` | Whether the key details could be read from the payload. `FAILED` rows are retried by the store's sweep | never |
| `projectionVersion` | integer | The extractor version that last wrote the key details | never |
| `projectedAt` | instant | When the key details were last written | never |
| `versionNumber` | integer | 1-based position of the share in its day by `sharedTime`, **worked out when you read it** | never |

**These values are read-time values and can change** after you first saw the share, with no new
`storedSeq`: `keyDetails`, `anySubjectIsYouth`, `dayYouthSeen`, `isLatest`, `predecessorShareId`,
`projectionStatus`, `projectionVersion`, `projectedAt`, `versionNumber`. Section 5 says what to do.
The others never change.

---

## 4. Endpoints

### 4.1 Pull: `GET /results-store/v1/shares?storedAfterSeq=…`

The presence of `storedAfterSeq` makes the call a pull.

| Parameter | Required | Values | Default | Bad value |
|---|---|---|---|---|
| `storedAfterSeq` | yes | integer ≥ 0; exclusive | — | `400 invalid_stored_after_seq` |
| `limit` | no | integer 1 to 500 | 100 | `400 limit_out_of_range` |
| `dayYouthSeen` | no | `notFalse` (day flag not `false`; unknown stays in), `true` (day flag `true`) | absent = every day | `400 invalid_day_youth_seen` (`false` is not accepted on pull) |
| `courtCentreId` | no | UUID. Selects that court's shares **and every `FAILED` share** (court unknown) (D-COURT-FAILED, pending Sachin) | absent = every court | `400 invalid_court_centre_id` |

`sharedDayFrom`, `sharedDayTo`, `latestOnly` or `cursor` with `storedAfterSeq` → `400 conflicting_parameters`.

**Response `200`:**

```json
{
  "items": [ <share item>, … ],
  "nextStoredAfterSeq": 48213,
  "hasMore": false,
  "visibleUpTo": "2026-10-03T18:00:04.000000Z"
}
```

| Field | Meaning |
|---|---|
| `items` | Matching shares with `storedSeq` > `storedAfterSeq`, in ascending `storedSeq`, at most `limit` |
| `hasMore` | `true` exactly when more matching shares are already visible. Call again at once |
| `nextStoredAfterSeq` | Your next cursor. When `hasMore` is `true`: the last item's `storedSeq`. When `false`: the highest `storedSeq` the store can vouch for, **whether or not it matched your filters**, or your own `storedAfterSeq` if that is higher. So your cursor moves over ranges your filters skip |
| `visibleUpTo` | The store's clock minus the visibility lag. **Every share stored at or before `visibleUpTo` with `storedSeq` ≤ `nextStoredAfterSeq` has been presented to you**, if it matched your filters when you read |

**Order and cursor rules.**

- Order is ascending `storedSeq`. `storedAfterSeq` is exclusive. Start from `0`.
- Store `nextStoredAfterSeq` after you have processed the page, and pass it back as `storedAfterSeq`.
- **Everything returned is final: no share with a lower `storedSeq` will appear later** (section 4.1.1).
- Keep polling while `hasMore` is `true`; then poll on your own schedule.
- Probation's bridge (`limit=200` every 30 s) and YOT's nightly run are within these limits.

**Waiting for a time barrier** (YOT's 18:00 run): pull until `hasMore` is `false` and `visibleUpTo` is at
or after the barrier. Then every share stored before the barrier has been presented.

#### 4.1.1 Why pull is safe, and the lag

A share is numbered inside the store's write transaction, which then writes more before it commits. So
number 101 can still be open while 102 has committed. If the store returned 102 and you moved past it,
you would never see 101.

The store therefore returns only shares numbered at or below the highest number stored more than the
**visibility lag** ago. Every store transaction ends within the lag (it is checked against the
store's own timeouts at start), so every share at or below that number is committed or gone for good.
The default lag is **110 seconds** (D-LAG-VALUE, pending Sachin). In practice a share reaches pull about
two minutes after it was stored. Search, one share and the day's versions apply no lag.

What this does not cover: a database crash or failover in the middle of a commit, a disk stall longer
than the lag, or the database clock stepping back. The store counts any write that took longer than the
lag (its operators are alerted). Your cover for these rare cases is reconciliation (section 5.6).

### 4.2 Search: `GET /results-store/v1/shares?courtCentreId=…&sharedDayFrom=…&sharedDayTo=…`

Without `storedAfterSeq` the call is a search.

| Parameter | Required | Values | Default | Bad value |
|---|---|---|---|---|
| `courtCentreId` | yes | UUID | — | missing: `400 missing_parameter`; bad: `400 invalid_court_centre_id` |
| `sharedDayFrom` | yes | date, London shared day, inclusive | — | missing: `400 missing_parameter`; bad: `400 invalid_shared_day` |
| `sharedDayTo` | yes | date, inclusive; not before `sharedDayFrom`; at most 31 days after it counting both ends | — | `400 invalid_shared_day`, `400 day_range_reversed`, `400 day_range_too_long` |
| `dayYouthSeen` | no | `notFalse`, `true`, `false` | absent = every day | `400 invalid_day_youth_seen` |
| `latestOnly` | no | `true`, `false` | `false` | `400 invalid_latest_only` |
| `limit` | no | 1 to 500 | 100 | `400 limit_out_of_range` |
| `cursor` | no | the `nextCursor` of the previous page | first page | `400 invalid_cursor` |

A call with neither `storedAfterSeq` nor all three required search parameters → `400 missing_parameter`.

**Response `200`:** `{ "items": [ <share item>, … ], "nextCursor": "djF8MjAyNi0xMC0wMnwxNzU5…" }`

- Order: `sharedDayLondon`, then `sharedTime`, then `shareId`, ascending.
- `nextCursor` is `null` on the last page. Otherwise pass it back unchanged with the same parameters.
- The cursor is opaque text of at most 128 characters. Do not build or change it; an altered one is
  refused. It marks a position, so it stays valid while new shares are stored.

**Search is a query, not a feed.** Shares stored while you page may or may not appear. `FAILED` shares
(court unknown) never appear. No visibility lag applies. Use pull when you need completeness.

### 4.3 One share: `GET /results-store/v1/shares/{shareId}`

- `200`: one share item (section 3), current values.
- `400 invalid_share_id`: not a canonical UUID. `404 share_not_found`: no such share.
- No visibility lag.

### 4.4 The payload: `GET /results-store/v1/shares/{shareId}/payload`

**Body.** The payload the store holds, as `application/json` (no charset parameter; UTF-8):

- the **working copy**: the message as hearing sent it, with any finalised court-application results
  from progression added at intake (without `amendmentDate`, `amendmentReason` and
  `amendmentReasonId`); held by the database as `jsonb`, so key order and spacing are the database's,
  not hearing's; or
- the **text exactly as it arrived**, when the database could not hold a working copy (rare: for
  example a `\u0000` in the text).

The body is the whole JSON envelope, **`_metadata` included**. The top level holds `hearing`,
`hearingDay`, `sharedTime` and `isReshare` beside `_metadata`. The sharer's user id, when the source had
one, is `_metadata.context.user`; if the key is absent there was none. There is no separate field or
header for it (D-S12, pending Sachin).

**Headers on `200`:**

| Header | Value |
|---|---|
| `ETag` | `"<64 lower-case hex>"`: strong, quoted, the SHA-256 of **exactly the bytes of this body** |
| `Results-Store-Share-Id` | the share id |
| `Results-Store-Hearing-Id` | the hearing id |
| `Results-Store-Hearing-Day` | the hearing day, `yyyy-MM-dd` |
| `Results-Store-Shared-Time` | `sharedTime`, six fraction digits, UTC |
| `Results-Store-Enrichment-Applied` | `true` or `false` |
| `Results-Store-Payload-Form` | `working-copy`, or `arrived-text` when the working copy is empty |
| `Cache-Control` | `no-store` |
| `Content-Length` | the body's byte count. No `Content-Encoding`; no chunked transfer |

**Checking the body.** Compute SHA-256 over the bytes you received and compare it with the `ETag`
without its quotes. The `ETag` is **not** the store's checksum of the arrived message (that checksum is
over a different text).

**Conditional fetch.** Send `If-None-Match` with an `ETag` you hold (alone, in a list, weak `W/"…"`, or
`*`). If it matches: `304 Not Modified`, the `ETag` header, no body. Only the `ETag` is promised on a
`304`. Otherwise `200` as above.

**Stability promise** (D-JSONB-PROMISE, pending Sachin). For a given share the body and its `ETag` stay
the same while the store's PostgreSQL major version is unchanged. A database upgrade (or a dump and
restore) may write the same content with other bytes, which changes the `ETag` but never the content.
Verify each response against its own `ETag`; across fetches separated by an upgrade, compare content,
not bytes.

**Errors:** `400 invalid_share_id`; `404 share_not_found`.

### 4.5 A day's versions: `GET /results-store/v1/hearings/{hearingId}/days/{hearingDay}/shares`

- `200`: `{ "items": [ <share item>, … ] }`, every share of that hearing day, ascending `sharedTime`;
  `versionNumber` runs 1, 2, 3 …; exactly one item has `isLatest` `true`. Not paged.
- `400 invalid_hearing_id`, `400 invalid_hearing_day`; `404 hearing_day_not_found` when the day has no
  share.
- No visibility lag.

### 4.6 The arrived text: `GET /results-store/v1/shares/{shareId}/payload/arrived` (only if D-RAW is accepted)

- Body: the message text exactly as hearing sent it, byte for byte, `application/json`.
- `ETag`: `"<the store's SHA-256 checksum of that text>"`, which is also the SHA-256 of the body bytes.
- Headers as section 4.4, with `Results-Store-Payload-Form: arrived-text`.
- Conditional fetch and errors as section 4.4. Its own action and allow rule.

---

## 5. What you can rely on, and what you must do

These rules are normative. They follow from the store keeping each share once and updating only a few
columns in place.

### 5.1 Filters are evaluated when you read

`dayYouthSeen` and `courtCentreId` are checked against the values **at the time of your call**. A share
already behind your cursor is **never presented again**, even if it would match now.

### 5.2 New versions come as new shares

A later share of the same day has a higher `storedSeq` and is presented when it is stored. Each share is
a full snapshot of its day. When you receive one, call `GET /hearings/{hearingId}/days/{hearingDay}/shares`
if you need to know which is the day's latest.

### 5.3 Which youth filter to use

`dayYouthSeen=notFalse` is **complete** for youth-relevant days: a day's flag moves from `false` to
`true` only through a new share, which you will be presented; days whose flag is unknown are included.
`dayYouthSeen=true` **can miss** a share whose day becomes `true` without a new share (the store's sweep
filling in a share it could not read at first). Use `notFalse`, or no filter, and filter yourself if you
need completeness.

### 5.4 The unknown-row obligation

**A share presented with `projectionStatus` `FAILED` or `dayYouthSeen` `null` is not final in its key
details. Re-read `GET /shares/{shareId}` until `projectionStatus` is `OK` (or the day's successor
arrives) before deciding it is not yours.**

This matters most with `courtCentreId`: a court-filtered pull also returns every `FAILED` share from any
court, because its court is not yet known (D-COURT-FAILED, pending Sachin).

### 5.5 Values that change in place

- `isLatest` can be `false` for a share that arrived out of order (`arrivedOutOfOrder` `true`), and a
  share that was latest stops being latest when a later version arrives. Read the day's versions before
  acting on "latest".
- `versionNumber` can go up when a share with an earlier `sharedTime` arrives late. **Your key is
  `shareId`, never `versionNumber`.**
- `keyDetails`, `anySubjectIsYouth` and `dayYouthSeen` can be rewritten in place, with no new
  `storedSeq`: by the store's sweep (a `FAILED` share read successfully later), and by a support-staff
  re-extraction (spec 004; an unknown day flag can become `true` or `false`). `projectionVersion` and
  `projectedAt` show when key details were last written.

### 5.6 Reconciling

Re-pulling from an older cursor is safe and returns the current values of every share in that range
(filters are evaluated at read time). Use it to reconcile your own records, for example nightly over the
previous day's range. Search cannot reconcile: it needs a court and never returns `FAILED` shares.

### 5.7 Idempotency

You may be presented the same share again when you re-pull or reconcile. Keep your own idempotency guard
on `shareId`.

---

## 6. Errors

Every `4xx` and `5xx` from the store has this body and nothing else:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "reason": "limit_out_of_range" }
```

`title` is the HTTP reason phrase. The body never holds your input, a path, an exception message or any
payload content. `Content-Type` is `application/problem+json`, except `401` and `403`, which come as
`application/json`. A `401` or `403` has this same JSON body whatever `Accept` you send, `text/html`
included: there is no HTML error page. Branch on `status` and `reason`.

| Status | `reason` | When | Retry? |
|---|---|---|---|
| 400 | `unknown_parameter` | a query parameter the endpoint does not take | no |
| 400 | `repeated_parameter` | a query parameter given more than once | no |
| 400 | `conflicting_parameters` | pull and search parameters together | no |
| 400 | `missing_parameter` | a required parameter is absent | no |
| 400 | `invalid_stored_after_seq` | not an integer ≥ 0 | no |
| 400 | `limit_out_of_range` | not an integer from 1 to 500 | no |
| 400 | `invalid_day_youth_seen` | not one of the allowed values | no |
| 400 | `invalid_court_centre_id` | not a canonical UUID | no |
| 400 | `invalid_shared_day` | `sharedDayFrom` or `sharedDayTo` not a date | no |
| 400 | `day_range_reversed` | `sharedDayFrom` after `sharedDayTo` | no |
| 400 | `day_range_too_long` | more than 31 days | no |
| 400 | `invalid_latest_only` | not `true` or `false` | no |
| 400 | `invalid_cursor` | not a cursor the store issued | no |
| 400 | `invalid_share_id` | not a canonical UUID | no |
| 400 | `invalid_hearing_id` | not a canonical UUID | no |
| 400 | `invalid_hearing_day` | not a date | no |
| 400 | `bad_request` | any other malformed request | no |
| 401 | `unauthenticated` | no `CJSCPPUID` | no |
| 403 | `forbidden` | your user is in neither admitted group | no |
| 404 | `route_not_found` | the path is not served | no |
| 404 | `share_not_found` | no such share | no ("not held") |
| 404 | `hearing_day_not_found` | the day has no share | no ("not held") |
| 405 | `method_not_allowed` | a served path with another method; see `Allow` | no |
| 406 | `not_acceptable` | the store cannot produce what `Accept` asks | no |
| 415 | `unsupported_content_type` | a `multipart/*` request | no |
| 500 | `internal_error` | anything else | yes, with back-off |
| 503 | `store_unavailable` | the database cannot be reached or a query timed out. Carries `Retry-After: 5` (seconds) | yes, after `Retry-After` |

---

## 7. For each consumer

| Need | Use |
|---|---|
| Every share, once, in order (feeds, bridges) | pull, cursor `nextStoredAfterSeq`, idempotency on `shareId` |
| Youth-relevant days only, complete | pull with `dayYouthSeen=notFalse` |
| Wait for everything stored before 18:00 | pull until `hasMore` is `false` and `visibleUpTo` ≥ 18:00 |
| INT or SJP | `keyDetails.isSjp` |
| The register day | `sharedDayLondon`; the UTC day for file names is `sharedDayUtc` |
| Is this the day's latest? | `GET /hearings/{hearingId}/days/{hearingDay}/shares` |
| The payload, checked | `/payload`, SHA-256 of the body equals the `ETag` |
| Whether application results were added | `Results-Store-Enrichment-Applied` header (also `enrichmentApplied` in the item) |
| The sharer's user id | `_metadata.context.user` in the payload body |
| The raw event text | `/payload/arrived`, only if D-RAW is accepted |
| A court's shares over some days | search |
