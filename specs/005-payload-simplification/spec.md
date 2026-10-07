# Feature Specification: Payload Simplification

**Feature Branch**: `005-payload-simplification`
**Created**: 2026-10-07
**Status**: Draft
**Input**: Two decisions taken while reviewing the Results Store design page (CRA 321061800) on
2026-10-07: every share has a working copy, and the arrived-text endpoint is withdrawn. The guiding
instruction: *we should not overcomplicate our implementation over theoretical risk*. The change
delivers less code than today, not more.

**Sources**: the design page; specs 001 (*Share intake*, FR-015, research R8), 002 (*Enrichment*,
FR-019, FR-031, FR-041, research R6, R18) and 003 (*Read API*, phase D, FR-033, FR-035, FR-038,
FR-041, US8, research R19, R23) as built and merged to `main` at `2ba9a8b`; the contract repository
`hmcts/api-cp-crime-results-store` (release `0.2.0`). Class and file names are those of the built code.

### Scope

**In scope.**

1. **Every share has a working copy.** The `\u0000` escape and each unpaired surrogate escape are
   removed from the working copy (`payload_json`) before it is written, so the copy is always held.
   `payload_text` and `payload_sha256` stay exactly as received. The NULL working copy, the savepoint
   and the SQLSTATE class 22 handling, the counter `resultsstore.intake.parsed.copy.skipped`, the
   enriched-copy refusal and its `unstorable_results` fallback, and the read API's arrived-text
   fallback on `GET /results-store/v1/shares/{shareId}/payload` are removed.
2. **The arrived-text endpoint is withdrawn.** `GET /results-store/v1/shares/{shareId}/payload/arrived`
   (spec 003 phase D, T013) is removed from the contract repository first (release `0.3.0`), then from
   the service: its route, action, allow rule, controller method, query, meters, audit marker entry
   and tests. The `Results-Store-Payload-Form` header of the payload operation goes with it: it had
   one value left.
3. Constitution 2.2.0 → 2.3.0 (Principle II and Principle V); short *Amended by spec 005* notes on
   specs 001, 002 and 003; the design rules; the compose smoke check.

**Out of scope.**

- The design page (edited by its owner in parallel).
- Spec 004 (unbuilt). Its planned constitution 2.3.0 and contract `v0.3.0` are now 2.4.0 and `v0.4.0`;
  004 renumbers itself when it is built.
- The `ETag` of the payload operation: unchanged.
- A Flyway change. `payload_json` stays nullable; a row stored without a copy before this spec (none
  is expected) is still read by the extraction sweep from its text.
- A backfill of such rows. Before the service change is released, every deployed environment is
  checked for `payload_json IS NULL` rows; a non-zero count is a separate task.

### Why

The NULL working copy existed for one theoretical case: a text whose only fault is a `\u0000` escape
or an unpaired surrogate escape, which PostgreSQL `jsonb` refuses. Keeping a second path for it (the
savepoint, the counter, the text fallback on the read API, the second payload form) cost more than
the case is worth, and the read API's consumers (court registers, youth offending teams) read the
working copy. Removing the two escapes from the copy keeps every share readable from one place. The
arrived-text endpoint was built for probation's draft ask S10; no probation work exists and no
consumer needs it. The arrived text stays in the store, with its checksum, for support.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A share with an escape jsonb refuses is stored with a working copy (Priority: P1)

A hearing-resulted message holds a `\u0000` escape (or an unpaired surrogate escape) in some text
field. The store keeps the message text exactly as it arrived, with its checksum, and holds a working
copy from which those escapes are removed. The read API serves that working copy; the extraction
sweep reads it.

**Independent Test**: Publish a share whose text holds `a\u0000b`. The share is stored; `payload_text`
is the text as sent and `payload_sha256` is its SHA-256; `payload_json` holds the text with the escape
removed (`ab`); `GET /shares/{shareId}/payload` serves the working copy with an `ETag` over the bytes
served and no `Results-Store-Payload-Form` header.

**Acceptance Scenarios**:

1. **Given** a text holding `\u0000`, `\uD800` (a high surrogate escape with no low one) or `\uDC00`
   (a low one on its own), **When** it is stored, **Then** the share has a working copy equal to the
   text with those escapes removed, and the text and checksum are unchanged.
