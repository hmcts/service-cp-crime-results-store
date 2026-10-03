# Contract: configuration (003 delta)

Adds to [../../001-share-intake/contracts/configuration.md](../../001-share-intake/contracts/configuration.md)
and [../../002-enrichment/contracts/configuration.md](../../002-enrichment/contracts/configuration.md).
Same conventions: a typed `@ConfigurationProperties` record in `config/`, Spring duration format,
checked at start; a bad value stops the service, proved by `ConfigurationValidationTest` and
`ReadApiConfigTest`. The read beans are built whatever `resultsstore.publicevents.enabled` says.

## `resultsstore.read.*` (`ReadApiProperties`)

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.read.pull.visibility-lag` | none set: derived as `transaction-timeout` + 2 × `statement-timeout` + `idle-in-transaction-timeout` of `resultsstore.intake.store.*` (110 s at their defaults) | `RESULTSSTORE_READ_PULL_VISIBILITYLAG` | ≥ that sum; ≤ 10 min (D-LAG-VALUE, pending Sachin) |
| `resultsstore.read.statement-timeout` | `5s` | `RESULTSSTORE_READ_STATEMENTTIMEOUT` | > 0; below the driver's socket timeout (`spring.datasource.hikari.data-source-properties.socketTimeout`, 30 s) when one is set |

`application.yaml`:

```yaml
resultsstore:
  read:
    pull:
      # Unset: derived from resultsstore.intake.store.* (transaction + 2 x statement +
      # idle-in-transaction). Set it only to make it longer. Every pod must share the intake values.
      visibility-lag: ${RESULTSSTORE_READ_PULL_VISIBILITYLAG:}
    statement-timeout: ${RESULTSSTORE_READ_STATEMENTTIMEOUT:5s}
```

### Where the rules run

- `ReadApiProperties`' compact constructor checks what needs no other setting: the statement timeout is
  positive; a lag, when set, is positive.
- `ReadApiConfig` checks the rules that span settings, and builds the beans:
  - the effective lag is the set value, or the derived sum when unset;
  - effective lag < sum → `IllegalStateException` naming `resultsstore.read.pull.visibility-lag` and the
    sum's three properties (`Rules.atLeast(name, value, boundName, bound)`, a new four-argument
    overload beside the existing three-argument one);
  - effective lag > 10 min → `IllegalStateException`. When the lag is unset (so the derived sum is what
    is too long), the message names `resultsstore.intake.store.transaction-timeout`;
  - statement timeout ≥ the socket timeout → `IllegalStateException`, the same rule as
    `IntakeConfig`'s for the intake statement timeout.
- Messages name properties, never values from the environment.

### The lag is checked per pod: rollout order (D-READONLY-PODS, pending Sachin)

The lag is checked against **this pod's own** `resultsstore.intake.store.*`. The writers are the pods
with the subscription on. The check proves nothing if pods disagree. So:

1. **Every pod of every deployment that shares the database MUST have the same
   `resultsstore.intake.store.*` values.** A read-only pod must still be given the writers' values.
2. **Raising an intake timeout:** first raise `resultsstore.read.pull.visibility-lag` (or leave it unset
   and roll the new timeouts to every pod at once, read-only pods first), then raise the timeout.
3. **Lowering an intake timeout:** lower the timeout on every pod first; lower the lag after.
4. The runtime check is `resultsstore.intake.visibility.overrun` (contracts/metrics.md): any increase
   means a store transaction outlived the lag.

## `authz.http.enabled` must be on (D-AUTHZ-REQUIRED, pending Sachin)

`ApiWebConfig` stops the service at start when `authz.http.enabled` is not `true` and the `test`
profile is not active: `IllegalStateException` naming `authz.http.enabled`. The default in
`application.yaml` is already `${AUTHZ_HTTP_ENABLED:true}`.

## Other settings changed by 003

| Property | Value | Why |
|---|---|---|
| `server.error.whitelabel.enabled` | `false` | the white-label page would render a model other than the four bounded fields (research R13) |
| `spring.mvc.problemdetails.enabled` | stays `false` (not set) | Boot's handler would write `detail` and `instance` (research R13) |
| `server.compression.enabled` | stays `false` (not set) | a strong `ETag` is per representation (research R10) |

## `test` profile (`application-test.yaml`)

| Change | Why |
|---|---|
| header comment corrected: context tests now need the Testcontainers database (T003) | the read beans are unconditional |
| no new property | `authz.http.enabled`, `audit.http.enabled` and `cp.audit.enabled` stay `false`; `ReadApiIT` and `AuditIT` switch them on with `@DynamicPropertySource` |

## Compose (`docker-compose.yml`, app service; T012)

| Variable | Value | Why |
|---|---|---|
| `RESULTSSTORE_INTAKE_STORE_TRANSACTIONTIMEOUT`, `…_STATEMENTTIMEOUT`, `…_LOCKTIMEOUT`, `…_IDLEINTRANSACTIONTIMEOUT` | short values through `${VAR:-default}` (for example 6 s, 2 s, 1 s, 1 s) | so the smoke's lag is about 11 s, not 110 s |
| `RESULTSSTORE_READ_PULL_VISIBILITYLAG` | left unset | the smoke checks the derived default |

## Constants that are deliberately not settings

They are the consumer contract (contracts/read-api.md); a setting would let it drift per environment.

| Constant | Where | Value |
|---|---|---|
| base path | `filters/ApiRoute` | `/results-store/v1` |
| routes and action names | `filters/ApiRoute` | spec.md FR-001, FR-046 |
| default and maximum `limit` | `application/ShareReadService` | 100 and 500 (pull and search) |
| search span | `application/ShareReadService` | 31 days, both ends counted |
| cursor length | `domain/SearchCursor` | 128 characters |
| `Retry-After` on `503` | `api/ReadApiExceptionHandler` | 5 seconds |
| lag upper bound | `config/ReadApiConfig` | 10 minutes |
