-- V1 created event_receipt keyed by the share's identity, and nothing has ever written to it. It
-- cannot hold a message without an identity, and it refuses a redelivery. V1 is not edited; this
-- migration replaces the table, and refuses to run if the table holds any row.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM event_receipt) THEN
        RAISE EXCEPTION 'event_receipt is not empty; V2 refuses to reshape it';
    END IF;
END $$;

DROP TABLE event_receipt;

CREATE TABLE event_receipt (
    message_id        TEXT        NOT NULL,   -- JMSMessageID as given (e.g. 'ID:...'), or 'sha256:<hex>' when absent
    status            TEXT        NOT NULL,
    hearing_id        UUID        NULL,       -- identity is nullable: a non-share may carry none or part of it
    hearing_day       DATE        NULL,
    shared_at         TIMESTAMPTZ NULL,       -- V1's shared_time, renamed to match hearing_share
    attempts          INTEGER     NOT NULL DEFAULT 1,
    delivery_count    INTEGER     NULL,       -- JMSXDeliveryCount last seen
    first_received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_received_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    settled_at        TIMESTAMPTZ NULL,       -- set on the move to an end state
    reason            TEXT        NULL,       -- bounded code, never exception or payload text
    message_text      TEXT        NULL,       -- the text as received; non-shares only
    share_id          UUID        NULL,       -- the stored share (STORED) or the existing one (DUPLICATE)
    CONSTRAINT event_receipt_pk PRIMARY KEY (message_id),
    CONSTRAINT event_receipt_status_ck
        CHECK (status IN ('RECEIVED', 'STORED', 'DUPLICATE', 'UNREADABLE', 'NO_IDENTITY')),
    CONSTRAINT event_receipt_attempts_ck CHECK (attempts >= 1),
    CONSTRAINT event_receipt_delivery_count_ck CHECK (delivery_count IS NULL OR delivery_count >= 0),
    CONSTRAINT event_receipt_settled_ck CHECK ((status = 'RECEIVED') = (settled_at IS NULL)),
    CONSTRAINT event_receipt_reason_length_ck CHECK (reason IS NULL OR char_length(reason) <= 120),
    CONSTRAINT event_receipt_non_share_reason_ck
        CHECK (status NOT IN ('UNREADABLE', 'NO_IDENTITY') OR reason IS NOT NULL),
    CONSTRAINT event_receipt_text_only_for_non_share_ck
        CHECK (message_text IS NULL OR status IN ('UNREADABLE', 'NO_IDENTITY')),
    CONSTRAINT event_receipt_share_id_ck
        CHECK ((status IN ('STORED', 'DUPLICATE')) = (share_id IS NOT NULL)),
    CONSTRAINT event_receipt_identity_whole_ck
        CHECK (status IN ('UNREADABLE', 'NO_IDENTITY')
               OR (hearing_id IS NOT NULL AND hearing_day IS NOT NULL AND shared_at IS NOT NULL))
);

-- R1: receipts still RECEIVED. Partial, so it stays small.
CREATE INDEX event_receipt_open_ix ON event_receipt (first_received_at) WHERE status = 'RECEIVED';
-- Receipts of one hearing day (spec 004's receipts endpoint; reconciliation by identity).
CREATE INDEX event_receipt_hearing_day_ix ON event_receipt (hearing_id, hearing_day);

-- FR-044: the key, the identity and the first arrival never change; the end-state columns change
-- only on the move out of RECEIVED; after that only the delivery details (attempts,
-- last_received_at, delivery_count) do. The CHECKs above hold each row's shape; this holds its history.
CREATE FUNCTION event_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.message_id, NEW.hearing_id, NEW.hearing_day, NEW.shared_at, NEW.first_received_at)
            IS DISTINCT FROM
       (OLD.message_id, OLD.hearing_id, OLD.hearing_day, OLD.shared_at, OLD.first_received_at) THEN
        RAISE EXCEPTION 'event_receipt_fixed_columns_guard: a receipt''s key, identity and first arrival never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'RECEIVED'
       AND (NEW.status, NEW.reason, NEW.message_text, NEW.share_id, NEW.settled_at)
            IS DISTINCT FROM
           (OLD.status, OLD.reason, OLD.message_text, OLD.share_id, OLD.settled_at) THEN
        RAISE EXCEPTION 'event_receipt_settled_guard: a settled receipt keeps its end state'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER event_receipt_guard_tg
    BEFORE UPDATE ON event_receipt
    FOR EACH ROW EXECUTE FUNCTION event_receipt_guard();
