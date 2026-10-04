# service-cp-crime-results-store

The Results Store: a versioned, queryable store of every share of every resulted hearing day.

It subscribes to `public.events.hearing.hearing-resulted` on the Artemis `public.event` topic, keeps
every share of a hearing day as an immutable version, indexes the facts consumers search on, and
serves the stored results through an internal read API. Consumers (court registers, youth
offending team distribution, probation and others) build their own outputs from it. The store
applies no business rules of its own.

## Programme
Crime Common Platform (CPP), Modern by Default (MbD).
Organisation: HMCTS / Ministry of Justice. Team: Resulting Assistant.
Design: [Results Store Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/321061800/Results+Store+Service)
(Confluence, CRA space).

## Stack
- Spring Boot 4.1, Java 25, **Gradle (never Maven)**
- Package: `uk.gov.hmcts.cp.resultsstore`; configuration prefix `resultsstore.*`
- Port: 8082 locally
- PostgreSQL + Flyway; Artemis (Spring JMS) for the one legacy subscription
- `cp-auth-rules-filter` (default deny) and `cp-audit-filter-springboot`
- Derived from `hmcts/service-hmcts-crime-springboot-template`. Never scaffold from scratch and
  never use Spring Initializr.

## Key Documentation
| Document | Location |
|---|---|
| Design (authoritative) | Confluence: [Results Store Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/321061800/Results+Store+Service). This repo carries no design narrative |
| Constitution | `.specify/memory/constitution.md` |
| Overview, local stack, configuration | `README.md` |
| Pipeline and logging | `docs/PIPELINE.md`, `docs/Logging.md` |
| API description (audit filter reads it) | `src/main/resources/results-store-openapi.yaml` |
| Authorisation rules | `src/main/resources/acl/results-store-rules.drl` |
| Specifications | `specs/` (none yet) |

## What the skeleton has, and has not
Has:
- `HearingResultedEventListener`: a stub on the shared durable subscription. It reads `hearing.id`,
  `hearingDay` and `sharedTime`, logs them, and acknowledges. It stores nothing.
- Flyway `V1__create_event_receipt.sql`: the receipt-log table. Nothing writes to it yet.
- `ActionHeaderFilter` with no path mappings, and a DRL with no rules. Everything outside
  `/actuator` and `/error` is refused.
- Audit filter wired; `cp.audit.enabled` is `false` by default.
- The GitHub Actions pipeline and checks the sibling results-distribution services use.
- Spec 001 (share intake, the write path) is in progress on branch `001-share-intake`.

Has not (arrives with feature specs): storing shares and versions, enrichment from progression,
the read API and its rules, the operations API, reconciliation, purge, metrics and alerts. The
design also says unmapped paths must be refused by `ActionHeaderFilter`; the skeleton still lets
them through to the default-deny rules.

## Subscription Rule
The subscription name `resultsstore-service.sdg` and its selector
`CPPNAME = 'public.events.hearing.hearing-resulted'` are the subscription's identity on the broker
(there is no client id). Changing either abandons the existing subscription and its backlog. Never
change them casually; a change needs a reviewed decision and a plan for the old backlog.

The store publishes nothing on Artemis. New messaging uses Azure Service Bus (constitution
Principle IX). The broker never gates readiness.

## Authorisation Rule
Default deny. Every new endpoint adds, in the same change: its mapping in `ActionHeaderFilter`,
its own allow rule in `acl/results-store-rules.drl` naming the groups it admits, and its entry in
`results-store-openapi.yaml`. `/operations/**` is for "Second Line Support" only and never returns
a payload. Read-API rules admit "System Users" and "Second Line Support" and match method and path; the
action is derived from method and path, never taken from the caller. Every request that reaches an endpoint
is audited and every refused one is counted (`resultsstore.read.refused`); the payload endpoints' audit
body is the fixed marker `{"payloadOmitted":true}` (constitution 2.2.0, Principle VII).

## Build & Test
```bash
./gradlew build pmdMain pmdTest jacocoTestReport   # the merge gate: compile (-Werror), all tests,
                                                   # PMD on main and test, coverage report and gate
./gradlew test                                     # the whole suite, unit and *IT alike
./gradlew bootJar && docker compose up -d --build  # local stack: app, postgres, artemis, wiremock
./scripts/container-smoke.sh                       # build the image, require readiness within 60s
```
- Docker must be running: the `*IT` suites use Testcontainers (Postgres) and an embedded Artemis.
- `pmdMain` runs only when named on the command line; `pmdTest` runs in `check`.
- JaCoCo gate: 0.88 line / 0.85 branch, excluding `Application` and `config/**`
  (`gradle/test.gradle`). Thresholds only go up.

## Repository Conventions
- No wildcard imports; constructor injection; records for DTOs; SLF4J only.
- Log identifiers only (`shareId`, `hearingId`, `hearingDay`, `sharedTime`). Never a payload or any
  personal data.
- Tests: `{ClassName}Test` for unit, `{ClassName}IT` for integration; method names
  `{action}_{scenario}_should_{expectation}`; AssertJ; `@DisplayName` for readable names.
- PMD (7.22.0, `.github/pmd-ruleset.xml` and `.github/pmd-test-ruleset.xml`) fails the build.
- TDD: the failing test first, with the red run quoted before the green run in the task narrative.
- Conventional Commits; no AI attribution in commits, PRs, comments or docs.
- `main` is protected: changes arrive by pull request with the required checks green and one
  approval.
- Never run two committing agents in this repo at the same time.

## Setup

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/002-enrichment/plan.md` (enrichment from progression), with its
`research.md`, `data-model.md`, `contracts/` and `quickstart.md` beside it.
Spec 001 (`specs/001-share-intake/`) describes the write path it extends.
<!-- SPECKIT END -->
