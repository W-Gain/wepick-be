-- Additive expansion: existing entities and API writes keep using the V1 columns.
-- New image metadata remains optional until its callers are migrated.
ALTER TABLE users
    ADD COLUMN role ENUM ('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
    ADD COLUMN withdrawn_at DATETIME(6) NULL;

ALTER TABLE images
    ADD COLUMN owner_user_id BIGINT NULL,
    ADD COLUMN purpose VARCHAR(20) NULL,
    ADD COLUMN expires_at DATETIME(6) NULL,
    ADD INDEX IX_images_status_expires_at (status, expires_at),
    ADD CONSTRAINT FK_images_owner_user FOREIGN KEY (owner_user_id) REFERENCES users (user_id);

CREATE TABLE social_accounts (
    social_account_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,
    connected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (social_account_id),
    CONSTRAINT UK_social_accounts_provider_identity UNIQUE (provider, provider_user_id),
    CONSTRAINT UK_social_accounts_user_provider UNIQUE (user_id, provider),
    CONSTRAINT FK_social_accounts_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

CREATE TABLE anonymous_voters (
    anonymous_voter_id BIGINT NOT NULL AUTO_INCREMENT,
    token_hash VARCHAR(255) NOT NULL,
    linked_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (anonymous_voter_id),
    CONSTRAINT UK_anonymous_voters_token_hash UNIQUE (token_hash),
    INDEX IX_anonymous_voters_linked_user (linked_user_id),
    INDEX IX_anonymous_voters_expires_at (expires_at),
    CONSTRAINT FK_anonymous_voters_linked_user FOREIGN KEY (linked_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB;

CREATE TABLE login_attempts (
    login_attempt_id BIGINT NOT NULL AUTO_INCREMENT,
    state_hash CHAR(64) NOT NULL,
    browser_binding_hash CHAR(64) NOT NULL,
    client_attempt_id VARCHAR(64) NOT NULL,
    return_to VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    PRIMARY KEY (login_attempt_id),
    CONSTRAINT UK_login_attempts_state_hash UNIQUE (state_hash),
    INDEX IX_login_attempts_expires_at (expires_at)
) ENGINE=InnoDB;

CREATE TABLE external_unlink_jobs (
    unlink_job_id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (unlink_job_id),
    INDEX IX_external_unlink_jobs_due (next_attempt_at, expires_at, unlink_job_id)
) ENGINE=InnoDB;
