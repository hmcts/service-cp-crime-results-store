# Contract: database schema

The schema this feature creates is defined in full in [../data-model.md](../data-model.md):
the DDL of `V2__reshape_event_receipt.sql`, `V3__create_share_store.sql` and
`V4__projection_tried_at.sql` (the sweep's `hearing_share.projection_tried_at` and its index), the
columns that may change after insert, the state machines and the validation rules.

Rules that bind every later spec:

1. **`V1__create_event_receipt.sql` is never edited.** Nor is any migration once it has run in a
   shared environment. Changes arrive as new, forward-only migrations (`V5__…` and later).
2. V2 refuses to run while V1's `event_receipt` holds a row.
3. The tables are this service's own. Other services read results through the read API
   (spec 003), never through the database.
4. Only the columns listed under "What may change after insert" in `data-model.md` are ever
   updated (constitution I; FR-044).
5. `FlywayMigrationIT` holds the schema to these rules (T002).

*Amended by spec 002* ([contracts/schema.md](../../002-enrichment/contracts/schema.md)): `hearing_share_payload.payload_json` is the working copy (arrived text parsed, plus finalised application results added at intake), read by extraction and the sweep; no migration changes it, and `payload_text` stays the text as received.
