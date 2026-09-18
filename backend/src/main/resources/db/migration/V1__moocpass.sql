CREATE TABLE mp_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 app_id VARCHAR(80) NOT NULL,
 openid VARCHAR(128) NOT NULL,
 nickname VARCHAR(80) NOT NULL DEFAULT 'MoocPass 用户',
 avatar_url VARCHAR(1000) NOT NULL DEFAULT '',
 preferences TEXT,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (app_id, openid)
);

CREATE TABLE mp_auth_session (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 access_hash VARCHAR(64) NOT NULL UNIQUE,
 refresh_hash VARCHAR(64) NOT NULL UNIQUE,
 access_expires_at TIMESTAMP NOT NULL,
 refresh_expires_at TIMESTAMP NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES mp_user(id)
);

CREATE TABLE mp_secret_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 purpose VARCHAR(40) NOT NULL,
 key_version VARCHAR(32) NOT NULL,
 ciphertext TEXT NOT NULL,
 mask VARCHAR(16) NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES mp_user(id)
);

CREATE TABLE mp_platform_account (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 platform_code VARCHAR(32) NOT NULL,
 site_type VARCHAR(32) NOT NULL DEFAULT '',
 username VARCHAR(128) NOT NULL,
 secret_id BIGINT NOT NULL,
 status VARCHAR(32) NOT NULL,
 last_synced_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (user_id, platform_code),
 FOREIGN KEY (user_id) REFERENCES mp_user(id),
 FOREIGN KEY (secret_id) REFERENCES mp_secret_version(id)
);

CREATE TABLE mp_course (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 account_id BIGINT NOT NULL,
 external_id VARCHAR(128) NOT NULL,
 class_id VARCHAR(128) NOT NULL DEFAULT '',
 name VARCHAR(255) NOT NULL,
 teacher VARCHAR(255) NOT NULL DEFAULT '',
 cover_url VARCHAR(1000) NOT NULL DEFAULT '',
 platform_progress INT NULL,
 resource_snapshot LONGTEXT,
 synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (account_id, external_id, class_id),
 FOREIGN KEY (user_id) REFERENCES mp_user(id),
 FOREIGN KEY (account_id) REFERENCES mp_platform_account(id)
);

CREATE TABLE mp_answer_profile (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 is_default BOOLEAN NOT NULL DEFAULT FALSE,
 current_version_id BIGINT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES mp_user(id)
);

CREATE TABLE mp_profile_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 profile_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 secret_id BIGINT NULL,
 config_json TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (profile_id) REFERENCES mp_answer_profile(id),
 FOREIGN KEY (secret_id) REFERENCES mp_secret_version(id)
);

CREATE TABLE mp_task_batch (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 idempotency_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 result_json LONGTEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (user_id, idempotency_key),
 FOREIGN KEY (user_id) REFERENCES mp_user(id)
);

CREATE TABLE mp_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 account_id BIGINT NOT NULL,
 course_id BIGINT NULL,
 batch_id BIGINT NULL,
 source_task_id BIGINT NULL,
 kind VARCHAR(16) NOT NULL DEFAULT 'COURSE',
 course_name VARCHAR(255) NOT NULL DEFAULT '',
 config_json TEXT NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
 progress INT NOT NULL DEFAULT 0,
 platform_progress INT NULL,
 current_step VARCHAR(255) NOT NULL DEFAULT '',
 error_code VARCHAR(64) NOT NULL DEFAULT '',
 error_message VARCHAR(500) NOT NULL DEFAULT '',
 owner VARCHAR(80) NULL,
 lease_until TIMESTAMP NULL,
 version BIGINT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0,
 next_run_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 log_seq BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES mp_user(id),
 FOREIGN KEY (account_id) REFERENCES mp_platform_account(id),
 FOREIGN KEY (course_id) REFERENCES mp_course(id)
);
CREATE INDEX idx_task_queue ON mp_task(state, next_run_at, id);
CREATE INDEX idx_task_user ON mp_task(user_id, created_at);
CREATE INDEX idx_task_lease ON mp_task(lease_until);

CREATE TABLE mp_active_course (
 account_id BIGINT NOT NULL,
 course_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL UNIQUE,
 PRIMARY KEY (account_id, course_id),
 FOREIGN KEY (task_id) REFERENCES mp_task(id)
);

CREATE TABLE mp_scheduler_lock (id INT PRIMARY KEY);
INSERT INTO mp_scheduler_lock(id) VALUES(1);

CREATE TABLE mp_account_lease (
 account_id BIGINT PRIMARY KEY,
 task_id BIGINT NOT NULL UNIQUE,
 owner VARCHAR(80) NOT NULL,
 lease_until TIMESTAMP NOT NULL
);

CREATE TABLE mp_task_checkpoint (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 resource_id VARCHAR(200) NOT NULL,
 state VARCHAR(32) NOT NULL,
 detail_json TEXT,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (task_id, resource_id),
 FOREIGN KEY (task_id) REFERENCES mp_task(id)
);

CREATE TABLE mp_task_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 seq BIGINT NOT NULL,
 level VARCHAR(12) NOT NULL,
 event_type VARCHAR(32) NOT NULL,
 message VARCHAR(2000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (task_id, seq),
 FOREIGN KEY (task_id) REFERENCES mp_task(id)
);
CREATE INDEX idx_log_retention ON mp_task_log(created_at);

CREATE TABLE mp_question_result (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 task_id BIGINT NOT NULL,
 question_key VARCHAR(64) NOT NULL,
 question_type VARCHAR(32) NOT NULL,
 question TEXT NOT NULL,
 options_json TEXT NOT NULL,
 answer TEXT,
 source VARCHAR(32) NOT NULL,
 state VARCHAR(32) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (task_id, question_key),
 FOREIGN KEY (user_id) REFERENCES mp_user(id),
 FOREIGN KEY (task_id) REFERENCES mp_task(id)
);

CREATE TABLE mp_answer_cache (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 question_key VARCHAR(64) NOT NULL,
 answer TEXT NOT NULL,
 source VARCHAR(32) NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (user_id, question_key),
 FOREIGN KEY (user_id) REFERENCES mp_user(id)
);

CREATE TABLE mp_api_usage (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 task_id BIGINT NULL,
 profile_id BIGINT NOT NULL,
 model VARCHAR(128) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
 reserved_tokens INT NOT NULL,
 input_tokens INT NULL,
 output_tokens INT NULL,
 elapsed_ms BIGINT NULL,
 error_code VARCHAR(64) NOT NULL DEFAULT '',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (user_id) REFERENCES mp_user(id)
);
CREATE INDEX idx_usage_budget ON mp_api_usage(user_id, profile_id, created_at);
