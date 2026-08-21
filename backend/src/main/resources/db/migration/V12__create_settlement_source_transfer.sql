CREATE TABLE settlement_source_transfer (
    settlement_id BIGINT NOT NULL,
    trip_id BIGINT NOT NULL,
    source_transfer_id BIGINT NOT NULL,
    source_transfer_version BIGINT NOT NULL,
    from_member_id BIGINT NOT NULL,
    to_member_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    PRIMARY KEY (settlement_id, source_transfer_id),
    CONSTRAINT fk_source_transfer_settlement FOREIGN KEY (settlement_id, trip_id)
        REFERENCES settlement(id, trip_id) ON DELETE CASCADE,
    CONSTRAINT fk_source_transfer_transfer FOREIGN KEY (source_transfer_id, trip_id)
        REFERENCES settlement_transfer(id, trip_id),
    CONSTRAINT fk_source_transfer_from_member FOREIGN KEY (from_member_id, trip_id)
        REFERENCES trip_member(id, trip_id),
    CONSTRAINT fk_source_transfer_to_member FOREIGN KEY (to_member_id, trip_id)
        REFERENCES trip_member(id, trip_id),
    CONSTRAINT ck_source_transfer_version CHECK (source_transfer_version >= 0),
    CONSTRAINT ck_source_transfer_amount CHECK (amount > 0),
    CONSTRAINT ck_source_transfer_members CHECK (from_member_id <> to_member_id),
    CONSTRAINT ck_source_transfer_status CHECK (status IN ('PAID', 'CONFIRMED'))
);

CREATE INDEX ix_source_transfer_trip ON settlement_source_transfer(trip_id, source_transfer_id);
