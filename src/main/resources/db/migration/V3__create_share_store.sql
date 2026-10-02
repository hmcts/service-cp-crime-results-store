CREATE TABLE hearing_day_head (
    hearing_id      UUID        NOT NULL,
    hearing_day     DATE        NOT NULL,
    latest_share_id UUID        NULL,         -- NULL only inside the transaction that creates the row
    share_count     INTEGER     NOT NULL DEFAULT 0,
    youth_seen      BOOLEAN     NULL,         -- three values: TRUE (sticky), NULL (unknown), FALSE
    first_stored_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT hearing_day_head_pk PRIMARY KEY (hearing_id, hearing_day),
    CONSTRAINT hearing_day_head_count_ck CHECK (share_count >= 0),
    -- latest and count move together, in one UPDATE
    CONSTRAINT hearing_day_head_latest_ck CHECK ((latest_share_id IS NULL) = (share_count = 0))
);

CREATE TABLE hearing_share (
    share_id             UUID        NOT NULL,   -- UUID v5 over 'hearingId|hearingDay|sharedTime' as sent
    hearing_id           UUID        NOT NULL,
    hearing_day          DATE        NOT NULL,
    shared_at            TIMESTAMPTZ NOT NULL,
    shared_day_london    DATE        NOT NULL,   -- worked out in Java (Europe/London)
    shared_day_utc       DATE        NOT NULL,
    stored_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    stored_seq           BIGINT      GENERATED ALWAYS AS IDENTITY,
    payload_sha256       CHAR(64)    NOT NULL,
    -- key details: all nullable; empty while extraction has FAILED
    is_reshare           BOOLEAN     NULL,
    court_centre_id      UUID        NULL,
    court_room_id        UUID        NULL,
    lja_code             TEXT        NULL,
    jurisdiction_type    TEXT        NULL,       -- recorded as stated; no CHECK on its values
    is_sjp               BOOLEAN     NULL,
    is_group_proceedings BOOLEAN     NULL,
    youth_court_id       UUID        NULL,
    any_subject_is_youth BOOLEAN     NULL,
    -- the day's youth flag, propagated under the lock
    day_youth_seen       BOOLEAN     NULL,
    -- chain
    is_latest            BOOLEAN     NOT NULL,
    predecessor_share_id UUID        NULL,
    arrived_out_of_order BOOLEAN     NOT NULL,
    -- enrichment (spec 002); always false in 001
    enrichment_applied   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- extraction
    projection_status    TEXT        NOT NULL,
    projection_reason    TEXT        NULL,
    projection_version   INTEGER     NOT NULL,
    projection_attempts  INTEGER     NOT NULL DEFAULT 1,
    projected_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- retention (open); never set in 001
    expires_at           TIMESTAMPTZ NULL,
    CONSTRAINT hearing_share_pk PRIMARY KEY (share_id),
    CONSTRAINT hearing_share_identity_uk UNIQUE (hearing_id, hearing_day, shared_at),
    CONSTRAINT hearing_share_stored_seq_uk UNIQUE (stored_seq),
    -- the target of the same-day foreign keys below
    CONSTRAINT hearing_share_day_share_uk UNIQUE (hearing_id, hearing_day, share_id),
    CONSTRAINT hearing_share_day_fk FOREIGN KEY (hearing_id, hearing_day)
        REFERENCES hearing_day_head (hearing_id, hearing_day),
    -- the predecessor is a share of the same day (and an earlier one: hearing_share_predecessor_guard)
    CONSTRAINT hearing_share_predecessor_fk FOREIGN KEY (hearing_id, hearing_day, predecessor_share_id)
        REFERENCES hearing_share (hearing_id, hearing_day, share_id),
    CONSTRAINT hearing_share_not_own_predecessor_ck
        CHECK (predecessor_share_id IS NULL OR predecessor_share_id <> share_id),
    CONSTRAINT hearing_share_sha256_ck CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT hearing_share_projection_status_ck CHECK (projection_status IN ('OK', 'FAILED')),
    CONSTRAINT hearing_share_projection_reason_ck
        CHECK ((projection_status = 'FAILED') = (projection_reason IS NOT NULL)),
    CONSTRAINT hearing_share_projection_reason_length_ck
        CHECK (projection_reason IS NULL OR char_length(projection_reason) <= 120),
    CONSTRAINT hearing_share_projection_version_ck CHECK (projection_version >= 1),
    CONSTRAINT hearing_share_projection_attempts_ck CHECK (projection_attempts >= 1),
    CONSTRAINT hearing_share_failed_is_empty_ck
        CHECK (projection_status = 'OK'
               OR (is_reshare IS NULL AND court_centre_id IS NULL AND court_room_id IS NULL
                   AND lja_code IS NULL AND jurisdiction_type IS NULL AND is_sjp IS NULL
                   AND is_group_proceedings IS NULL AND youth_court_id IS NULL
                   AND any_subject_is_youth IS NULL)),
    CONSTRAINT hearing_share_expires_unset_ck CHECK (expires_at IS NULL)
);