2. **Given** a text holding a surrogate pair escape (`😀`), an escaped backslash followed
   by `u0000` (`\\u0000`), or any other escape (`\n`, `\"`), **Then** the working copy keeps it.
3. **Given** finalised application results from progression holding a `\u0000` escape, **Then** the
   enriched copy is stored with the escape removed and `enrichment_applied` true; nothing is run
   twice and nothing is counted as skipped.
4. **Given** a text the database itself cannot hold as `jsonb` for any other reason (a number beyond
   its numeric range), **Then** the store transaction fails like any database failure: retryable,
   redelivered by the broker, and dead-lettered after its attempts. Nothing special is done.

### User Story 2 - The arrived-text endpoint is gone (Priority: P1)

A caller of `GET /results-store/v1/shares/{shareId}/payload/arrived` is refused as for any unmapped
path.

**Independent Test**: With a stored share, `GET .../payload/arrived` answers `404 route_not_found`
for a "System Users" caller and is counted in `resultsstore.read.refused`; the four remaining routes
behave as before; no allow rule, action or OpenAPI path names the arrived route; the contract jar
declares four operations.

**Acceptance Scenarios**:

1. **Given** the released contract `0.3.0` (its `team/rs` draft while this spec is built), **Then**
   `SharesApi` declares `pullOrSearchShares`, `getShare`, `getSharePayload`, `listHearingDayShares`
   and nothing else, and the service's `results-store-openapi.yaml` matches it path for path.
2. **Given** the payload operation, **Then** its `200` carries `ETag`, the five `Results-Store-*`
   identity and enrichment headers, `Cache-Control` and `Content-Length`, and no
   `Results-Store-Payload-Form`.
3. **Given** `ReadEndpoint`, **Then** its tags are `pull`, `search`, `share`, `payload` and
   `day_versions`; `resultsstore.read.requests` is never registered with `arrived_payload`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The working copy written to `payload_json` MUST be the text (or the enriched copy) with
  every `\u0000` escape and every unpaired surrogate escape removed, and nothing else changed. A
  surrogate pair escape, an escaped backslash followed by `u0000`, and every other escape stay.
  Removal is plain removal: no replacement character.
- **FR-002**: `payload_text`, `text_bytes` and `payload_sha256` MUST stay exactly as received.
- **FR-003**: The store MUST write the payload row in one insert with its working copy. There is no
  savepoint, no catch of SQLSTATE class 22, and no row written without a copy. A database refusal of
  the copy is a retryable store failure (001 FR-027), nothing more.
- **FR-004**: The enriched copy MUST go through the same removal and the same insert. The outcome
  `EnrichedCopyRefused`, the second store run with the arrived copy, and the enrichment skip reason
  `unstorable_results` (002 FR-019, FR-031) are removed.
- **FR-005**: The counter `resultsstore.intake.parsed.copy.skipped`, `IntakeObserver.parsedCopySkipped`
  and `StoreResult.Stored.parsedCopySkipped` are removed (001 FR-015 amended).
- **FR-006**: `GET /results-store/v1/shares/{shareId}/payload` MUST serve the working copy without
  `_metadata`, read from `payload_json` only; `PAYLOAD_SQL` reads no `payload_text`. The arrived-text
  form, `PayloadForm`, `EnvelopeMetadata` and the `Results-Store-Payload-Form` header are removed
  (003 FR-033, FR-035, 002 FR-041 amended). The `ETag` is unchanged: the SHA-256 of exactly the
  bytes served.
- **FR-007**: `GET /results-store/v1/shares/{shareId}/payload/arrived` MUST be removed: from the
  contract repository (`getShareArrivedPayload`), the service's OpenAPI document, `ApiRoute`,
  `ReadEndpoint`, the allow rule `results-store.get-share-arrived-payload`, `SharesController`,
  `ShareReadService`, `ShareQueries`, `JdbcShareQueries` and the audit payload marker set. A request
  for it is refused `404 route_not_found` and counted (003 FR-001, FR-038, FR-041, FR-046, FR-049,
  US8 amended).
