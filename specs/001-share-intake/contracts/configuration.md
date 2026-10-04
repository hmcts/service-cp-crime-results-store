# Contract: configuration

New typed settings (`@ConfigurationProperties`, records in `config/`). Every one has a default in
`application.yaml` and can be set by environment variable (Spring relaxed binding: upper case, dots
to underscores, dashes removed). They are checked when the service starts; a bad value stops the
service starting (FR-046), proved by `ConfigurationValidationTest`.

Durations use Spring's format (`10s`, `5m`).

## `resultsstore.intake.*` (`IntakeProperties`)

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.intake.redelivery-pause.enabled` | `true` | `RESULTSSTORE_INTAKE_REDELIVERYPAUSE_ENABLED` | boolean |
| `resultsstore.intake.redelivery-pause.cap` | `30s` | `RESULTSSTORE_INTAKE_REDELIVERYPAUSE_CAP` | 1 s to 5 min |
| `resultsstore.intake.receipt-timeout` | `10s` | `RESULTSSTORE_INTAKE_RECEIPTTIMEOUT` | 1 s to 60 s; the receipt transaction's Spring timeout |
| `resultsstore.intake.store.transaction-timeout` | `60s` | `RESULTSSTORE_INTAKE_STORE_TRANSACTIONTIMEOUT` | ≥ every value below |
| `resultsstore.intake.store.lock-timeout` | `10s` | `RESULTSSTORE_INTAKE_STORE_LOCKTIMEOUT` | > 0, ≤ `statement-timeout` |
| `resultsstore.intake.store.statement-timeout` | `20s` | `RESULTSSTORE_INTAKE_STORE_STATEMENTTIMEOUT` | > 0, below the JDBC `socketTimeout` when one is set (30 s in `application.yaml`) |
| `resultsstore.intake.store.idle-in-transaction-timeout` | `10s` | `RESULTSSTORE_INTAKE_STORE_IDLEINTRANSACTIONTIMEOUT` | > 0, ≤ `transaction-timeout` |

The three `store.*` timeouts are applied per transaction with `set_config(…, true)` (research
R2). They also bound each sweep row's transaction.

> *Amended by spec 003* ([configuration](../../003-read-api/contracts/configuration.md), FR-061,
> FR-062): the defaults are now `statement-timeout` **10s** (was 20s) and `lock-timeout` **5s** (was
> 10s), so the read API's 90-second visibility lag holds (transaction + 2 × statement +
> idle-in-transaction). Every pooled connection also starts with `SET statement_timeout` equal to
> `statement-timeout` (the pool backstop, `config/StatementTimeoutBackstop`); Flyway migrates on its own
> connection with the limit lifted. The table above keeps the 001 values as written.

## `resultsstore.sweep.*` (`SweepProperties`)

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.sweep.enabled` | `true` | `RESULTSSTORE_SWEEP_ENABLED` | boolean; the switch for the scheduled sweep (the scheduler bean is not created when false) |
| `resultsstore.sweep.initial-delay` | `1m` | `RESULTSSTORE_SWEEP_INITIALDELAY` | ≥ 0 |
| `resultsstore.sweep.fixed-delay` | `5m` | `RESULTSSTORE_SWEEP_FIXEDDELAY` | 10 s to 24 h |
| `resultsstore.sweep.batch-size` | `100` | `RESULTSSTORE_SWEEP_BATCHSIZE` | 1 to 1000 |
| `resultsstore.sweep.max-attempts` | `3` | `RESULTSSTORE_SWEEP_MAXATTEMPTS` | 1 to 10; counts the intake attempt |

## `test` profile (`application-test.yaml`)

| Property | Value | Why |
|---|---|---|
| `resultsstore.intake.redelivery-pause.enabled` | `false` | tests do not wait; the one test that proves the pause turns it on with a 1 s cap |
| `resultsstore.sweep.enabled` | `false` | sweep tests call the sweep directly |

## Not changed

`resultsstore.publicevents.*` (topic, subscription, selector, enabled) keep their names and
values (constitution IX). `spring.datasource.hikari.data-source-properties.socketTimeout` stays
30 s.

## Constants that are deliberately not settings

| Constant | Where | Why |
|---|---|---|
| share id namespace `3f6c2a4e-8d1b-4f0a-9c57-1e2b7d9a4c60` | `domain/ShareId` | changing it would give every share a new id |
| extractor version | `application/KeyDetailsExtractor.EXTRACTOR_VERSION` | moves with the code that reads the fields; raising it makes the sweep retry older `FAILED` rows |
| pause base (2^n seconds) | `adapter/publicevents/RedeliveryPause` | fixed by D2; only the cap is an environment's choice |