-- the latest share is a share of the same day (and is_latest: hearing_day_head_latest_check)
ALTER TABLE hearing_day_head
    ADD CONSTRAINT hearing_day_head_latest_fk FOREIGN KEY (hearing_id, hearing_day, latest_share_id)
        REFERENCES hearing_share (hearing_id, hearing_day, share_id);

-- At most one latest share per hearing day. Not deferrable: clear the old latest first.
CREATE UNIQUE INDEX hearing_share_one_latest_ux
    ON hearing_share (hearing_id, hearing_day) WHERE is_latest;
-- The sweep's scan.
CREATE INDEX hearing_share_failed_ix
    ON hearing_share (stored_seq) WHERE projection_status = 'FAILED';

CREATE TABLE hearing_share_payload (
    share_id     UUID    NOT NULL,
    payload_text TEXT    NOT NULL,   -- the message text exactly as received (envelope with _metadata)
    text_bytes   INTEGER NOT NULL,   -- UTF-8 length of payload_text
    payload_json JSONB   NULL,       -- parsed copy; NULL when jsonb cannot hold it (\u0000); unread in 001
    CONSTRAINT hearing_share_payload_pk PRIMARY KEY (share_id),
    CONSTRAINT hearing_share_payload_share_fk FOREIGN KEY (share_id) REFERENCES hearing_share (share_id),
    CONSTRAINT hearing_share_payload_bytes_ck CHECK (text_bytes = octet_length(payload_text))
);

CREATE TABLE share_defendant (
    share_id            UUID NOT NULL,
    case_id             UUID NOT NULL,   -- hearing.prosecutionCases[].id
    defendant_id        UUID NOT NULL,   -- .defendants[].id
    master_defendant_id UUID NULL,       -- .defendants[].masterDefendantId
    CONSTRAINT share_defendant_pk PRIMARY KEY (share_id, case_id, defendant_id),
    CONSTRAINT share_defendant_share_fk FOREIGN KEY (share_id) REFERENCES hearing_share (share_id)
);

-- FR-044: what may change after insert. Every guard raises restrict_violation (23001) naming
-- itself; the CHECKs above hold each row's shape, these hold its history.

-- Refuses every row of the operation it is attached to: <table>_<update|delete>_guard.
CREATE FUNCTION refuse_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%_%_guard: % rows are never changed by %', TG_TABLE_NAME, lower(TG_OP), TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

-- The day row: only latest_share_id, share_count and youth_seen change.
CREATE FUNCTION hearing_day_head_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.hearing_id, NEW.hearing_day, NEW.first_stored_at)
            IS DISTINCT FROM (OLD.hearing_id, OLD.hearing_day, OLD.first_stored_at) THEN
        RAISE EXCEPTION 'hearing_day_head_fixed_columns_guard: a day row''s key and first store never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

