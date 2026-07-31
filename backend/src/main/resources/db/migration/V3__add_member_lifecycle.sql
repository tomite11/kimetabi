ALTER TABLE trip_member
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_trip_member_version CHECK (version >= 0);

CREATE TABLE trip_member_unsettled_balance (
    trip_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    balance_yen BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (trip_id, member_id),
    CONSTRAINT fk_member_unsettled_balance_member
        FOREIGN KEY (member_id, trip_id)
        REFERENCES trip_member(id, trip_id),
    CONSTRAINT ck_member_unsettled_balance_amount
        CHECK (balance_yen <> -9223372036854775808)
);

COMMENT ON TABLE trip_member_unsettled_balance IS
    'Current unsettled balance projection maintained by expense and settlement writes';

