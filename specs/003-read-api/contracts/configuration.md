# Contract: configuration (003 delta)

Adds to [../../001-share-intake/contracts/configuration.md](../../001-share-intake/contracts/configuration.md)
and [../../002-enrichment/contracts/configuration.md](../../002-enrichment/contracts/configuration.md).
Same conventions: a typed `@ConfigurationProperties` record in `config/`, Spring duration format,
checked at start; a bad value stops the service, proved by `ConfigurationValidationTest` and
`ReadApiConfigTest`. The read beans are built whatever `resultsstore.publicevents.enabled` says.

## The contract dependency (`apiSpec`; research R23)

Build settings, not runtime properties. Set in `gradle/libs.versions.toml` and `build.gradle` (T009).

| Item | Value | Where | Rule |
|---|---|---|---|
| Coordinates | `uk.gov.hmcts.cp:api-cp-crime-results-store` | `gradle/libs.versions.toml` `[libraries]`, alias `api-results-store`; `apiSpec libs.api.results.store` in `build.gradle` | the `apiSpec` configuration; `implementation.extendsFrom apiSpec` |
| Version while phase C is built | `rs-69080b1` (draft from the api repo's `team/rs`, commit `69080b1`, which already serves the payload as `byte[]`; it superseded the first draft `rs-2c5bc08`) | `gradle/libs.versions.toml` `[versions]` | an exact draft, never a range |
| Version released | after phase D (orchestrator ruling: the api repo's Release `v0.2.0` and the bump from `rs-69080b1` happen after phase D) | the same | strict `X.Y.Z`: `./gradlew validateApiSpecVersions` (`gradle/apispec-validation.gradle`) fails on anything else, and the `validate-api-spec-version` job runs it before `ci-release` in `.github/workflows/ci-released.yml` |
| Repository | Azure Artifacts `hmcts-lib`, `https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/maven/v1` | `gradle/repositories.gradle` (already there) | read anonymously; no credentials. Also published to GitHub Packages, not used for reading |
| Audit filter's document | `audit.http.openapi-rest-spec: ${HTTP_AUDIT_OPENAPI_SPEC:results-store-openapi.yaml}` | `application.yaml` | **unchanged**. The service's own `src/main/resources/results-store-openapi.yaml` stays the one document the glob finds; the jar carries its spec only at `openapi/openapi-spec.yml`. `OpenApiContractDriftTest` keeps the two equal (paths, components, tags; not `info` or `servers`) |

## `resultsstore.intake.store.*`: new defaults (spec 001 settings, changed by 003; E3)

003 lowers two intake defaults so the 90-second lag is provable (research R4). Changed in
`application.yaml` and in `IntakeProperties.Store`'s `@DefaultValue`s together (T008).

| Property | Default before 003 | Default from 003 | Environment variable | Rule (unchanged) |
|---|---|---|---|---|
| `resultsstore.intake.store.transaction-timeout` | `60s` | `60s` | `RESULTSSTORE_INTAKE_STORE_TRANSACTIONTIMEOUT` | at least each timeout below |
| `resultsstore.intake.store.statement-timeout` | `20s` | **`10s`** | `RESULTSSTORE_INTAKE_STORE_STATEMENTTIMEOUT` | > 0; ≤ transaction; below the socket timeout |
| `resultsstore.intake.store.lock-timeout` | `10s` | **`5s`** | `RESULTSSTORE_INTAKE_STORE_LOCKTIMEOUT` | > 0; ≤ statement; ≤ transaction |
| `resultsstore.intake.store.idle-in-transaction-timeout` | `10s` | `10s` | `RESULTSSTORE_INTAKE_STORE_IDLEINTRANSACTIONTIMEOUT` | > 0; ≤ transaction |

The lag rule (below) ties them to the read side: **lag ≥ transaction + 2 × statement +
idle-in-transaction**, and **lock ≤ statement**. At the defaults: 60 + 2 × 10 + 10 = 90 s.

Each is also checked to be whole in the unit that enforces it (gate round 1): the transaction timeout in
whole seconds (a Spring transaction timeout is an `int` of seconds, so `500ms` would run as one second) and
the three PostgreSQL limits in whole milliseconds (they are sent as `<n>ms`, so `500us` would be sent as
`0ms`, which PostgreSQL reads as no limit). A value that is not stops the service naming the property
(`… must be a whole number of seconds` / `milliseconds`), so the lag sum is always the bound actually enforced.

## Pool backstop: Hikari connection-init SQL (E3)

| Setting | Value | Set by | Rule |
|---|---|---|---|
| `spring.datasource.hikari.connection-init-sql` | `SET statement_timeout = '<n>ms'`, n = `resultsstore.intake.store.statement-timeout` in milliseconds (`10000ms` by default) | `config/StatementTimeoutBackstop` (a `BeanPostProcessor` on the `HikariDataSource`), from the bound intake property, so the two cannot differ | not set in `application.yaml` and not settable on its own: a non-blank value stops the service naming the property; an empty or blank one (an empty environment variable) counts as unset and is replaced. A test proves the pooled value equals the intake property, at the default and at a custom value |

Why in Java, not as a YAML line built from the same environment variable: Spring and PostgreSQL read
duration strings differently (`1m` is a minute to Spring and an error to PostgreSQL; `PT10S` is valid
only to Spring), and an init SQL PostgreSQL rejects would fail every connection.

What it is for: a server-side limit on every statement on every pooled connection, the read API's
included, as a backstop for anything that forgets its own. What it is not: part of the lag's proof. The
store transaction still sets its own `statement_timeout`, `lock_timeout` and
`idle_in_transaction_session_timeout` per transaction (`JdbcShareStore.SET_TIMEOUTS`), and the proof
rests on those. Client-side timeouts (the JDBC query timeout, a Spring transaction timeout used as a
client deadline) are never part of the bound.

It also applies to the sweep and the receipt transaction. A job on a pooled connection that needs longer
than the statement timeout sets `SET LOCAL statement_timeout` inside its own transaction.

## Flyway's own connection and statement timeout (T008)

The backstop would also bind Flyway if it migrated on a pooled connection, and an index build on a large
table can exceed 10 s. A session-level `SET` on a pooled connection would also return to the pool and undo
the backstop for the next borrower. So Flyway migrates on its own connection, with its limit lifted:

| Setting | Value | Why |
|---|---|---|
| `spring.flyway.user` | `${spring.datasource.username}` | with a Flyway user set, Boot gives Flyway an unpooled `SimpleDriverDataSource` derived from `spring.datasource` (same URL), not the Hikari pool; the backstop never applies to it, and nothing it sets reaches the pool |
| `spring.flyway.password` | `${spring.datasource.password:}` | the same credentials |
| `spring.flyway.init-sqls` | `SET statement_timeout = '${RESULTSSTORE_FLYWAY_STATEMENTTIMEOUT:0}'` | Flyway's connection runs with the limit lifted: `0` (no limit) by default |

| Environment variable | Default | Rule |
|---|---|---|
| `RESULTSSTORE_FLYWAY_STATEMENTTIMEOUT` | `0` | a **PostgreSQL** duration (`0`, `30min`, `600000`), not a Spring one; it is written into the SQL as sent |

Proved by `FlywayMigrationIT.flyway_should_migrate_on_its_own_connection_with_the_lifted_statement_timeout`
(Flyway's data source is not the Hikari pool, its init SQL is the line above, and a migration's
`BEFORE_MIGRATE` callback reads `statement_timeout` `0` on Flyway's connection) and by
`PooledStatementTimeoutIT` (every pooled connection, borrowed at once after Flyway has run, reads `10s`).

## `resultsstore.read.*` (`ReadApiProperties`)

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.read.pull.visibility-lag` | none set: derived as `transaction-timeout` + 2 × `statement-timeout` + `idle-in-transaction-timeout` of `resultsstore.intake.store.*` (**90 s** at their defaults) | `RESULTSSTORE_READ_PULL_VISIBILITYLAG` | ≥ that sum; ≤ 10 min (D-LAG-VALUE, E3) |
| `resultsstore.read.statement-timeout` | `5s` | `RESULTSSTORE_READ_STATEMENTTIMEOUT` | > 0; below the driver's socket timeout (`spring.datasource.hikari.data-source-properties.socketTimeout`, 30 s) when one is set. Despite the name, this is the read `JdbcTemplate`'s JDBC query timeout: a client-side cancel, backed by the socket timeout. It is not PostgreSQL's server-side `statement_timeout` that intake sets per transaction. That is acceptable for reads: a read blocks no writer, and a late cancel only delays one answer (spec FR-045). The pool backstop above (10 s) also applies to reads |

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
  - effective lag < sum → `IllegalArgumentException` (as every other settings rule) naming `resultsstore.read.pull.visibility-lag` and the
    sum's three properties (`Rules.atLeast(name, value, boundName, bound)`, a new four-argument
    overload beside the existing three-argument one);
  - effective lag > 10 min → `IllegalArgumentException`. When the lag is unset (so the derived sum is what
    is too long), the message names `resultsstore.intake.store.transaction-timeout`;
  - statement timeout ≥ the socket timeout → `IllegalStateException`, the same rule as
    `IntakeConfig`'s for the intake statement timeout.
- Messages name properties, never values from the environment.
- The read statement timeout is applied as the read `JdbcTemplate`'s query timeout, in whole seconds rounded
  up (`1500ms` runs as 2 s; never 0, which would be no timeout). The socket-timeout rule compares that
  rounded value, so `29500ms` under a 30 s socket timeout is refused (it would run as 30 s). The read template is built inside the
  `JdbcShareQueries` bean, not exposed as a bean: a second `JdbcTemplate` bean would make Boot's own back off
  and put the read timeout under intake's `JdbcClient`.

### The lag is checked per pod: rollout order (D-READONLY-PODS = no, E10)

The lag is checked against **this pod's own** `resultsstore.intake.store.*`. The writers are the pods
with the subscription on. The check proves nothing if pods disagree. So:

1. **Every pod of every deployment that shares the database MUST have the same
   `resultsstore.intake.store.*` values.** No deployment runs read-only pods or pods with other intake
   timeouts (E10).
2. **Raising an intake timeout:** first raise `resultsstore.read.pull.visibility-lag` (or leave it unset
   and roll the new timeouts to every pod at once, read-only pods first), then raise the timeout.
3. **Lowering an intake timeout:** lower the timeout on every pod first; lower the lag after.
4. The runtime check is `resultsstore.intake.visibility.overrun` (contracts/metrics.md): any increase
   means a store transaction outlived the lag.
5. **The 003 deploy itself** lowers the statement and lock timeouts and the derived lag together. That is
   safe only because the read API is new: consumers start pulling after every pod runs 003. Any later
   change follows steps 2 and 3.

## `authz.http.enabled` must be on (D-AUTHZ-REQUIRED = yes, E12)

`ApiWebConfig` stops the service at start when `authz.http.enabled` is not `true` and the `test`
profile is not active: `IllegalStateException` naming `authz.http.enabled`. The default in
`application.yaml` is already `${AUTHZ_HTTP_ENABLED:true}`.

## Other settings changed by 003

| Property | Value | Why |
|---|---|---|
| `server.error.whitelabel.enabled` | `false` | the white-label page would render a model other than the four bounded fields. The service's own `BoundedErrorController` replaces Boot's error controller anyway; the setting stays off as a second guard (research R13) |
| `spring.mvc.problemdetails.enabled` | stays `false` (not set) | Boot's handler would write `detail` and `instance` (research R13) |
| `server.compression.enabled` | stays `false` (not set) | a strong `ETag` is per representation (research R10) |

## `test` profile (`application-test.yaml`)

| Change | Why |
|---|---|
| header comment corrected: context tests now need the Testcontainers database (T003) | the read beans are unconditional |
| no new property | `authz.http.enabled`, `audit.http.enabled` and `cp.audit.enabled` stay `false`. The tests that need them switch them on for themselves (test properties or `@DynamicPropertySource`): `AuthzIT` and `FilterOrderIT` (T003), `ReadApiIT` and `AuditIT` (T011) |

## Compose (`docker-compose.yml`, app service; T012)

| Variable | Value | Why |
|---|---|---|
| `RESULTSSTORE_INTAKE_STORE_TRANSACTIONTIMEOUT`, `…_STATEMENTTIMEOUT`, `…_LOCKTIMEOUT`, `…_IDLEINTRANSACTIONTIMEOUT` | short values through `${VAR:-default}`: transaction 6 s, statement 2 s, lock 1 s, idle-in-transaction 1 s (lock ≤ statement holds) | so the smoke's derived lag is 6 + 2 × 2 + 1 = 11 s, not 90 s; the pool backstop follows the 2 s statement timeout |
| `RESULTSSTORE_READ_PULL_VISIBILITYLAG` | left unset | the smoke checks the derived default |

The usersgroups stub (`docker/wiremock/mappings`) answers any caller as "System Users"
(`identity-stub.json`, priority 10), except two matched on `CJSCPPUID` (priority 1): `2222…` in "Second Line
Support" (`identity-second-line.json`) and `1111…` in "Other Group" (`identity-no-group.json`, refused
`403`). The smoke (`scripts/container-smoke.sh`) uses all three.

## Constants that are deliberately not settings

They are the consumer contract (contracts/read-api.md); a setting would let it drift per environment.

| Constant | Where | Value |
|---|---|---|
| base path | `filters/ApiRoute` | `/results-store/v1` |
| routes and action names | `filters/ApiRoute` | spec.md FR-001, FR-046 |
| default and maximum `limit` | `application/ShareReadService` | 100 and 500 (pull and search) |
| search span | `application/ShareReadService` | day form: 31 days, both ends counted; time form: at most 31 days (P31D), half-open |
| cursor length | `domain/SearchCursor` | 128 characters |
| `Retry-After` on `503` | `api/ReadApiExceptionHandler` | 5 seconds |
| lag upper bound | `config/ReadApiConfig` | 10 minutes |
