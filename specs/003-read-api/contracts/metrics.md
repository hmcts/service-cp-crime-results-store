# Contract: metrics (003 delta)

Adds to [../../001-share-intake/contracts/metrics.md](../../001-share-intake/contracts/metrics.md) and
[../../002-enrichment/contracts/metrics.md](../../002-enrichment/contracts/metrics.md). Everything there
still holds unless this file says otherwise. At the end of 003 (T012) the 001 contract's "Not in 001"
line points here, and this file stays as the record of the change.

## Rules

- Every tag value is the lower-case name of an enum constant held in `domain/` (`ReadEndpoint`,
  `ReadOutcome`, `RouteRefusal`), from the fixed lists below. No share id, hearing id, court id, date,
  path or caller value, ever (Principle XI).
- Every meter and tag combination is registered when its observer is built, so a dashboard sees zero
  rather than nothing. `MicrometerRefusalObserver` (T003) registers `resultsstore.read.refused`;
  `MicrometerReadObserver` (T005) the other read meters; `MicrometerIntakeObserver` (T008) the overrun
  counter.
- **Read meters describe a request, not a transaction.** They fire once the answer is decided, after the
  query. The overrun counter fires after the store transaction's commit has returned.
- `outcome=not_modified` exists for `payload` and `arrived_payload` only; every other
  (endpoint, outcome) pair is registered.

## Counters

| Name | Tags (allowed values) | Moves when | FR |
|---|---|---|---|
| `resultsstore.read.requests` | `endpoint` = `pull` \| `search` \| `share` \| `payload` \| `day_versions` \| `arrived_payload` (only if D-RAW); `outcome` = `ok` \| `not_modified` \| `bad_request` \| `not_found` \| `unavailable` \| `failed` | once per request that reached a controller, when the answer is decided. `bad_request` = any `400`; `not_found` = `share_not_found` or `hearing_day_not_found`; `unavailable` = `503 store_unavailable`; `failed` = `500 internal_error` | FR-055 |
| `resultsstore.read.refused` | `reason` = `route_not_found` \| `method_not_allowed` \| `unsupported_content_type` | this service's filters refuse a request before authorisation or audit. These requests are not audited, so this counter is their only record | FR-051, FR-055 |
| `resultsstore.intake.visibility.overrun` | — | after a store transaction's commit returns, when its time from sending the share insert to the commit returning was at or above the pull visibility lag. Evidence that the pull-safety assumption was broken: **alert on any increase**. Not counted: a duplicate or a refused copy (nothing visible was inserted), and a commit the client never sees return (D-OVERRUN, pending Sachin) | FR-020 |

## Timers

| Name | Tags | Records | FR |
|---|---|---|---|
| `resultsstore.read.duration` | `endpoint` (as above) | from controller entry to the answer decided, including the query. Not the filters, not the audit, not writing the body | FR-055 |

## Distribution summaries (no tags)

| Name | Records | FR |
|---|---|---|
| `resultsstore.read.page.items` | items returned per pull or search page; shows consumers' backlog and use of `limit` | FR-055 |
| `resultsstore.read.payload.bytes` | bytes served per `200` from `/payload` (and `/payload/arrived`); sizes the bandwidth and the audit exposure | FR-055 |

## Not counted by 003

- `401` and `403`: the authorisation library refuses them before any of this service's code. Its own
  logs and the gateway's are their record.
- Reconciliation findings, dead letters and subscription health: spec 004 and Azure Monitor.

## Alert input

- `resultsstore.intake.visibility.overrun` increasing at all: page the owner; consumers may have missed a
  share; run consumer reconciliation (contracts/read-api.md §5.6).
- `resultsstore.read.requests{outcome=unavailable}` rising: the database is failing reads.
- `resultsstore.read.refused` rising: a consumer is calling a wrong path or method.

The alert rules themselves are an Azure Monitor task outside this repository.

## Checks

`MicrometerReadObserverTest` and `MicrometerRefusalObserverTest` assert every name and tag set against a
`SimpleMeterRegistry`, the tag values against the enums, and that no tag value parses as a UUID or a
date. `MicrometerIntakeObserverTest` gains the overrun counter in its full registered set.
