CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trip(id),
    trip_revision BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id BIGINT NOT NULL,
    resource_version BIGINT,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_outbox_trip_revision
    ON outbox_event(trip_id, trip_revision);

CREATE INDEX ix_outbox_unpublished
    ON outbox_event(created_at)
    WHERE published_at IS NULL;
