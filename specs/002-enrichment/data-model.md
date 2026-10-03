# Data model: Enrichment

**Feature**: `002-enrichment` | **Date**: 2026-10-03 | **Plan**: [plan.md](plan.md)

## No schema change

002 adds no table, column, index, constraint, trigger or migration. V1 to V4 are not edited. The two
columns it uses already exist in `V3__create_share_store.sql`:

| Table | Column | DDL (V3) | Used by 002 |
|---|---|---|---|
| `hearing_share` | `enrichment_applied` | `BOOLEAN NOT NULL DEFAULT FALSE` (`:41`) | bound at insert from the request |
| `hearing_share_payload` | `payload_json` | `JSONB NULL` (`:95`) | holds the working copy |

`hearing_share_guard` (`:135-146`) already lists `enrichment_applied` among a share's fixed columns,
and `hearing_share_payload_guard_tg` refuses any update of a payload row. 002 relies on both.

**Stale comments.** V3's inline comments now describe 001 only: `payload_json` "parsed copy; NULL when
jsonb cannot hold it (\u0000); unread in 001", and `enrichment_applied` "always false in 001". They are
left as they are: changing them would mean editing a migration that has run, or a `COMMENT ON` migration
for wording alone. This file and [spec.md](spec.md) carry the meaning from 002 on.

## What the payload columns mean from 002

| Column | Meaning |
|---|---|
| `hearing_share_payload.payload_text` | **Unchanged.** The message text exactly as it arrived, byte for byte, header (`_metadata`) included. |
| `hearing_share_payload.text_bytes` | **Unchanged.** UTF-8 length of `payload_text`. |
| `hearing_share.payload_sha256` | **Unchanged.** SHA-256 of `payload_text`, never of the working copy. |
| `hearing_share_payload.payload_json` | **The working copy.** The arrived text parsed, with progression's finalised results set into `hearing.courtApplications[i].judicialResults` for each application that received them, each result without `amendmentDate`, `amendmentReason` and `amendmentReasonId`. Nothing else differs in content. When nothing was added it is `CAST(payload_text AS jsonb)`, as in 001. Permanent: no longer a copy that may be dropped after NFT. The copy key details and the sweep read, and the copy the read API will serve (spec 003). NULL only when jsonb cannot hold even the arrived text (001 FR-015). |
| `hearing_share.enrichment_applied` | **True** only when at least one application received results and `payload_json` holds them. **False** when no application needed results, progression had nothing to add, enrichment was off, the share was already stored, or the fallback stored the arrived copy. Fixed at insert. |

jsonb keeps content, not key order, spacing or duplicate keys (last wins). So `payload_json::text`
differs from `payload_text` in form even when nothing was added. That is why `payload_sha256` is never
offered as the checksum of the served body (spec FR-041).

## Entities

### Share (`hearing_share`)

As in 001 ([../001-share-intake/data-model.md](../001-share-intake/data-model.md)), with
`enrichment_applied` now set at insert from the request (above). Still fixed at insert.

### Payload (`hearing_share_payload`)

One row per share: the arrived text, its size in bytes, and the working copy (above). Insert-only.

### Application lookup (not stored)

What one progression answer gave, held in memory for one attempt and reported to the metrics only:

| Field | Values |
|---|---|
| application id | a canonical UUID read from the share; logged, never a metric tag |
| outcome | `enriched`, `not_found`, `not_finalised`, `no_results`, `invalid_id` (no call), or a failure cause (`progression_*`) |
| duration | the call's time, for the lookup timer |

Nothing about a lookup is written to the database. The receipt's attempt count is the only stored
trace of a failed one.

## What may change after insert

Unchanged from 001 (FR-044). 002 adds no updatable column. `enrichment_applied` and the payload row
are never updated, so a share stored un-enriched stays un-enriched.

## Rows stored before 002

Shares stored by 001 code have `payload_json` as the un-enriched cast of the arrived text and
`enrichment_applied = false`. They keep both for ever:

- nothing looks them up again or re-enriches them (spec FR-034);
- the sweep reads their `payload_json` and gets the same key details as before;
- `EXTRACTOR_VERSION` does not change, so the sweep has no reason to re-read them.

No environment outside tests holds 001 rows: the store has no deploy values yet. A backfill, if ever
wanted, would need a migration that relaxes `hearing_share_guard` and the payload guard; it is not
planned.

## Invariants

1. `payload_text` and `payload_sha256` describe the arrived message, byte for byte (SC-006).
2. `enrichment_applied = true` implies `payload_json IS NOT NULL` (SC-007).
3. `enrichment_applied = true` implies at least one `hearing.courtApplications[]` element of
   `payload_json` has a non-empty `judicialResults` that the arrived text did not have.
4. When `enrichment_applied = false` and `payload_json IS NOT NULL`, `payload_json` equals
   `CAST(payload_text AS jsonb)`.
5. No result in an added `judicialResults` array has `amendmentDate`, `amendmentReason` or
   `amendmentReasonId` at its top level.
6. Apart from added or filled `judicialResults` arrays of court applications, `payload_json` has the
   same content as `CAST(payload_text AS jsonb)`.
7. A share is never stored half-enriched: either every lookup it needed answered, or nothing was
   stored (FR-020).

Invariants 2 and 4 can be checked in SQL:

```sql
-- 2: must return 0
SELECT count(*) FROM hearing_share s JOIN hearing_share_payload p USING (share_id)
 WHERE s.enrichment_applied AND p.payload_json IS NULL;
-- 4: must return 0
SELECT count(*) FROM hearing_share s JOIN hearing_share_payload p USING (share_id)
 WHERE NOT s.enrichment_applied AND p.payload_json IS NOT NULL
   AND p.payload_json <> CAST(p.payload_text AS jsonb);
```

## Validation rules (from the FRs)

| Rule | FR | Where |
|---|---|---|
| Look up only `hearing.courtApplications[]` elements whose top-level `judicialResults` is missing, `null` or `[]` | FR-002, FR-003 | `ApplicationResultsEnricher` scan |
| An application id must be a canonical UUID, else skipped and counted `invalid_id` | FR-014 | scan |
| Copy only `judicialResults`, only from a `FINALISED` answer with a non-empty array | FR-010, FR-011 | enricher |
| Remove only the three amendment fields, only at a result's top level; copy non-object elements unchanged | FR-010, FR-014 | enricher |
| An added key goes last; a replaced `null` or `[]` keeps its place | FR-012 | `ObjectNode.set` |
| Decimals keep value and written precision | FR-015 | reader features (research R9) |
| `enrichment_applied` bound at insert, from what is stored | FR-018 | `JdbcShareStore` |
| If the enriched copy cannot be held, store the arrived copy with the flag false | FR-019 | fallback (research R18) |
