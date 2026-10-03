# Contract: database schema (003 delta)

Adds to [../../001-share-intake/contracts/schema.md](../../001-share-intake/contracts/schema.md) and
[../../002-enrichment/contracts/schema.md](../../002-enrichment/contracts/schema.md). Every rule there
still binds. The DDL is in full in [../data-model.md](../data-model.md).

## Migration

| File | Adds |
|---|---|
| `V5__read_api.sql` | function `hearing_share_stored_at()` and trigger `hearing_share_stored_at_tg` (`BEFORE INSERT ON hearing_share`, sets `stored_at := clock_timestamp()`); index `hearing_share_youth_feed_ix`; index `hearing_share_centre_day_ix`; a comment on each |

No table, column, constraint or guard changes. V1 to V4 are not edited.

## Rules added by 003

1. `hearing_share.stored_at` is set by `hearing_share_stored_at_tg`, after the row's `stored_seq` is
   taken. No insert may rely on supplying its own value; the trigger overrides it.
2. The identity sequence behind `stored_seq` keeps cache 1, so numbers are handed out in time order
   across sessions. A later migration MUST NOT raise it: pull safety (research R4) depends on it.
3. Read queries are autocommit and read-only. Only the payload queries name `hearing_share_payload`
   (Principle III).
4. Each index is justified by the read query it serves; `ReadQueriesPlanIT` ties them together. An index
   with no query is not added.
5. Spec 004 adds its own migration (V6) and its own indexes; it does not change V5.
6. `FlywayMigrationIT` holds the schema to these rules: the V5 objects exist with these names, the
   trigger fires on insert, the sequence cache is 1, and V1 to V4 are unchanged.
