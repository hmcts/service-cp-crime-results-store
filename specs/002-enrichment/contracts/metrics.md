# Contract: metrics (002 delta)

Adds to [../../001-share-intake/contracts/metrics.md](../../001-share-intake/contracts/metrics.md).
Everything there still holds unless this file says otherwise. At the end of 002 (T010) the 001
contract is updated to match, and this file stays as the record of the change.

## Rules

- Every tag value comes from the fixed lists below, each an enum constant's lower-case name held in
  `domain/` and pre-registered, so a dashboard sees zero rather than nothing. No application id, share
  id, date or free text, ever (FR-032, Principle XI).
- **Exception to "after commit".** `resultsstore.enrichment.applications` and
  `resultsstore.enrichment.lookup` describe the HTTP call, not a transaction. They fire when the call
  ends (or, for `invalid_id`, when the scan skips the application), before any store transaction.
  They count per attempt: a redelivered share is looked up and counted again (FR-029).
- `resultsstore.enrichment.applied` fires after the store transaction commits, from the flag actually
  stored (`StoreResult.Stored.enrichmentApplied`), so the fallback never counts it (FR-030).
- `resultsstore.enrichment.skipped` fires when the decision is made: `disabled` and `already_stored`
  before the store transaction, `unstorable_results` when the store refuses the enriched copy
  (before the re-run).

## Changed counter

| Name | Tags (allowed values) | Moves when | FR |
|---|---|---|---|
| `resultsstore.intake.failed` | `stage` = `receipt` \| `store` \| **`enrich`**; `cause` = `lock_timeout` \| `statement_timeout` \| `database` \| `other` \| **`progression_rejected`** \| **`progression_refused`** \| **`progression_unavailable`** \| **`progression_unreachable`** \| **`progression_timeout`** \| **`progression_malformed`** | an intake attempt throws. `stage=enrich` for a failed lookup (cause from contracts/progression-lookup.md) or an unexpected failure in the enrichment step (`other`). A failed existence check counts `stage=store` with a database cause | FR-027 |

The `progression_*` causes appear only with `stage=enrich`, and `stage=enrich` pairs only with them and
`other` (`IntakeFailureCause.belongsTo`); every allowed pair is pre-registered.
`IntakeFailureCause.fromSqlState` never returns a `progression_*` cause.

The tag values are the lower-case names of `domain/IntakeStage`, `domain/IntakeFailureCause`,
`domain/ApplicationLookupOutcome` and `domain/EnrichmentSkip` (phase A). The `resultsstore.enrichment.*`
meters below are registered by `MicrometerIntakeObserver` in T006; the timer's `failed` outcome is not
a value of `ApplicationLookupOutcome`.

## New counters

| Name | Tags (allowed values) | Moves when | FR |
|---|---|---|---|
| `resultsstore.enrichment.applications` | `outcome` = `enriched` \| `not_found` \| `not_finalised` \| `no_results` \| `invalid_id` | once per distinct application id answered by progression, when the call ends; `invalid_id` once per application skipped by the scan (no call). Failures are not outcomes here; they move `intake.failed` | FR-028 |
| `resultsstore.enrichment.skipped` | `reason` = `disabled` \| `already_stored` \| `unstorable_results` | a share needing lookups made none because enrichment is off (`disabled`) or the share was already stored (`already_stored`); or the enriched copy could not be held and the arrived copy was stored instead (`unstorable_results`) | FR-005, FR-006, FR-031 |
| `resultsstore.enrichment.applied` | — | a share was stored with `enrichment_applied = true` (after commit) | FR-030 |

A share with no application needing a lookup moves none of these.

## New timer

| Name | Tags | Records | FR |
|---|---|---|---|
| `resultsstore.enrichment.lookup` | `outcome` = `enriched` \| `not_found` \| `not_finalised` \| `no_results` \| `failed` | the duration of each progression call, from sending the request to the end of classification, on a monotonic clock; `failed` for every call that ended in a `progression_*` cause | FR-029 |

`invalid_id` is not a timer outcome: no call is made.

## As built (T006, checked in T010)

- **Observer port.** `IntakeObserver.applicationLookedUp(ApplicationLookupOutcome)` moves
  `enrichment.applications`; `IntakeObserver.lookupTimed(Optional<ApplicationLookupOutcome>, Duration)`
  records `enrichment.lookup`. `IntakeService` calls `lookupTimed` once per progression call, when the
  call ends: with the answer's outcome when progression answered (then `applicationLookedUp` with the
  same outcome first), or with `Optional.empty()` when the call threw a `RetryableIntakeException`
  (a `progression_*` cause), which `MicrometerIntakeObserver` records under `outcome="failed"`. A failed
  call moves no `enrichment.applications` outcome. The duration is taken on the injected monotonic
  clock, from just before `ProgressionApplications.find` to the end of the enricher's classification.
- **`invalid_id` counting rule.** Counted once per application needing results whose id is missing or
  not a canonical UUID (`Scan.invalidIds()`), only when enrichment is on (the port is present), and
  before the existence check, so it is counted again on a redelivery and for a share already stored.
  With enrichment off nothing is counted for it; a share whose only applications have invalid ids makes
  no lookup and moves no `skipped` reason. It never reaches the timer: `MicrometerIntakeObserver`
  pre-registers every timer outcome except `invalid_id`, plus `failed`.

## Alert input

*Progression lookups failing* (design page, *Observability*) reads
`resultsstore.intake.failed{stage="enrich"}` by cause: `progression_unavailable`,
`progression_unreachable` and `progression_timeout` point at progression or the network;
`progression_refused` at the system user; `progression_rejected` and `progression_malformed` at the
route or the contract. The alert rules themselves are not part of 002.

## Checks

`MicrometerIntakeObserverTest`: every new name and tag set against a `SimpleMeterRegistry`; every tag
value pre-registered; the whole-registry check (no tag outside the lists, none matching a UUID or date
pattern) still passes. `IntakeServiceTest`: lookup meters fire per call; `enrichment.applied` only
after the store returns `Stored` with the flag true; the fallback counts `unstorable_results` and not
`applied`.
