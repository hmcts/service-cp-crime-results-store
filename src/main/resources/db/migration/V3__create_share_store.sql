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
    CONSTRAINT hearing_share_day_fk FOREIGN KEY (hearing_id, hearing_day)
        REFERENCES hearing_day_head (hearing_id, hearing_day),
    CONSTRAINT hearing_share_predecessor_fk FOREIGN KEY (predecessor_share_id)
        REFERENCES hearing_share (share_id),
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

ALTER TABLE hearing_day_head
    ADD CONSTRAINT hearing_day_head_latest_fk FOREIGN KEY (latest_share_id)
        REFERENCES hearing_share (share_id);

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
