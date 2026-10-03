# Contract: configuration (004 delta)

Adds to [../../001-share-intake/contracts/configuration.md](../../001-share-intake/contracts/configuration.md),
[../../002-enrichment/contracts/configuration.md](../../002-enrichment/contracts/configuration.md) and
[../../003-read-api/contracts/configuration.md](../../003-read-api/contracts/configuration.md). Same
conventions: a typed `@ConfigurationProperties` record in `config/`, Spring duration format, checked at
start; a bad value stops the service, proved by `ConfigurationValidationTest` and `OperationsConfigTest`.
Messages name properties, never values from the environment. The operations beans are built whatever
`resultsstore.publicevents.enabled` says.

## `resultsstore.operations.*` (`OperationsProperties`)

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.operations.statement-timeout` | `10s` | `RESULTSSTORE_OPERATIONS_STATEMENTTIMEOUT` | > 0; below the driver's socket timeout (30 s). The operations `JdbcTemplate`'s JDBC query timeout, as spec 003's read timeout |
| `resultsstore.operations.rerun.max-range` | `31d` | `RESULTSSTORE_OPERATIONS_RERUN_MAXRANGE` | 1 day to 92 days (D-RERUN-BOUNDS, pending Sachin) |
| `resultsstore.operations.rerun.max-hearing-ids` | `200` | `RESULTSSTORE_OPERATIONS_RERUN_MAXHEARINGIDS` | 1 to 1,000 |
| `resultsstore.operations.rerun.max-share-ids` | `1000` | `RESULTSSTORE_OPERATIONS_RERUN_MAXSHAREIDS` | 1 to 5,000 |
| `resultsstore.operations.rerun.max-matched` | `200000` | `RESULTSSTORE_OPERATIONS_RERUN_MAXMATCHED` | 1 to 1,000,000 |
| `resultsstore.operations.rerun.chunk-size` | `5000` | `RESULTSSTORE_OPERATIONS_RERUN_CHUNKSIZE` | 100 to 10,000 |
| `resultsstore.operations.rerun.request.transaction-timeout` | `120s` | `RESULTSSTORE_OPERATIONS_RERUN_REQUEST_TRANSACTIONTIMEOUT` | ≥ statement-timeout; ≤ 10 min |
| `resultsstore.operations.rerun.request.statement-timeout` | `20s` | `RESULTSSTORE_OPERATIONS_RERUN_REQUEST_STATEMENTTIMEOUT` | ≥ lock-timeout; below the socket timeout |
| `resultsstore.operations.rerun.request.lock-timeout` | `10s` | `RESULTSSTORE_OPERATIONS_RERUN_REQUEST_LOCKTIMEOUT` | > 0 |
| `resultsstore.operations.receipts.max-rows` | `200` | `RESULTSSTORE_OPERATIONS_RECEIPTS_MAXROWS` | 1 to 1,000 |
| `resultsstore.operations.reconciliation.received-give-up` | `1h` | `RESULTSSTORE_OPERATIONS_RECONCILIATION_RECEIVEDGIVEUP` | 1 min to 1 day. Set it from the broker's redelivery give-up time (D-R1-WINDOW, pending Sachin; platform team to confirm) |
| `resultsstore.operations.status.pod-recent` | `1d` | `RESULTSSTORE_OPERATIONS_STATUS_PODRECENT` | 10 min to 7 days. Also the age at which a pod's sweep row is deleted |

The rerun request transaction sets its own `lock_timeout`, `statement_timeout` and
`idle_in_transaction_session_timeout` (the last equal to the statement timeout) with
`set_config(..., TRUE)`, as the store transaction does, and is bounded by its transaction timeout.

`application.yaml`:

```yaml
resultsstore:
  # The operations API (specs/004-operations-api/contracts/configuration.md).
  operations:
    statement-timeout: ${RESULTSSTORE_OPERATIONS_STATEMENTTIMEOUT:10s}
    rerun:
      max-range: ${RESULTSSTORE_OPERATIONS_RERUN_MAXRANGE:31d}
      max-hearing-ids: ${RESULTSSTORE_OPERATIONS_RERUN_MAXHEARINGIDS:200}
      max-share-ids: ${RESULTSSTORE_OPERATIONS_RERUN_MAXSHAREIDS:1000}
      max-matched: ${RESULTSSTORE_OPERATIONS_RERUN_MAXMATCHED:200000}
      chunk-size: ${RESULTSSTORE_OPERATIONS_RERUN_CHUNKSIZE:5000}
      request:
        transaction-timeout: ${RESULTSSTORE_OPERATIONS_RERUN_REQUEST_TRANSACTIONTIMEOUT:120s}
        statement-timeout: ${RESULTSSTORE_OPERATIONS_RERUN_REQUEST_STATEMENTTIMEOUT:20s}
        lock-timeout: ${RESULTSSTORE_OPERATIONS_RERUN_REQUEST_LOCKTIMEOUT:10s}
    receipts:
      max-rows: ${RESULTSSTORE_OPERATIONS_RECEIPTS_MAXROWS:200}
    reconciliation:
      # Until the broker's redelivery give-up time is confirmed.
      received-give-up: ${RESULTSSTORE_OPERATIONS_RECONCILIATION_RECEIVEDGIVEUP:1h}
    status:
      pod-recent: ${RESULTSSTORE_OPERATIONS_STATUS_PODRECENT:1d}
