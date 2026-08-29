ALTER TABLE user_model_api
    ALTER COLUMN api_key_encrypted DROP NOT NULL,
    ALTER COLUMN api_key_hint DROP NOT NULL;
