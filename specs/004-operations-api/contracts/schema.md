# Contract: database schema (004 delta)

Adds to [../../001-share-intake/contracts/schema.md](../../001-share-intake/contracts/schema.md),
[../../002-enrichment/contracts/schema.md](../../002-enrichment/contracts/schema.md) and
[../../003-read-api/contracts/schema.md](../../003-read-api/contracts/schema.md). Every rule there still
binds, except where rule 1 below changes one on purpose. The DDL is in full in
[../data-model.md](../data-model.md).

## Migration

| File | Adds |
|---|---|
| `V6__operations.sql` | tables `extraction_rerun`, `extraction_rerun_item`, `sweep_round`; their checks and indexes; functions and triggers `extraction_rerun_guard`/`_tg`, `extraction_rerun_item_guard`/`_tg`; `hearing_share_guard()` replaced (`CREATE OR REPLACE`; trigger `hearing_share_guard_tg` unchanged); indexes `event_receipt_first_received_ix`, `event_receipt_stale_ix`, `hearing_share_stored_at_ix` |

V1 to V5 are not edited.

## Rules added or changed by 004

1. **Changed.** An `OK` share's key-detail and `projection_*` columns may change, but only while a
   pending `extraction_rerun_item` names the share, only to stay `OK`, never lowering a `true`
   `any_subject_is_youth`. On every row, any change to those columns raises `projection_attempts`,
   never lowers `projection_version` and never moves `projected_at` back. V3's "OK is final in 001" is
   superseded; V3 is not edited.
2. Each guard branch has its own name: `hearing_share_fixed_columns_guard` (V3, unchanged),
   `hearing_share_projection_version_guard`, `hearing_share_projection_guard`,
   `hearing_share_youth_guard`, `hearing_share_rerun_guard`, `extraction_rerun_fixed_columns_guard`,
   `extraction_rerun_done_guard`, `extraction_rerun_item_fixed_columns_guard`,
   `extraction_rerun_item_done_guard`. Each raises `restrict_violation` (23001).
3. Only the sweep writes an `OK` share's key details, and only after the item is locked and pending,
   writing the share before marking the item done in the same transaction.
4. A share has at most one pending item (`extraction_rerun_item_one_pending_ux`).
5. A request row is written after its items, in the same transaction; the items' foreign key to it is
   checked at commit. Its counts are fixed; only `status` (`OPEN` to `DONE`) and `finished_at` change,
   once.
6. The rerun tables have no delete guard: a later retention or erasure spec removes their rows, items
   before requests and before any share they name (both foreign keys).
7. `extraction_rerun.reason` and `requested_by` are never selected by an operations read and never
   logged.
8. Operations reads are autocommit and read-only, never name `hearing_share_payload` or `message_text`,
   and name their columns.
9. `sweep_round` is one row per pod, written only by that pod's sweep, with database-clock times; rows
   older than `pod-recent` are deleted by the upsert.
10. Each index is justified by the query it serves; `OperationsQueriesPlanIT` ties them together.
11. `FlywayMigrationIT` and `OperationsSchemaIT` hold the schema to these rules: the V6 objects exist
    with these names, each guard branch refuses by name, V3's fixed-column refusals still hold, and V1
    to V5 are unchanged.
