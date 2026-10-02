# Workflow: Mandatory Build Loop

Every non-trivial code change MUST follow this cycle:

```
Contract → Failing test → Write → Code Review (agent) → QA (agent) → Contract Validate (agent) → Fix → Ship
```

- **Contract:** this service has three contracts:
  1. the **inbound event** — `public.events.hearing.hearing-resulted` on the Artemis `public.event`
     topic, owned by hearing. The store depends on three fields only: `hearing.id`, `hearingDay`
     and `sharedTime`. Everything else is stored as received and never validated (constitution
     Principle V). The subscription name and selector in `application.yaml` are part of this
     contract: changing either abandons the subscription and its backlog;
  2. the **read API** — `src/main/resources/results-store-openapi.yaml`, owned here and versioned
     with the repo. It describes every consumer endpoint under `/results-store/v1` and every
     support endpoint under `/operations/**`. `cp-audit-filter-springboot` reads it at runtime, so
     an endpoint missing from it is an endpoint whose audit event is wrong; and
  3. the **authorisation rules** — `src/main/resources/acl/results-store-rules.drl` and the path
     mapping in `ActionHeaderFilter`. One allow rule per action; no rule means no access.

  Update the contract BEFORE writing code that changes it. A change to what a consumer receives
  is a cross-team event: say so in the PR.
- **Contract Validate:** run the `spec-validator` agent to check the code against the three
  contracts and the gates below.

Loop repeats until ALL agents return PASS / COMPLIANT.

## What Requires the Loop

| Must Go Through Loop                            | Exempt                         |
|-------------------------------------------------|--------------------------------|
| New / modified Java class                       | Markdown / docs only           |
| New / modified test class or fixture            | Whitespace / import only       |
| Read API or operations endpoint                 | CLAUDE.md and rule updates     |
| `results-store-openapi.yaml` or `acl/*.drl` change | README changes              |
| Flyway migration                                |                                |
| Subscription / listener configuration           |                                |
| Dockerfile changes                              |                                |
| CI/CD pipeline config                           |                                |

## TDD is Non-Negotiable

- Write the failing test first; confirm it fails for the *correct* reason (assertion failure, not a
  compilation error)
- Then write the minimum production code to make it pass
- Then refactor with the test still green
- Commit history MUST show the failing test was authored at or before the production code

## Gates

A change ships only when all applicable gates are green:

1. **Identity gate.** A message missing `hearing.id`, `hearingDay` or `sharedTime`, or one that
   cannot be read, is recorded on its receipt (`NO_IDENTITY` or `UNREADABLE`) with a bounded reason
   and the message text, counted and acknowledged. It is never dead-lettered. Nothing else in the
   payload is validated.
2. **Never-refuse gate.** A share with its identity is always stored. An extraction failure marks
   the row `projection_status = FAILED`; it never drops the share. Proven by test.
3. **Transaction gate.** Receipt in its own transaction first; then one store transaction for
   everything that makes the share queryable; the message is acknowledged only after that commit.
   The progression lookup runs between the two, never inside a transaction. Proven by test.
4. **Idempotency gate.** Redelivery of a stored share stores nothing new: the share insert uses
   `ON CONFLICT DO NOTHING` on the identity's unique key, the receipt is marked `DUPLICATE` and the
   message is acknowledged, with no error and no rollback. Payloads are not compared. Proven by
   test, not by inspection.
5. **Immutability gate.** No code updates a stored share's facts or payload. The only updates are
   the latest pointer, the predecessor link and the day's youth flag, all under the hearing-day
   lock; and the key-details and `projection_*` columns, by the extraction sweep alone, from the
   stored payload. "Latest" is decided by `sharedTime`, never by arrival order.
6. **Indexed-search gate.** Pull and search queries use indexed columns only and never read the
   payload table.
7. **Default-deny gate.** Every endpoint has its `ActionHeaderFilter` mapping, its own allow rule
   and its OpenAPI entry. `/operations/**` admits "Second Line Support" only and returns no payload.
8. **No-swallowed-exception gate.** No empty catch, no catch-and-continue, no success returned from
   a catch block. Reviewers reject on sight.
9. **No-PII gate.** No personal data and no payload content in logs, metric labels, failure reasons
   or audit events, at any level.

## Agent Definitions

### code-reviewer (Read only)
- Spawned as sub-agent with Read-only tools
- Analyses code for: logic errors, null safety, layering violations, swallowed exceptions,
  acknowledgement before commit, rows updated outside the immutability rule, secrets, PII in logs,
  `System.out` usage
- Returns: **PASS** or **NEEDS CHANGES** with severity-rated findings
- NEVER modifies code — reports only

### qa (Read only)
- Spawned as sub-agent with read-only tools; `Bash` for inspection and for running the existing
  suite only
- Judges the tests that exist against the test matrix in `.claude/agents/qa.md` (JUnit Jupiter +
  Mockito + AssertJ; Testcontainers Postgres; embedded Artemis; WireMock for HTTP stubs) and names
  every gap
- Verifies TDD discipline (test tasks ordered before implementation tasks; a red run recorded before
  the green run)
- Runs `./gradlew test`
- Returns: **PASS** or **FAIL** with test results and missing-coverage findings
- NEVER writes tests and NEVER fixes production code — reports only

### software-engineer (Full access)
- For full feature implementation tasks
- **Writes every test**, test-first — the `qa` agent reviews them, it does not author them
- Follows all rules in `technical-rules.md` and `design_rules.md`
- Runs `./gradlew build pmdMain pmdTest jacocoTestReport` after changes

### spec-validator (Read only)
- Spawned as sub-agent with Read-only tools
- Checks the code against the three contracts and the gates above, and the constitution's domain
  principles (I–IX)
- Returns: **COMPLIANT** or **DRIFT DETECTED** with severity-rated findings
- NEVER modifies code — reports only

### research (Read, Glob, Grep, WebSearch)
- For deep codebase investigation, including the producer (hearing) and the enrichment source
  (progression) when establishing what the payload contains
- Cross-references design documents
- Returns structured findings with citations

## Critical Principle

**Agents are reporters, not fixers.** The parent agent (or developer) reads agent reports and applies
all fixes. This prevents conflicting changes and keeps the team in control.
