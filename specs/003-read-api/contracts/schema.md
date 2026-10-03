# Contract: database schema (003 delta)

Adds to [../../001-share-intake/contracts/schema.md](../../001-share-intake/contracts/schema.md) and
[../../002-enrichment/contracts/schema.md](../../002-enrichment/contracts/schema.md). Every rule there
still binds. The DDL is in full in [../data-model.md](../data-model.md). This file is the database only: the
API's response schemas are in the contract jar from `hmcts/api-cp-crime-results-store` (research R23),
and no column is named after them.

## Migration

| File | Adds |
|---|---|
| `V5__read_api.sql` | function `hearing_share_stored_at()` and trigger `hearing_share_stored_at_tg` (`BEFORE INSERT ON hearing_share`, sets `stored_at := clock_timestamp()`); index `hearing_share_youth_feed_ix (stored_seq) WHERE day_youth_seen IS NOT FALSE`; index `hearing_share_centre_feed_ix (court_centre_id, stored_seq) WHERE court_centre_id IS NOT NULL`; index `hearing_share_centre_shared_at_ix (court_centre_id, shared_at, share_id) WHERE court_centre_id IS NOT NULL`; a comment on each |

No table, column, constraint or guard changes. V1 to V4 are not edited.

## Rules added by 003

1. `hearing_share.stored_at` is set by `hearing_share_stored_at_tg`, after the row's `stored_seq` is
   taken. No insert may rely on supplying its own value; the trigger overrides it.
2. The identity sequence behind `stored_seq` keeps cache 1, so numbers are handed out in time order
   across sessions. A later migration MUST NOT raise it: pull safety (research R4) depends on it.
3. Read queries are autocommit and read-only. Only the payload queries name `hearing_share_payload`
   (Principle III). The working copy is read as `(payload_json - '_metadata')::text`; no read query
   returns `_metadata` to a caller, and the text form is stripped in the service before it is served (E8).
   Nothing stored changes: `payload_text` and `payload_json` keep `_metadata`, and `payload_sha256` stays
   the checksum of the text as it arrived; it is never served and never an `ETag`.
4. Each index is justified by the read query it serves; `ReadQueriesPlanIT` ties them together. An index
   with no query is not added. Youth pull → `hearing_share_youth_feed_ix`; court pull →
   `hearing_share_centre_feed_ix`; both search forms → `hearing_share_centre_shared_at_ix`.
5. Spec 004 adds its own migration (V6) and its own indexes; it does not change V5.
6. `FlywayMigrationIT` holds the schema to these rules: the V5 objects (one trigger, three indexes) exist
   with these names and column orders, the
   trigger fires on insert, the sequence cache is 1, and V1 to V4 are unchanged.
7. Every connection the pool opens starts with `statement_timeout` equal to
   `resultsstore.intake.store.statement-timeout` (10 s by default; contracts/configuration.md). A
   migration that needs longer sets `SET LOCAL statement_timeout` inside its own transaction.
