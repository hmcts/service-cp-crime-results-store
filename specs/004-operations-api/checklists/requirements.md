# Specification Quality Checklist: Operations API

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-03
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — routes, body fields, parameters, reason codes, outcome names, table names, guard names and setting names are the support contract and the database backstop that reviewers check against, not the build. Class names appear only under *Changes to spec 001, spec 002 and spec 003*, to say which existing code 004 touches; SQL, class layout and test names are left to plan.md and tasks.md.
- [x] Focused on user value and business needs — each story says why it matters: fixing an extractor bug in stored shares, seeing a stalled sweep, answering "what happened to this message", proving nothing was lost, keeping support data away from everyone else.
- [x] Written for non-technical stakeholders — short sentences and everyday words; the rerun outcomes are a table with "when" and "written"; the decisions table says what is assumed and what else could be chosen.
- [x] All mandatory sections completed — scope, user stories, edge cases, functional requirements, changes to specs 001 to 003, key entities, success criteria, decisions pending Sachin and assumptions are filled.

## Requirement Completeness

- [x] No clarification markers remain — every open point is a row of *Decisions pending Sachin* with the default applied and the alternatives listed; the requirements carry the default and say "pending Sachin".
- [x] Requirements are testable and unambiguous — each FR names a route, a field, a status and reason, an outcome, a guard name, a counter, a setting with its default, or a document change that a test or a grep can check.
- [x] Success criteria are measurable — each SC has a count, a percentage or a pass/fail threshold.
- [x] Success criteria are technology-agnostic (no implementation details) — outcomes are responses, row states, counts and refusals. SC-002 names database updates because the guard is the requirement; SC-012 names the coverage figures only because the build gate requires them.
- [x] All acceptance scenarios are defined — every story has Given/When/Then scenarios and an independent test.
- [x] Edge cases are identified — shares stored while a request is written, overlapping requests, a pod dying mid-item, a rerun of a `FAILED` share, rolling deploys, values moving to null, youth flags moving in each direction, clock-change days, receipts across midnight, bad bodies and media types, a sweep switched off.
- [x] Scope is clearly bounded — the Scope section names replay, the nightly job, sampled R2, a youth-raised feed, a cancel endpoint, defendant row removal, retention and erasure, read API changes and deploy values as out of scope.
- [x] Dependencies and assumptions identified — spec 003 is named as the base with what 004 reuses; *Decisions pending Sachin* lists every D-item from the rulings that touches 004; *Assumptions* lists the settled choices.

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria — FR groups map to stories 1 to 6 and to SC-001 to SC-012.
- [x] User scenarios cover primary flows — rerun request and sweep, status, receipts, reconciliation, authorisation and leaks, metrics.
- [x] Feature meets measurable outcomes defined in Success Criteria — every story has at least one SC that proves it.
- [x] No implementation details leak into specification — task numbers, SQL, class layout and test names are left to plan.md and tasks.md.

## Notes

- Validation pass 1 found two problems in spec.md: User Story 1's independent test described changing a stored share, which the guards forbid (reworded: the share is inserted with key details its working copy does not state); and the rulings' FALSE-to-unknown youth move was not covered by any default (now written under D-NEVER-BLANK and named in D-YOUTH-RAISE). Pass 2: every item passes.
- `grep -c "NEEDS CLARIFICATION" spec.md` = 0.
