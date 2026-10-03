# Contract: database schema (002 delta)

**No schema change.** 002 adds no migration. `V1__create_event_receipt.sql` to
`V4__projection_tried_at.sql` are not edited, and every rule in
[../../001-share-intake/contracts/schema.md](../../001-share-intake/contracts/schema.md) still binds.

What changes is the meaning of two existing columns, set out in full in
[../data-model.md](../data-model.md):

| Column | 001 | From 002 |
|---|---|---|
| `hearing_share_payload.payload_json` | parsed copy of the arrived text; unread; could be dropped after NFT | the working copy: the arrived text parsed, with finalised application results added; permanent; read by the extraction and the sweep; served by the read API (spec 003) |
| `hearing_share.enrichment_applied` | always false | true when at least one application received results; fixed at insert |

Unchanged: `payload_text` (the message exactly as it arrived), `text_bytes`, `payload_sha256` (over
`payload_text`).

Rules added by 002:

1. `enrichment_applied` is bound in the share insert and never updated (`hearing_share_guard`).
2. `enrichment_applied = true` only with `payload_json` NOT NULL.
3. The V3 inline comments on both columns (`:40`, `:95`) describe 001 and are left stale on purpose;
   no `COMMENT ON` migration is added for wording alone.
4. Rows stored before 002 are never re-enriched or rewritten.
5. Forward constraint for spec 003 (FR-041): the read API serves `payload_json` (as `jsonb::text`, or
   `payload_text` when `payload_json` is NULL), with an `ETag` over the exact bytes written to the
   response. `payload_sha256` is never offered as the served body's checksum.
