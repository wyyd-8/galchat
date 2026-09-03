ALTER TABLE user_character_info
    ADD COLUMN model_api_id BIGINT;

COMMENT ON COLUMN user_character_info.model_api_id IS
    'Optional weak reference to user_model_api; null or missing target uses the built-in model';
