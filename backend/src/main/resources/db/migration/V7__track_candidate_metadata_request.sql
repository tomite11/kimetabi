ALTER TABLE candidate
    ADD COLUMN metadata_request_event_id UUID;

ALTER TABLE candidate
    ADD CONSTRAINT fk_candidate_metadata_request_event
    FOREIGN KEY (metadata_request_event_id) REFERENCES outbox_event(id);

UPDATE candidate
SET title_edited_at = created_at
WHERE title IS NOT NULL AND title_edited_at IS NULL;

CREATE INDEX ix_candidate_metadata_request_event
    ON candidate(metadata_request_event_id)
    WHERE metadata_request_event_id IS NOT NULL;
