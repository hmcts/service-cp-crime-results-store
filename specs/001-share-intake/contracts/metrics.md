# Contract: metrics

Micrometer meters, exported to Azure Monitor through the OpenTelemetry starter and exposed at
`/actuator/prometheus` (research R16). Names below are the Micrometer names; the Prometheus form
replaces dots with underscores and adds `_total` to counters (and `_seconds` to timers).

Rules (FR-040, Principle XI):
- every tag value comes from the fixed list shown; no id, date, field path or free text, ever;
- a meter describing a transaction fires only after that transaction commits; a failure counter
  fires after the rollback;
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
| `resultsstore.intake.message.id.missing` | — | a message had no `JMSMessageID` | FR-005 |
| `resultsstore.intake.parsed.copy.skipped` | — | a payload's parsed copy was left empty (`\u0000` or unpaired surrogate) | FR-015 |
| `resultsstore.extraction.failed` | `stage` = `intake` \| `sweep`; `kind` = `missing` \| `wrong_type` \| `invalid_uuid` \| `unexpected` | key details could not be read (after the commit that records it) | FR-032 |
| `resultsstore.sweep.rows` | `outcome` = `fixed` \| `failed_again` \| `skipped` \| `error` | the sweep finishes one row (`skipped`: another pod changed it first; `error`: the row's transaction threw) | FR-037, FR-038 |

`cause` comes from the SQLSTATE: `55P03` → `lock_timeout`; `57014` → `statement_timeout`; any
other `DataAccessException` → `database`; anything else → `other`.

## Timers

| Name | Tags | Records | FR |
|---|---|---|---|
| `resultsstore.intake.lag` | `order` = `in_order` \| `out_of_order` | `stored_at − shared_at` of each stored share (negative clamped to zero) | FR-039 |

## Not in 001

Enrichment counters (spec 002); reconciliation findings, subscription health and dead-letter count
(read from the broker and Azure Monitor, specs 003/004).

## Checks (T013)

`MicrometerIntakeObserverTest` asserts every name and tag set against a `SimpleMeterRegistry`; an
assertion over the whole registry fails if any tag value is outside the lists above or matches a
UUID or date pattern (SC-010).
