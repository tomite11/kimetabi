CREATE TABLE repository_probe (
    id integer PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
