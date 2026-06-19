-- V2__device_token_and_unique_keys.sql
-- Add the device API token hash column and uniqueness constraints that
-- were not structurally possible in V1 (token is generated at registration).
--
-- Invariants (see CLAUDE.md §4):
--   * Only the SHA-256 hash of the token is stored. Plaintext is returned once.
--   * device_token_hash is VARCHAR(64) — SHA-256 in lowercase hex is always 64 chars.
--     Using VARCHAR (not CHAR) so Hibernate's schema validation passes without annotation overrides.
--   * public_key must be unique: reject duplicate device registration (FR1).

ALTER TABLE devices
    ADD COLUMN device_token_hash VARCHAR(64) NOT NULL,
    ADD CONSTRAINT uq_device_token_hash UNIQUE (device_token_hash),
    ADD CONSTRAINT uq_device_public_key UNIQUE (public_key);