- **FR-008**: The extraction sweep's read (`ShareStore.payloadForExtraction`) keeps its text fallback
  for a row stored without a copy before this spec. Nothing else reads the fallback.
- **FR-009**: Constitution 2.2.0 → 2.3.0 (MINOR). Principle II: the working copy is the text parsed,
  enriched, with the `\u0000` escape and unpaired surrogate escapes removed (the one exception to
  "nothing else is added, removed or changed"), so every stored share has a working copy; the read
  API serves the working copy without `_metadata`; the "from the text when the working copy is
  empty", text fallback and "on its own endpoint" clauses go. Principle V: the store's own code
  validates and refuses nothing; a text the database itself cannot hold as `jsonb` fails like any
  database error and ends on the dead-letter queue after the broker's attempts. Workflow gate 2 says
  the same.
- **FR-010**: Short *Amended by spec 005* notes, not rewrites, on: specs/001 `spec.md` FR-015,
  `research.md` R8, `contracts/metrics.md`; specs/002 `spec.md` FR-019, FR-031, FR-041,
  `data-model.md` invariants 2 and 4, `contracts/metrics.md`; specs/003 `spec.md` FR-033, FR-041,
  US8, `contracts/read-api.md` §4.4 and §4.6, `contracts/metrics.md`; `.claude/rules/design_rules.md`
  (read API table) and `.claude/rules/technical-rules.md` (the arrived-text bullet).
- **FR-011**: `scripts/container-smoke.sh` MUST stop polling the arrived text, check that
  `GET .../payload/arrived` is `404 route_not_found`, and keep the check that every payload row has a
  working copy.
- **FR-012**: The contract is released as `0.3.0` from `hmcts/api-cp-crime-results-store`
  (CHANGELOG *Removed*: the operation and the header), and the service pins `0.3.0` before its own
  release (`validateApiSpecVersions`).

### Key Entities

- **Working copy** (`hearing_share_payload.payload_json`): the text parsed, enriched at intake, with
  the two escapes removed; held for every share; served by the read API; read by the sweep.
- **Arrived text** (`payload_text`, `payload_sha256`): exactly as received; never served after this
  spec; kept for support and for the checksum.

## Success Criteria *(mandatory)*

- **SC-001**: After this spec no row of `hearing_share_payload` written by the service has
  `payload_json IS NULL` (the smoke's `bool_and(payload_json IS NOT NULL)` holds for every share,
  a `\u0000` share included).
- **SC-002**: `GET .../payload/arrived` is `404 route_not_found`; `OpenApiContractDriftTest` is
  green against the `0.3.0` jar; `every_api_route_should_have_a_drl_rule_and_a_controller_mapping`
  passes over four routes.
- **SC-003**: The change removes more lines than it adds in `src/main`.
- **SC-004**: The merge gate (`./gradlew build pmdMain pmdTest jacocoTestReport`) and the smoke are
  green; code-reviewer, qa and spec-validator pass.

## Decisions taken with the instruction

| Id | Question | Decision |
|---|---|---|
| D-NUMBER | A number beyond numeric's range still makes `jsonb` refuse the copy. Keep a narrow class 22 catch? | No. It fails like any database error (FR-003, Principle V sentence). Theoretical risk, not worth a second path |
| D-ENRICHED | Keep the enriched-copy refusal and the `unstorable_results` re-run? | No. The removal applies to the enriched copy too; there is nothing left to refuse (FR-004) |
| D-FORM-HEADER | Keep `Results-Store-Payload-Form` with one value? | No. Removed from the contract with the arrived operation (FR-006, FR-007) |
| D-SCHEMA | Add `NOT NULL` to `payload_json`? | No. No Flyway change; the sweep's text fallback stays for a pre-005 row (FR-008) |
| D-LONE-SURROGATE | Jackson writes a lone surrogate in a progression result as a raw UTF-16 unit, not an escape, so the strip cannot see it on the enriched copy; the JDBC encoder writes `?` in its place | Left as is: pre-existing, theoretical, `payload_text` unaffected. A raw-surrogate pass in the strip only if the guarantee must be literal |
| D-BUMP | MINOR or MAJOR? | MINOR, 2.3.0: the store's own code still refuses nothing; the database's limit is named, not a rule reversed |
