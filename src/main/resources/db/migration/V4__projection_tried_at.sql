-- When the extraction sweep last tried a FAILED share (spec 001, T012). Set by the sweep only, in its
-- own short transaction after each attempt, whatever the outcome: fixed (the row is then OK), failed
-- again (which also records the new reason, version and attempts) or an operational error (a read or
-- write that failed, which changes nothing else). The sweep takes rows never tried first, then the
-- longest since tried, so a row that keeps failing operationally rotates behind the others instead of
-- holding the head of every batch.
--
-- hearing_share_projection_guard (V3) does not list this column, so it is not held to the guard's
-- FAILED-only rule: the stamp follows an OK write as well (FR-044).
ALTER TABLE hearing_share ADD COLUMN projection_tried_at TIMESTAMPTZ NULL;

-- The sweep's scan, in the sweep's order. Replaces hearing_share_failed_ix (stored_seq alone), which
-- served only the sweep.
CREATE INDEX hearing_share_sweep_ix
    ON hearing_share (projection_tried_at NULLS FIRST, stored_seq) WHERE projection_status = 'FAILED';
DROP INDEX hearing_share_failed_ix;