```

## `resultsstore.sweep.*` additions (`SweepProperties`)

`SweepProperties` is registered whatever the subscription says (`IntakeConfig`'s
`@EnableConfigurationProperties`), so these are checked on every pod.

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.sweep.rerun-batch-size` | `200` | `RESULTSSTORE_SWEEP_RERUNBATCHSIZE` | 1 to 1,000 |
| `resultsstore.sweep.rerun-max-attempts` | `3` | `RESULTSSTORE_SWEEP_RERUNMAXATTEMPTS` | 1 to 10 (D-RERUN-CANCEL, pending Sachin) |
| `resultsstore.sweep.pod-name` | `${HOSTNAME:local}` | `HOSTNAME` (set by Kubernetes) | matches `^[a-z0-9][a-z0-9.-]{0,252}$`, the table's check |

```yaml
resultsstore:
  sweep:
    # Rerun items one round claims, after the FAILED rows.
    rerun-batch-size: ${RESULTSSTORE_SWEEP_RERUNBATCHSIZE:200}
    # Operational failures before an item is abandoned.
    rerun-max-attempts: ${RESULTSSTORE_SWEEP_RERUNMAXATTEMPTS:3}
    # This pod's row in sweep_round.
    pod-name: ${HOSTNAME:local}
```

## Where the rules run

- `OperationsProperties`' compact constructor checks each value alone (`Rules.within`, `Rules.atLeast`).
- `OperationsConfig` checks the rules that span settings: request lock ≤ statement ≤ transaction;
  operations and request statement timeouts below the socket timeout when one is set (the same rule as
  `IntakeConfig`'s). It builds the operations `JdbcTemplate` (query timeout), `JdbcOperationsQueries`,
  `JdbcRerunRequests` (its own `TransactionTemplate` with the request transaction timeout), the four
  services, `MicrometerOperationsObserver` and a `Clock` (`Clock.systemUTC()`).
- `SweepProperties`' compact constructor checks the three new values.
- `RerunService` takes spec 003's effective visibility lag (the `ReadApiConfig` bean) for the
  stored-range rule.

## `test` profile

No new property. `authz.http.enabled`, `audit.http.enabled` and `cp.audit.enabled` stay `false`;
`OperationsApiIT` and `AuditIT` switch them on for themselves, as spec 003's tests do.

## Compose (`docker-compose.yml`, app service; T010)

| Variable | Value | Why |
|---|---|---|
| `RESULTSSTORE_SWEEP_INITIALDELAY`, `RESULTSSTORE_SWEEP_FIXEDDELAY` | short values through `${VAR:-default}` (for example `5s`, `10s`) | so the smoke sees its rerun item done in seconds, not minutes |

`docker/wiremock/mappings/identity-second-line-support.json` (new) answers "Second Line Support" for
the synthetic operator id `22222222-2222-4222-8222-222222222222`, matched on `CJSCPPUID`, priority 1.
`identity-stub.json` keeps a lower priority, as spec 003 set it, so every other id is "System Users".

## Constants that are deliberately not settings

| Constant | Where | Value |
|---|---|---|
| routes and action names | `filters/ApiRoute` | spec FR-001, FR-042 |
| reason length | `domain/RerunReason` | 10 to 500 characters (the table's `CHECK`) |
| body size | `api/RerunBodyParser` | 64 KiB |
| requests and pods shown | `application/ExtractionStatusService` | 20 each |
| share ids and R1 message ids shown | `application/ExtractionStatusService`, `ReconciliationService` | 50 each |
| `messageId` | `api/OperationsParameters` | 1 to 256 printable ASCII, no space |
| `Retry-After` on `503` | spec 003's advice | 5 seconds |