-- The share: its identity, shared days, store clocks, checksum, arrival flag and enrichment flag never
-- change (spec Key Entities: arrived_out_of_order is fixed at insert).
-- Defence in depth for columns no code path may change; which other columns change, and when, is
-- the application's rule under the hearing-day lock (the chain, the day's youth flag; the key details
-- and projection_* by the sweep, which re-extracts on a version bump).
CREATE FUNCTION hearing_share_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.share_id, NEW.hearing_id, NEW.hearing_day, NEW.shared_at, NEW.shared_day_london,
        NEW.shared_day_utc, NEW.stored_at, NEW.stored_seq, NEW.payload_sha256, NEW.arrived_out_of_order,
        NEW.enrichment_applied)
            IS DISTINCT FROM
       (OLD.share_id, OLD.hearing_id, OLD.hearing_day, OLD.shared_at, OLD.shared_day_london,
        OLD.shared_day_utc, OLD.stored_at, OLD.stored_seq, OLD.payload_sha256, OLD.arrived_out_of_order,
        OLD.enrichment_applied) THEN
        RAISE EXCEPTION 'hearing_share_fixed_columns_guard: a share''s facts never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

-- The predecessor was shared earlier, so the chain can never loop.
CREATE FUNCTION hearing_share_predecessor_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.predecessor_share_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM hearing_share p
                   WHERE p.share_id = NEW.predecessor_share_id AND p.shared_at >= NEW.shared_at) THEN
        RAISE EXCEPTION 'hearing_share_predecessor_earlier_guard: a predecessor is shared before its successor'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- Both ways at commit: the share a day row names as latest has is_latest, and a day with any
-- share names its latest (so share_count >= 1, hearing_day_head_latest_ck). Checked at commit,
-- because the store transaction writes the share before it moves the day row on, and clears the
-- old latest before it sets the new one. The same-day rule is hearing_day_head_latest_fk.
CREATE FUNCTION hearing_day_head_latest_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM hearing_day_head h
               JOIN hearing_share s ON s.share_id = h.latest_share_id
               WHERE h.hearing_id = NEW.hearing_id AND h.hearing_day = NEW.hearing_day AND NOT s.is_latest) THEN
        RAISE EXCEPTION 'hearing_day_head_latest_is_latest_guard: a day row names a share that is not latest'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM hearing_share s
               WHERE s.hearing_id = NEW.hearing_id AND s.hearing_day = NEW.hearing_day)
       AND NOT EXISTS (SELECT 1 FROM hearing_day_head h
                       WHERE h.hearing_id = NEW.hearing_id AND h.hearing_day = NEW.hearing_day
                         AND h.latest_share_id IS NOT NULL AND h.share_count >= 1) THEN
        RAISE EXCEPTION 'hearing_day_head_has_latest_guard: a day with shares names its latest share'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER hearing_day_head_guard_tg
    BEFORE UPDATE ON hearing_day_head
    FOR EACH ROW EXECUTE FUNCTION hearing_day_head_guard();
CREATE TRIGGER hearing_day_head_delete_guard_tg
    BEFORE DELETE ON hearing_day_head
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
CREATE CONSTRAINT TRIGGER hearing_day_head_latest_check_tg
    AFTER INSERT OR UPDATE OF latest_share_id, share_count ON hearing_day_head
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION hearing_day_head_latest_check();

CREATE TRIGGER hearing_share_guard_tg
    BEFORE UPDATE ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION hearing_share_guard();
CREATE TRIGGER hearing_share_predecessor_guard_tg
    BEFORE INSERT OR UPDATE OF predecessor_share_id ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION hearing_share_predecessor_guard();
CREATE TRIGGER hearing_share_delete_guard_tg
    BEFORE DELETE ON hearing_share
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
CREATE CONSTRAINT TRIGGER hearing_share_latest_check_tg
    AFTER INSERT OR UPDATE OF is_latest ON hearing_share
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION hearing_day_head_latest_check();

-- The payload is insert-only. share_defendant has no guard: the sweep replaces a share's rows when
-- it re-extracts, and their immutability otherwise is the application's, under the hearing-day lock.
CREATE TRIGGER hearing_share_payload_guard_tg
    BEFORE UPDATE OR DELETE ON hearing_share_payload
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();
