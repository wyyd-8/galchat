CREATE TABLE user_model_api (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    api_key_hint VARCHAR(32) NOT NULL,
    request_overrides JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'UNTESTED'
        CHECK (status IN ('UNTESTED', 'SUCCESS', 'PARTIAL', 'FAILED')),
    chat_capability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (chat_capability IN ('UNKNOWN', 'SUPPORTED', 'UNSUPPORTED', 'INCONCLUSIVE')),
    streaming_capability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (streaming_capability IN ('UNKNOWN', 'SUPPORTED', 'UNSUPPORTED', 'INCONCLUSIVE')),
    tool_calling_capability VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (tool_calling_capability IN ('UNKNOWN', 'SUPPORTED', 'UNSUPPORTED', 'INCONCLUSIVE')),
    reasoning_output_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (reasoning_output_status IN ('UNKNOWN', 'DETECTED', 'NOT_DETECTED')),
    last_test_code VARCHAR(50),
    last_test_message VARCHAR(1000),
    last_test_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_user_model_api_user_name
    ON user_model_api (user_id, name);

CREATE INDEX idx_user_model_api_user_updated
    ON user_model_api (user_id, updated_at DESC, id DESC);
