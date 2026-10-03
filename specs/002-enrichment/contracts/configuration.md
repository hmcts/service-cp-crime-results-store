# Contract: configuration (002 delta)

Adds to [../../001-share-intake/contracts/configuration.md](../../001-share-intake/contracts/configuration.md).
Same conventions: typed `@ConfigurationProperties` records in `config/`, Spring duration format,
checked at start (a bad value stops the service, proved by `ConfigurationValidationTest` and
`IntakeConfigTest`). Unlike 001's settings, the base URL and the system user id have **no default**.

## `resultsstore.enrichment.*` (`EnrichmentProperties`)

| Property | Default | Environment variable | Rule |
|---|---|---|---|
| `resultsstore.enrichment.enabled` | `true` | `RESULTSSTORE_ENRICHMENT_ENABLED` | boolean. When false no progression call is made and every share is stored un-enriched (FR-005, FR-024) |

## `resultsstore.progression.*` (`ProgressionProperties`)

| Property | Default | Environment variable | Rule (when enrichment is on) |
|---|---|---|---|
| `resultsstore.progression.base-url` | none: `${CP_BASE_URL:}` | `CP_BASE_URL` | not blank; an absolute `http` or `https` URL with a host and no user info, path (other than empty or `/`), query or fragment |
| `resultsstore.progression.system-user-id` | none: `${RESULTS_STORE_SYSTEM_USER_ID:}` | `RESULTS_STORE_SYSTEM_USER_ID` (Key Vault secret `RESULTS-STORE-SYSTEM-USER-ID`) | not blank; a canonical UUID |
| `resultsstore.progression.connect-timeout` | `5s` | `RESULTSSTORE_PROGRESSION_CONNECTTIMEOUT` | 1 s to 30 s |
| `resultsstore.progression.read-timeout` | `10s` | `RESULTSSTORE_PROGRESSION_READTIMEOUT` | 1 s to 60 s; also the whole-response deadline (contracts/progression-lookup.md) |

`application.yaml`:

```yaml
resultsstore:
  enrichment:
    enabled: ${RESULTSSTORE_ENRICHMENT_ENABLED:true}
  progression:
    # The stack's internal mesh host, set per environment. No default: a missing value must stop the
    # service, not send the call to this service's own port.
    base-url: ${CP_BASE_URL:}
    # The store's own system user ("System Users"), from Key Vault. Never defaulted, never logged.
    system-user-id: ${RESULTS_STORE_SYSTEM_USER_ID:}
    connect-timeout: ${RESULTSSTORE_PROGRESSION_CONNECTTIMEOUT:5s}
    read-timeout: ${RESULTSSTORE_PROGRESSION_READTIMEOUT:10s}
```

### Where the rules run

- Each record's compact constructor checks its own values that need no other setting: timeouts in
  range always; base URL and user id shape only when not blank.
- The blank check, which depends on `resultsstore.enrichment.enabled`, runs in `ProgressionConfig`
  when it builds the client. It fails start with an `IllegalArgumentException` naming the property
  (`resultsstore.progression.base-url` or `resultsstore.progression.system-user-id`), never its value:
  `<property> must be set when resultsstore.enrichment.enabled is true`. The shape checks fail the
  same way (`… must be an absolute http or https URL …`, `… must be a canonical UUID`).
  `ProgressionProperties.toString()` leaves out both values.
- The client and its `RestClient` are built only when `resultsstore.publicevents.enabled` and
  `resultsstore.enrichment.enabled` are both true (the second also when it is not set, matching its
  `true` default). With enrichment off, both values may be blank.
- `config/Rules` gains `absoluteHttpUrl(name, value)` and `uuid(name, value)` beside `within` and
  `positive`.

## `test` profile (`application-test.yaml`)

| Property | Value | Why |
|---|---|---|
| `resultsstore.enrichment.enabled` | `false` (literal) | no progression in context-load tests; an exported variable cannot switch it on. Suites that cover enrichment set it true with `@DynamicPropertySource`, with a WireMock base URL and a synthetic user id |

## Compose (`docker-compose.yml`, app service)

| Variable | Value | Why |
|---|---|---|
| `CP_BASE_URL` | `http://wiremock:8080` (already set for usersgroups) | the progression stub is on the same WireMock |
| `RESULTS_STORE_SYSTEM_USER_ID` | a synthetic UUID, committed | stands in for the Key Vault secret; not a real user |

`RESULTSSTORE_ENRICHMENT_ENABLED` is left unset, so the default (`true`) is what the smoke check runs.

## Deployed environments (not part of 002)

`CP_BASE_URL` comes from `cpp-aks-deploy` `ansible/group_vars/resultsstore-service_values.yaml.j2`
(the stack's `-internal` Istio host), and `RESULTS_STORE_SYSTEM_USER_ID` from the chart's
`secretProvider` block (Key Vault `RESULTS-STORE-SYSTEM-USER-ID`). Neither file exists yet; adding them
is a separate task, and the service will not start with enrichment on until both are set.

## Constants that are deliberately not settings

| Constant | Where | Why |
|---|---|---|
| path `/progression-query-api/query/api/rest/progression/applications/{applicationId}` | `adapter/progression/ProgressionApplicationClient` | progression's contract, not an environment's choice |
| media type `application/vnd.progression.query.application-only+json` | same | selects the action |
| header name `CJSCPPUID` | same | the estate's identity header |
| the three removed fields | `application/ApplicationResultsEnricher` | parity with results (FR-010) |
| redirects off | `ProgressionConfig` request factory | never send the identity header elsewhere |
