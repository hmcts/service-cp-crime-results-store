<!--
SYNC IMPACT REPORT
==================
Version change: 1.0.0 → 2.0.0
Bump rationale: MAJOR. Principle V is reversed: a message without an
                identity, or with an unreadable body, is no longer
                dead-lettered; it is recorded on its receipt and
                acknowledged. Principle VI drops the payload-digest
                comparison and the anomaly record. Both break practice
                written against 1.0.0. The changes align this file with the
                design page (v45, 2026-10-02).

Principles changed in 2.0.0:
  I.    Every Share Is an Immutable Version - the key-details columns and
        the projection_* columns may also be rewritten, by the extraction
        sweep only, from the stored payload
  II.   The Payload Is the Source of Truth - the checksum is SHA-256 over
        the stored text
  IV.   The Store Applies No Business Rules - a derived fact is added only
        by an agreed amendment, named as a derivation
  V.    Never Refuse to Store - non-shares are recorded with a bounded
        reason and the message text, counted and acknowledged; never
        dead-lettered (REVERSED)
  VI.   Idempotent, Transactional Intake - receipt keyed by the broker's
        message id with statuses and an attempt count; duplicates caught by
        the unique key with ON CONFLICT DO NOTHING; no digest comparison, no
        anomaly; a short capped pause before a retryable failure is thrown
  VII.  Default-Deny Authorisation - read-API rules admit "System Users",
        no youth scoping; audit on the library's default settings
Principles unchanged: III, VIII, IX, X, XI, XII.

Sections: Core Principles, Technology Stack & Deployment, Development
Workflow & Quality Gates, Governance.

