# Implementation Plan: Payload Simplification

**Branch**: `005-payload-simplification` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/005-payload-simplification/spec.md`

## Summary

Two reductions. (1) Every share gets a working copy: the `\u0000` escape and unpaired surrogate
escapes are removed from the copy just before the one payload insert, so the NULL copy, the savepoint,
the class 22 handling, the `parsed.copy.skipped` counter, the enriched-copy refusal and the read API's
text fallback all go. (2) The arrived-text endpoint is withdrawn, contract repository first.

Both are removals. No new dependency, no migration, no new endpoint, no new metric.

## Technical Context

**Language/Version**: Java 25; Spring Boot 4.1; Gradle. **Storage**: PostgreSQL 16, no migration.
**Contract**: `uk.gov.hmcts.cp:api-cp-crime-results-store` pinned to the `team/rs` draft
`rs-8fb8ea6` while this spec is built (api commit `8fb8ea6`, branch `005-remove-arrived-payload`,
pushed to `team/rs` on 2026-10-07), then `0.3.0` once that commit is merged and released.
**Testing**: the existing suites; no new test class. **Constitution**: 2.2.0 in the tree; 2.3.0 at T007.

## Constitution Check

| Principle | Effect |
|---|---|
| I Immutable versions | Unchanged. The copy is written once, at insert; no update |
| II Payload is the source of truth | Amended (2.3.0): the copy is the text with the two escapes removed, held for every share; the read API serves it; the text fallback and the arrived endpoint go |
| III Indexed search | Unchanged. The sweep still reads the copy (its text fallback kept for a pre-005 row) |
| V Never refuse to store | Amended (2.3.0): the store's code refuses nothing; a text the database cannot hold as `jsonb` is a database failure, retried then dead-lettered (D-NUMBER) |
| VI Transaction order | Unchanged. One store transaction, receipt first, acknowledge after commit |
| VII Default deny | A route and its rule removed together; four routes left, each with its mapping, rule and OpenAPI entry |
| VIII Observability | Two meters removed (`parsed.copy.skipped`; `read.requests{endpoint=arrived_payload}`) and one reason (`unstorable_results`); nothing hidden |
| X TDD | Every task names its red tests first |
| XI No PII | The new invariant check compares `jsonb` values in Java and reports a share id only |

## Project Structure

No new package. Files removed: `domain/PayloadForm`, `domain/EnvelopeMetadata` and their tests.
Files changed: `persistence/NulSafety` (strip), `persistence/JdbcShareStore`, `persistence/JdbcShareQueries`,
`application/{IntakeService,ShareReadService,ShareQueries,StoreResult,IntakeObserver,ShareStore}`,
`domain/{StoredPayload,ReadEndpoint,ReadOutcome,EnrichmentSkip}`, `application/ServedPayload`,
`api/{SharesController,PayloadResponses,ReadApiExceptionHandler}`,
`filters/{ApiRoute,PayloadBodyFreeAuditPayloadGenerationService}`, `config/MicrometerIntakeObserver`,
`src/main/resources/results-store-openapi.yaml`, `src/main/resources/acl/results-store-rules.drl`,
`gradle/libs.versions.toml`, `scripts/container-smoke.sh`, the documents named in spec FR-009/FR-010.

## Phase plan

One phase, one run of the phase-gate workflow over T001–T007 (T008 after the api release). Order:
the write side first (T001–T003: no contract dependency, the 0.2.0 jar still pinned), then the
contract (T004: the pin, the yaml, the route, the controller and the enum in one commit, because the
jar loses `getShareArrivedPayload` and the override stops compiling), then the read-side cleanup
(T005: the form header, `PayloadForm` and `EnvelopeMetadata` can only go once the arrived route has),
then the smoke (T006) and the documents (T007).

Sequencing with the contract (research 003 R23 C4): the api change is on `team/rs`, so `ci-draft`
publishes `rs-8fb8ea6` to `hmcts-lib`; T004 pins it. The api pull request, its merge and the GitHub
Release `v0.3.0` are the orchestrator's steps; T008 bumps the pin to `0.3.0` and runs
`validateApiSpecVersions`. A release of the service cannot carry the draft.

## Risks

- A deployed row with `payload_json IS NULL` would answer `500` on `GET /payload` after T005. The
  orchestrator checks every environment before the service change is released (spec *Out of scope*).
- Intake extracts key details from the unstripped tree; a `\u0000` inside a key-detail field still
  fails as `UNSTORABLE_TEXT` while a later sweep re-extraction from the stripped copy succeeds.
  Harmless; left as is.
