# Results Store

A versioned, queryable store of every share of every resulted hearing day.

The service subscribes to `public.events.hearing.hearing-resulted` on the estate's Artemis
`public.event` topic, keeps every share of a hearing day as an immutable version, and serves the
stored results to consumers (court registers, youth offending team distribution, probation and
others) through a read API.

Design: [Results Store Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/321061800/Results+Store+Service)
(Confluence, CRA space).

## Status

This repository is the skeleton the first feature spec builds on. It has:

- Spring Boot 4.1 on Java 25, built with the Gradle wrapper.
- **PostgreSQL + Flyway.** `V1__create_event_receipt.sql` creates the receipt-log table. Nothing writes to it yet.
- **Artemis subscription.** A shared durable subscription `resultsstore-service.sdg` on `public.event`, with the broker-side selector `CPPNAME = 'public.events.hearing.hearing-resulted'`. The listener is a stub: it logs the share's identity (`hearing.id`, `hearingDay`, `sharedTime`) and acknowledges.
- **Authorisation.** `cp-auth-rules-filter`, default deny, with `acl/results-store-rules.drl` holding no rules yet. Everything outside `/actuator` and `/error` is refused.
- **Audit.** `cp-audit-filter-springboot`. The audit transport (`cp.audit.enabled`) is off by default.
- **Pipeline.** The GitHub Actions pipeline and checks the sibling results-distribution services use.

Not here yet, and arriving with the feature specs:

- storing shares and their versions;
- the read API, its action mappings (`ActionHeaderFilter`) and allow rules;
- enrichment, retention and purge;
- API tests.

## Prerequisites

- Java 25 (the Gradle toolchain will provision it if it is missing).
- Docker. Testcontainers needs it for the integration tests, and you need it for the local stack.

## Build and test

```bash
./gradlew build                            # compile, unit + integration tests, coverage gate
./gradlew pmdMain pmdTest                  # static analysis (PMD is outside `check` for main sources)
./gradlew jacocoTestReport                 # coverage report under build/reports/jacoco
./scripts/container-smoke.sh               # build the image and require readiness within 60s
```

The integration tests start their own Postgres (Testcontainers) and an embedded Artemis broker.
The coverage gate (`gradle/test.gradle`) requires 88% line and 85% branch coverage, excluding
`Application` and `config/**`.

## Run locally

```bash
./gradlew bootJar
docker compose up -d --build
curl localhost:8082/actuator/health
```

The stack is:

| Service    | Port(s)        | Purpose                                                  |
|------------|----------------|----------------------------------------------------------|
| `app`      | 8082           | this service                                             |
| `postgres` | 5432           | the store (`resultsstore` / `resultsstore`)              |
| `artemis`  | 61616, 8161    | the `public.event` topic; console on 8161 (admin/admin)  |
| `wiremock` | 8089           | usersgroups stub for the authorisation filter            |

To see the listener working, publish a test event from the broker container:

```bash
docker compose exec artemis /var/lib/artemis-instance/bin/artemis producer \
  --user admin --password admin --url tcp://localhost:61616 \
  --destination topic://public.event --message-count 1 \
  --properties '[{"type":"string","key":"CPPNAME","value":"public.events.hearing.hearing-resulted"}]' \
  --message '{"hearing":{"id":"0d7c2b1a-1111-4222-8333-944455556666"},"hearingDay":"2026-09-30","sharedTime":"2026-09-30T15:04:05.000Z"}'
docker compose logs app | grep hearing-resulted
```

`docker compose down -v` removes the stack and its database volume.

## Configuration

| Property / variable                                   | Default                      | Notes                                                         |
|-------------------------------------------------------|------------------------------|---------------------------------------------------------------|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`   | local compose values         | from Key Vault in deployed environments                       |
| `ARTEMIS_BROKER_URL` / `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | empty                   | the broker never gates readiness                              |
| `RESULTSSTORE_PUBLICEVENTS_ENABLED`                   | `true`                       | starts the subscription's listener container                  |
| `AUTHZ_HTTP_ENABLED`                                  | `true`                       | `cp-auth-rules-filter`                                        |
| `CP_BASE_URL`                                         | `http://localhost:8080`      | base URL for usersgroups                                      |
| `HTTP_AUDIT_ENABLED`                                  | `true`                       | HTTP half of the audit filter                                 |
| `CP_AUDIT_ENABLED`                                    | `false`                      | audit transport; set `true` with hosts/port where the API is served |

Changing `resultsstore.publicevents.subscription` or `resultsstore.publicevents.selector` abandons the
existing subscription and its backlog on the broker.

## Pipeline

- `ci-draft.yml` (PRs and pushes to `main` / `team/**`) and `ci-released.yml` (releases) call
  `ci-build-publish.yml`: Artefact-Version, Build, Test, Container-Smoke, then on push
  Provider-Deploy (publish the JAR to GitHub Packages and Azure Artifacts), Build-Docker (GHCR + Trivy)
  and Deploy (ADO pipeline 460).
- `code-analysis.yml` (PMD), `codeql.yml` (CodeQL, SBOM, DAST), `secrets-scanner.yml`,
  `auto-merge-dependabot.yml`; Dependabot config in `.github/dependabot.yml`.

See [docs/PIPELINE.md](docs/PIPELINE.md) and [docs/Logging.md](docs/Logging.md).

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
