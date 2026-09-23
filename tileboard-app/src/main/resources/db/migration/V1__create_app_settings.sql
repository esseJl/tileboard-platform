CREATE TABLE app_settings
(
    setting_key VARCHAR(200) PRIMARY KEY,
    value_json  TEXT      NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    version     BIGINT    NOT NULL DEFAULT 0
);