# Contract: metrics (004 delta)

Adds to [../../001-share-intake/contracts/metrics.md](../../001-share-intake/contracts/metrics.md),
[../../002-enrichment/contracts/metrics.md](../../002-enrichment/contracts/metrics.md) and
[../../003-read-api/contracts/metrics.md](../../003-read-api/contracts/metrics.md). Everything there still
holds unless this file says otherwise.

## Rules

- Prefix `resultsstore.`. Every tag value is the lower-case `tag()` of an enum constant held in
  `domain/` (`SelectorKind`, `OperationsEndpoint`, `RerunRowOutcome`) or a `ProblemReason` code, from
  the fixed lists below. No share id, hearing id, message id, date, operator id or reason text, ever
  (Principle XI).
- Every meter and tag combination is registered when its observer is built, so a dashboard sees zero
  rather than nothing. `MicrometerOperationsObserver` (T007) and `MicrometerSweepObserver` (T005, T006)
  are built **whatever `resultsstore.publicevents.enabled` and `resultsstore.sweep.enabled` say**: a pod
  whose sweep is off still shows the rerun meters at zero.
- No meter moves on a `401` or `403`: those end before any operations code runs. Spec 003's
  `resultsstore.read.refused{reason}` counts them, and every `404`, `405` and multipart `415` its filters
  refuse, for `/operations` paths as well as read paths. The name is spec 003's and is kept.

## Counters

| Name | Tags (allowed values) | Moves when | FR |
|---|---|---|---|
| `resultsstore.operations.rerun.requests` | `selector` = `stored_range` \| `hearing_ids` \| `share_ids`; `result` = `created` \| `repeat` | a rerun request is answered `202`: `created` when this call wrote it, `repeat` when an open request with the same selector answered | FR-013, FR-015, FR-049 |
| `resultsstore.operations.rerun.shares.queued` | `selector` (as above) | by `queued` when a request is `created` (not on a repeat) | FR-013, FR-049 |
| `resultsstore.operations.refused` | `endpoint` = `rerun` \| `status` \| `receipts` \| `reconciliation`; `reason` = one of the operations `4xx` reasons of contracts/operations-api.md §7 that the advice or a controller sends (`unknown_parameter`, `repeated_parameter`, `unreadable_body`, `body_too_large`, `unknown_field`, `selector_not_exactly_one`, `range_invalid`, `range_too_long`, `hearing_ids_out_of_range`, `share_ids_out_of_range`, `invalid_hearing_id`, `invalid_share_id`, `invalid_reason`, `selector_too_wide`, `conflicting_parameters`, `missing_parameter`, `invalid_hearing_day`, `invalid_message_id`, `invalid_date`, `date_in_future`, `bad_request`, `unauthenticated`, `not_acceptable`, `unsupported_content_type`) | the advice answers a `4xx` for an operations route (the endpoint from the matched route); `unauthenticated` here is the rerun controller's own check (FR-011), not the library's `401` | FR-049 |
| `resultsstore.sweep.rerun.rows` | `outcome` = `reextracted` \| `unchanged` \| `fixed` \| `failed_again` \| `kept` \| `youth_kept` \| `youth_raise_held` \| `newer_kept` \| `skipped` \| `error` \| `abandoned` \| `cancelled` | after each rerun item's transactions end. `error`: an operational failure, the item stays pending; `abandoned`: the failure that reached the attempt limit (counted instead of `error`). **Alert on any `abandoned` or `youth_raise_held`** | FR-018, FR-021, FR-025, FR-027 |
| `resultsstore.sweep.rerun.requests.finished` | — | by the number of requests a round end moves from `OPEN` to `DONE` | FR-026 |
| `resultsstore.sweep.round.record.failed` | — | a pod's sweep-round upsert fails; the round itself completes | FR-032 |

Existing meters, unchanged:

- `resultsstore.sweep.rows{outcome}` keeps counting the `FAILED` path only.
- `resultsstore.extraction.failed{stage=sweep,kind}` also moves for a rerun item whose `FAILED` share
  fails again. It does not move for `KEPT` (the share stays `OK`); that is
  `resultsstore.sweep.rerun.rows{outcome=kept}`.
- `resultsstore.sweep.rounds.failed` counts a round that throws before its rows, either path.

## Timers and gauges

None new. Boot's `http.server.requests` times each operations route by its template. A gauge of pending
items or of R1 would query the database on every scrape; the status endpoint and the nightly job
(deferred, D-NIGHTLY) cover them.

## Not counted by 004

- R1 and R2 findings as meters: they are worked out on demand (D-NIGHTLY). The alert "reconciliation
  does not run" waits for the nightly job.

## Alert input

- `resultsstore.sweep.rerun.rows{outcome=abandoned}` increasing: an item failed three times; read the
  status for its share id.
- `resultsstore.sweep.rerun.rows{outcome=youth_raise_held}` increasing: a share's youth subject would
  rise from `false` to `true`; it is held until a youth-raised feed exists. Tell the YOT team.
- `resultsstore.sweep.round.record.failed` increasing: the status cannot show this pod's sweep.
- `resultsstore.operations.refused` rising: a runbook or a person is calling the API wrongly.

The alert rules themselves are an Azure Monitor task outside this repository.

## Checks

`MicrometerOperationsObserverTest` and `MicrometerSweepObserverTest` assert every name and tag set
against a `SimpleMeterRegistry`, the tag values against the enums and `ProblemReason`, that every
combination exists at zero at start, and that no tag value parses as a UUID or a date. `SweepObserverConfigTest`
and `OperationsConfigTest` prove the observers exist with the subscription and the sweep off.
