-- Receipt log: one row per share of a hearing day, identified by the hearing, the day and the time
-- the share was made. Nothing writes to it yet; the first feature spec may reshape it.
CREATE TABLE event_receipt (
    hearing_id  UUID        NOT NULL,
    hearing_day DATE        NOT NULL,
    shared_time TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT event_receipt_pk PRIMARY KEY (hearing_id, hearing_day, shared_time)
);
