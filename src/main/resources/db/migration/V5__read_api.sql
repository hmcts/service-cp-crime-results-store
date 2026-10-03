-- V5__read_api.sql (spec 003): what the read API needs from the schema.
--
-- 1. stored_at is read from the clock after the row's stored_seq is taken. V3 declares stored_at
--    (DEFAULT clock_timestamp()) before stored_seq (identity), so the default could read the clock
--    before the number was taken. Pull safety (specs/003-read-api/research.md R4) needs a share's
--    stored_at to be no earlier than the moment its number was taken. A BEFORE INSERT trigger runs
--    after the defaults and the identity value are set. The column default stays; the trigger
--    overrides any value an INSERT supplies. hearing_share_guard (V3) already refuses any UPDATE of
--    stored_at.
CREATE FUNCTION hearing_share_stored_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.stored_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER hearing_share_stored_at_tg
    BEFORE INSERT ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION hearing_share_stored_at();

-- 2. Pull for youth-relevant days: dayYouthSeen=notFalse, and =true written as
--    "day_youth_seen IS NOT FALSE AND day_youth_seen" so the planner can prove the predicate.
--    A range scan in stored_seq order that stops after limit + 1 rows.
--    day_youth_seen is rewritten by youth propagation, so membership changes are non-HOT updates;
--    the volume is low.
CREATE INDEX hearing_share_youth_feed_ix
    ON hearing_share (stored_seq)
    WHERE day_youth_seen IS NOT FALSE;

-- 3. Pull with courtCentreId: an exact match (FAILED rows, which have no court, are never returned),
--    so one range scan in stored_seq order serves it and stops after limit + 1 rows.
CREATE INDEX hearing_share_centre_feed_ix
    ON hearing_share (court_centre_id, stored_seq)
    WHERE court_centre_id IS NOT NULL;

-- 4. Search by court over a shared_at range, keyset on (shared_at, share_id), so no sort step. Both
--    search forms use it: the service turns London days into a [from, to) shared_at range.
--    Partial: FAILED rows have no court and never match court_centre_id = :courtCentreId.
CREATE INDEX hearing_share_centre_shared_at_ix
    ON hearing_share (court_centre_id, shared_at, share_id)
    WHERE court_centre_id IS NOT NULL;

COMMENT ON INDEX hearing_share_youth_feed_ix IS
    'Read API pull, dayYouthSeen=notFalse|true (spec 003)';
COMMENT ON INDEX hearing_share_centre_feed_ix IS
    'Read API pull by court centre, exact match (spec 003)';
COMMENT ON INDEX hearing_share_centre_shared_at_ix IS
    'Read API search by court centre over a shared_at range, both forms (spec 003)';
COMMENT ON TRIGGER hearing_share_stored_at_tg ON hearing_share IS
    'stored_at read after stored_seq is taken; pull safety (spec 003)';
