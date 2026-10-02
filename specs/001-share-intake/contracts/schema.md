# Contract: database schema

The schema this feature creates is defined in full in [../data-model.md](../data-model.md):
the DDL of `V2__reshape_event_receipt.sql` and `V3__create_share_store.sql`, the columns that may
change after insert, the state machines and the validation rules.

Rules that bind every later spec:

1. **`V1__create_event_receipt.sql` is never edited.** Nor is any migration once it has run in a
   shared environment. Changes arrive as new, forward-only migrations (`V4__…` and later).
2. V2 refuses to run while V1's `event_receipt` holds a row.
3. The tables are this service's own. Other services read results through the read API
   (spec 003), never through the database.
4. Only the columns listed under "What may change after insert" in `data-model.md` are ever
   updated (constitution I; FR-044).
5. `FlywayMigrationIT` holds the schema to these rules (T002).
