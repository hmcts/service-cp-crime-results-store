# Contract: metrics

Micrometer meters, exported to Azure Monitor through the OpenTelemetry starter and exposed at
`/actuator/prometheus` (research R16). Names below are the Micrometer names; the Prometheus form
replaces dots with underscores and adds `_total` to counters (and `_seconds` to timers).

Rules (FR-040, Principle XI):
- every tag value comes from the fixed list shown; no id, date, field path or free text, ever;
- a meter describing a transaction fires only after that transaction commits; a failure counter
  fires after the rollback; `received` and `message.id.missing` describe the delivery, not a
  transaction, and fire before any work;
- every tag value is an enum constant's lower-case name, held in `domain/` so the mapping is
  covered by tests.

## Counters

| Name | Tags (allowed values) | Moves when | FR |
|---|---|---|---|
| `resultsstore.intake.received` | — | a message reaches the listener (every delivery, before any work) | FR-038 |
| `resultsstore.intake.stored` | `order` = `in_order` \| `out_of_order` | a share is stored (after commit) | FR-038 |
| `resultsstore.intake.not.share` | `status` = `unreadable` \| `no_identity`; `reason` = `not_text_message` \| `nul_character` \| `not_json` \| `not_object` \| `missing_hearing_id` \| `invalid_hearing_id` \| `missing_hearing_day` \| `invalid_hearing_day` \| `missing_shared_time` \| `invalid_shared_time` | a non-share is recorded (after the receipt commit) | FR-038 |
| `resultsstore.intake.duplicate` | — | a share already stored is dropped (after commit) | FR-038 |
| `resultsstore.intake.already.settled` | — | a redelivery finds its receipt in an end state and is acknowledged with no work | FR-004 |
| `resultsstore.intake.failed` | `stage` = `receipt` \| `store`; `cause` = `lock_timeout` \| `statement_timeout` \| `database` \| `other` | an intake attempt throws (after the rollback, before the pause) | FR-038 |
| `resultsstore.intake.message.id.missing` | — | a message had no `JMSMessageID` (counted for the delivery, before its receipt is written) | FR-005 |
| `resultsstore.intake.parsed.copy.skipped` | — | a payload's parsed copy was left empty (`\u0000` or unpaired surrogate) | FR-015 |
| `resultsstore.extraction.failed` | `stage` = `intake` \| `sweep`; `kind` = `missing` \| `wrong_type` \| `invalid_uuid` \| `unstorable_text` \| `unexpected` | key details could not be read (after the commit that records it): `intake` for a share stored `FAILED`; `sweep` only for a row the sweep counts `failed_again` (never for `skipped`, `error` or `cancelled`) | FR-032 |
| `resultsstore.sweep.rows` | `outcome` = `fixed` \| `failed_again` \| `skipped` \| `error` \| `cancelled` | the sweep finishes one row (`fixed`: re-read and set `OK`; `failed_again`: still unreadable, or the row's own failure (a missing payload row, JSON that does not read, the extractor throwing) recorded as an `UNEXPECTED` attempt; `skipped`: another pod changed it first; `error`: an operational failure, the database failing the payload read or the row's write (the store transaction) throwing, so its projection was left as it was, no attempt was spent and only the try (`projection_tried_at`) was recorded; `cancelled`: the sweep was stopping (`stop()` asked, or the thread interrupted), so no transaction was opened for the row, or a failure met while stopping was put down to the stop, with nothing written and no attempt spent). Every row is counted once, after its transactions end. Sweep failures never move `resultsstore.intake.failed` | FR-037, FR-038 |
| `resultsstore.sweep.rounds.failed` | — | a sweep round throws before its rows are worked (the candidate read), or with an `Error` (then thrown on: the schedule ends and its liveness contributor goes `DOWN`, quickstart §6); a row's own failures never reach it | FR-037 |

*Amended by spec 005*: `resultsstore.intake.parsed.copy.skipped` is withdrawn; every share has a working copy (specs/005-payload-simplification FR-005).

`cause` comes from the SQLSTATE: `55P03` → `lock_timeout`; `57014` → `statement_timeout`; any
other `DataAccessException` → `database`; anything else → `other`.

## Timers

| Name | Tags | Records | FR |
|---|---|---|---|
| `resultsstore.intake.lag` | `order` = `in_order` \| `out_of_order` | `stored_at − shared_at` of each stored share (negative clamped to zero) | FR-039 |

## Not in 001

Enrichment counters (spec 002); reconciliation findings, subscription health and dead-letter count
(read from the broker and Azure Monitor, specs 003/004).

*Amended by spec 003*: the read meters (`resultsstore.read.requests`, `.refused`, `.duration`,
`.page.items`, `.payload.bytes`) and intake's `resultsstore.intake.visibility.overrun` are in
[specs/003-read-api/contracts/metrics.md](../../003-read-api/contracts/metrics.md).

## Checks (T013)

`MicrometerIntakeObserverTest` asserts every name and tag set against a `SimpleMeterRegistry`; an
assertion over the whole registry fails if any tag value is outside the lists above or matches a
UUID or date pattern (SC-010).