Templates checked:
  ✅ .specify/templates/plan-template.md      - "Constitution Check" gate reads
                                                this file; no change needed
  ✅ .specify/templates/spec-template.md      - no change needed
  ✅ .specify/templates/tasks-template.md     - no change needed
  ✅ .claude/rules/*, .claude/agents/*        - updated to match
  ✅ CLAUDE.md                                - updated to match
  ✅ src/main/resources/application.yaml     - include-payload-body removed,
                                                so the audit library default
                                                applies

Follow-up TODOs:
  - Retention period is open on the design page. Amend the Technology Stack
    when it is decided.
-->

# service-cp-crime-results-store Constitution

The Results Store keeps every share of every resulted hearing day. Hearing
publishes `public.events.hearing.hearing-resulted` on the Artemis
`public.event` topic each time a hearing day is shared. The store receives
it, keeps it as an immutable version, indexes the facts consumers search on,
and serves the stored results through an internal read API. Consumers such as
the court register, youth offending team distribution and probation build
their own outputs from it.

The design is on Confluence:
[Results Store Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/321061800/Results+Store+Service)
(CRA space). Where the design page and this constitution disagree, this
constitution wins until it is amended.

## Core Principles

### I. Every Share Is an Immutable Version (NON-NEGOTIABLE)

A share is one version of one hearing day. Its identity is `hearingId`,
`hearingDay` and `sharedTime` together. Once a share is written, its facts and
its payload never change. Only these columns on a stored row may be updated
later. While holding the hearing-day lock:

- the latest pointer (which share of the day is latest);
- the predecessor link (which share came before it);
- the day's youth flag (`youth_seen`).

And by the extraction sweep alone, re-read from the stored payload:

- the key-details columns;
- the `projection_*` columns (extraction status and reason).

Nothing else is ever updated.

"Latest" is the share with the greatest `sharedTime`, worked out under the
lock. Arrival order never decides it. A share that arrives late is stored and
linked into its place; it does not become latest.

**Rationale**: consumers compare versions and replay history. That only
works if a stored version means the same thing every time it is read.

### II. The Payload Is the Source of Truth (NON-NEGOTIABLE)

The store keeps each payload exactly as it was received, plus the finalised
application results added at intake. Nothing else is added, removed or
reformatted. Every indexed column is read from the payload, and can be
rebuilt from it if the extraction rules change. The payload checksum is
SHA-256 over the stored text.

**Rationale**: if the columns can always be rebuilt from the payload, an
extraction bug is a re-run, not a data loss.

### III. Consumers Search Indexed Columns (NON-NEGOTIABLE)

Everything a consumer filters on (court centre, courtroom, hearing day, youth
flag, defendant id and so on) is a normalised, indexed column. The pull and
search queries use only these columns. They never open the payload. The
payload is returned only by the endpoint whose job is to return it.

**Rationale**: queries that read the payload get slower as the store grows.
Queries on indexed columns stay fast at any volume.

### IV. The Store Applies No Business Rules (NON-NEGOTIABLE)

The store captures everything and decides nothing about it. It records facts
exactly as the payload states them. For example, it records whether any
defendant in the share was flagged `isYouth`, and leaves the column empty when
it cannot read that. What "youth" means for a register is the consumer's
decision. Read authorisation may limit what a consumer sees; that is access
control, not a capture rule. Where a consumer needs a fact derived from the
data, it is added by an agreed amendment to this constitution, named as a
derivation, never silently.

**Rationale**: every consumer has its own rules. If the store applied one of
them, every other consumer would inherit it.

### V. Never Refuse to Store (NON-NEGOTIABLE)

Only `hearing.id`, `hearingDay` and `sharedTime` are required. A message
without one of them, or with a body that cannot be read, is not a share: it
is recorded on its receipt with a bounded reason and the message text,
counted, and acknowledged. It is never dead-lettered; the dead-letter queue
is for failures the broker gives up on after its own redelivery attempts.
Nothing else is validated, and the payload is not checked against a schema.
If extracting the
indexed columns fails, the share is still stored and the row is marked
(`projection_status = FAILED`) for the sweep to retry. Extraction failure
never drops a share.

**Rationale**: a share the store refuses is a share no consumer will ever
see. A share stored with a failed extraction can be fixed later.

### VI. Idempotent, Transactional Intake (NON-NEGOTIABLE)

- **Receipt first.** The listener records a receipt in its own transaction,
  before doing anything else. The receipt is keyed by the broker's message
  id, which every message has even when its body is unreadable. It holds the
  share's identity when the message carries one (nullable otherwise), a
  status (`RECEIVED`, then `STORED`, `DUPLICATE`, `UNREADABLE` or
  `NO_IDENTITY`), an attempt count, and a reason when something is wrong. A
  redelivery updates the receipt and raises its attempt count; it is never
  written twice.
- **One store transaction.** Locking the hearing day, inserting the share and
  its payload, extracting the columns, updating the youth flag, moving the
  latest pointer and marking the receipt `STORED` happen in one transaction.
  All or nothing.
- **Acknowledge after commit.** The message is acknowledged only after the
  store transaction commits.
- **Idempotent.** A unique key on (`hearing_id`, `hearing_day`, `shared_at`)
  is the whole mechanism. The share is inserted with `ON CONFLICT DO
  NOTHING`; if nothing was inserted, the share is already stored, the
  receipt is marked `DUPLICATE` and the message is acknowledged. A duplicate
  raises no error, is not rolled back and is never dead-lettered. The first
  stored payload stays; payloads are not compared and nothing is recorded
  beyond the receipt.
- **Retryable failures go back to the broker.** A failure a retry can fix
  (database or progression unreachable) is thrown so the message rolls back
  and the broker redelivers it. Before throwing, the listener pauses for
  `min(2^deliveryCount s, 30 s)`, so the broker's immediate redeliveries are
  not used up during a brief outage. There is no retry loop in the store.
- The progression lookup for finalised application results runs between the
  two transactions, never inside one. If progression cannot be reached, the
  message rolls back; the share is never stored half-enriched.

**Rationale**: the broker may deliver a message more than once, and a pod may
die at any moment. The receipt log lets reconciliation prove nothing was
lost; the single transaction means no consumer sees a half-stored share.

### VII. Default-Deny Authorisation (NON-NEGOTIABLE)

- `cp-auth-rules-filter` runs with `deny-when-no-rules: true`. An action with
  no rule is refused.
- Every endpoint adds its own allow rule in
  `src/main/resources/acl/results-store-rules.drl`, naming the groups it
  admits. No rule ever allows everything.
- The action is worked out from the request's path and method by
  `ActionHeaderFilter`. A caller-supplied `CPP-ACTION` header is never
  trusted for a mapped path.
- Every read-API action's rule admits the "System Users" group. There is no
  youth scoping; finer-grained rules are added only when a need appears.
- `/operations/**` is for "Second Line Support" only, and never returns a
  payload. Support staff read payloads through the read API under its own
  rules.
- Every request is audited by `cp-audit-filter-springboot`, with the
  library's default settings.

**Rationale**: the store holds every defendant's results, including
children's. An endpoint someone forgot to protect must fail closed.

### VIII. Observability Through Azure Monitor (NON-NEGOTIABLE)

The service publishes metrics and telemetry to Azure Monitor. An Azure Monitor
dashboard shows them with the reconciliation findings, and Azure Monitor
alerts fire when something goes wrong. The service sends **no exception
reports** (no e-mailed or file reports of failures).

A path that drops or fails something moves a counter with a bounded reason.
"It is in the logs" is not an alert.

**Rationale**: Modern by Default treats exception reports as a legacy pattern
that new services must not introduce.

### IX. Artemis Only for Legacy Integration (NON-NEGOTIABLE)

Artemis is used only to integrate with legacy services that are already on
it. Today that is hearing's `public.event`. New messaging follows Modern by
Default and uses Azure Service Bus. The store publishes nothing on Artemis.

The subscription name (`resultsstore-service.sdg`) and its selector are the
subscription's identity on the broker. Changing either abandons the existing
subscription and its backlog, so they change only by a deliberate, reviewed
decision.

**Rationale**: Artemis is the legacy estate's broker. Building new things on
it makes the move away from it harder.

### X. Test-Driven Development (NON-NEGOTIABLE)

Red, green, refactor, for every behaviour change.

1. Write the failing test first. It must fail for the right reason (a failed
   assertion, not a compile error).
2. Write the least production code that makes it pass.
3. Refactor with the test still green.

The evidence must be visible in history:

- Test tasks come before their implementation tasks in every `tasks.md`.
- The commit or task narrative quotes the red run before the green run.
- Reviewers reject tests that could not have failed first (always-true
  assertions, tests that only check no exception was thrown).

Exempt: pure mechanical refactors, formatting, and comment-only edits.

**Rationale**: a test written after the code describes what the code does. A
test written first describes what it should do.

### XI. Privacy in Telemetry (NON-NEGOTIABLE)

- Log identifiers only: `shareId`, `hearingId`, `hearingDay`, `sharedTime`,
  counts and timings. No names, dates of birth, addresses, prompt values or
  other personal data, at any level, in a deployed environment.
- Never log a payload, or part of one.
- Metric labels are bounded codes. Never label a metric with anything that
  identifies a person, and never with a free-text value.
- Failure reasons are bounded codes, never raw exception text and never a
  fragment of a payload.
- Never log secrets, tokens or connection strings.

**Rationale**: the payloads hold special-category and youth personal data.
Identifiers are enough to support the service.

### XII. HMCTS Estate Conventions (NON-NEGOTIABLE)

- **Build**: Gradle, wrapper committed. Never Maven, never Spring Initializr.
- **Stack**: Spring Boot 4, Java 25. Root package `uk.gov.hmcts.cp.resultsstore`.
- **Code**: constructor injection only, injected fields `private final`.
  Java records for DTOs. No wildcard imports. Explicit access modifiers.
- **Errors**: never swallow an exception. Catch only to classify and rethrow,
  or to record an explicit outcome.
- **Logging**: SLF4J only. No `System.out`, `System.err` or
  `printStackTrace()`, in production or test code. JSON logs from
  `logback.xml`.
- **Persistence**: Flyway migrations under `src/main/resources/db/migration`.
  Never Liquibase.
- **Configuration**: no hard-coded URLs, topic names, ports or secrets; typed
  `@ConfigurationProperties`.
- **Commits**: Conventional Commits (`feat`, `fix`, `test`, `refactor`,
  `chore`, `docs`, `build`, `ci`, `style`).
- **No AI attribution** in commits, branch names, PRs, code comments or
  docs. Everything reads as developer-written work.

**Rationale**: this is one of about seventy CPP services, run by people who
did not write it. Shared conventions let them read it like the others.

## Technology Stack & Deployment

- **Java** 25, **Spring Boot** 4.1, **Gradle**, from
  `hmcts/service-hmcts-crime-springboot-template`.
- **Port**: 8082 locally.
- **Database**: PostgreSQL with Flyway. Readiness depends on the database.
- **Messaging in**: a shared durable subscription `resultsstore-service.sdg`
  on the Artemis `public.event` topic, selector
  `CPPNAME = 'public.events.hearing.hearing-resulted'`, no client id, one
  message at a time per pod, transacted. Broker health never gates
  readiness.
- **Lookups**: progression's query API over REST, as a system user, for
  finalised application results.
- **Authorisation and audit**: `cp-auth-rules-filter` and
  `cp-audit-filter-springboot`, as used by the results validator. The audit
  transport (`cp.audit.enabled`) is off by default and switched on by each
  environment that serves the API.
- **Secrets**: from Azure Key Vault through the Secrets Store CSI driver.
  Never committed, never defaulted, never logged.
- **CI/CD**: GitHub Actions (`ci-draft`, `ci-released`, `ci-build-publish`,
  `code-analysis`, `codeql`, `secrets-scanner`, `auto-merge-dependabot`),
  then ADO pipeline 460 for deployment.
- **Observability**: Azure Monitor (Principle VIII).

## Development Workflow & Quality Gates

- Every feature is built with Spec Kit under `specs/NNN-slug/`, with at least
  `spec.md`, `plan.md` and `tasks.md`. Flow: `/speckit-specify →
  /speckit-plan → /speckit-tasks → /speckit-implement → /speckit-analyze`.
- Non-trivial changes follow `Spec → Write → Code Review → QA →
  Spec-Validate → Fix → Ship`. The reviewer agents (`code-reviewer`, `qa`,
  `spec-validator`) report findings only. They never modify files. The
  developer applies the fixes and repeats until all three pass. Exempt:
  markdown-only edits, whitespace or import-only edits, and changes to
  `.claude/rules/*` or `CLAUDE.md`.
- An API change updates `src/main/resources/results-store-openapi.yaml` and
  the allow rules before the code that serves it.
- Must be green before merge:
  `./gradlew build pmdMain pmdTest jacocoTestReport`. PMD is pinned
  (7.22.0) and fails the build; `pmdMain` runs only when named, so name it.
  The JaCoCo gate in `gradle/test.gradle` requires 0.88 line and 0.85 branch
  coverage, excluding `Application` and `config/**`. Thresholds only go up.
  Docker must be running for the Testcontainers suites.
- Pull requests state which principles the change touches. A deviation needs
  a written justification in the PR and in the plan's "Complexity Tracking"
  section.
- Reviewers look in particular for: a share or row updated outside
  Principle I, a query that opens the payload (III), a business rule in
  capture (IV), a share refused or dropped (V), an acknowledgement before
  commit (VI), an endpoint without its own allow rule (VII), personal data in
  a log line (XI), and production code with no failing test first (X).

## Governance

This constitution overrides the guidance in `.claude/rules/` and `CLAUDE.md`.
Where they disagree, this document wins, and the other files are corrected to
match.

**Amendment procedure**:

1. Propose the change in a feature spec under `specs/`, or in a pull request
   that changes only this file.
2. Bump the version by semantic versioning:
   - **MAJOR**: a principle removed or redefined in a way that breaks
     existing practice.
   - **MINOR**: a new principle or section, or materially wider guidance.
   - **PATCH**: clarifications and wording.
3. Update the Sync Impact Report at the top of this file.
4. Re-run `/speckit-analyze` on every feature in progress.

**Compliance**:

- Every PR honours these principles.
- Reviewers block a merge that breaks a NON-NEGOTIABLE principle without a
  written waiver.

**Version**: 2.0.0 | **Ratified**: 2026-10-01 | **Last Amended**: 2026-10-02
