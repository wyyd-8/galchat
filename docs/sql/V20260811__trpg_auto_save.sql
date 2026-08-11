CREATE TABLE trpg_auto_save (
    conversation_id BIGINT PRIMARY KEY,
    saved_at TIMESTAMP NOT NULL,
    format_version INT NOT NULL,
    snapshot JSONB NOT NULL
);
