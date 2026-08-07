CREATE TABLE expense_receipt (
    id UUID PRIMARY KEY,
    expense_id BIGINT NOT NULL,
    trip_id BIGINT NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    content_type VARCHAR(40) NOT NULL,
    byte_size BIGINT NOT NULL,
    upload_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    uploaded_at TIMESTAMPTZ,
    CONSTRAINT fk_receipt_expense
        FOREIGN KEY (expense_id, trip_id) REFERENCES expense(id, trip_id) ON DELETE CASCADE,
    CONSTRAINT ck_receipt_content_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_receipt_byte_size
        CHECK (byte_size BETWEEN 1 AND 10485760),
    CONSTRAINT ck_receipt_upload_status
        CHECK (upload_status IN ('PENDING', 'UPLOADED', 'FAILED')),
    CONSTRAINT ck_receipt_uploaded_at CHECK (
        (upload_status = 'UPLOADED' AND uploaded_at IS NOT NULL)
        OR (upload_status <> 'UPLOADED' AND uploaded_at IS NULL)
    )
);

CREATE INDEX ix_expense_receipt_expense ON expense_receipt(trip_id, expense_id);
