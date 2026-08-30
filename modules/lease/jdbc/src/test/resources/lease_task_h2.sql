CREATE TABLE IF NOT EXISTS lease_task
(
    task_id           VARCHAR(64) NOT NULL PRIMARY KEY,
    queue_name        VARCHAR(128) NOT NULL,
    task_type         VARCHAR(128) NOT NULL,
    payload           TEXT,
    deduplication_key VARCHAR(256),
    status            VARCHAR(32) NOT NULL,
    priority          INT NOT NULL DEFAULT 0,
    attempt_count     INT NOT NULL DEFAULT 0,
    worker_id         VARCHAR(128),
    lease_token       VARCHAR(128),
    lease_expires_at  BIGINT,
    visible_at        BIGINT NOT NULL,
    created_at        BIGINT NOT NULL,
    updated_at        BIGINT NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    error_message     TEXT,
    attributes_json   TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_task_dedup
    ON lease_task (queue_name, task_type, deduplication_key);

CREATE INDEX IF NOT EXISTS idx_lease_task_pending
    ON lease_task (queue_name, status, task_type, visible_at, priority, created_at, task_id);

CREATE INDEX IF NOT EXISTS idx_lease_task_expired
    ON lease_task (queue_name, status, task_type, lease_expires_at, priority, created_at, task_id);

CREATE INDEX IF NOT EXISTS idx_lease_task_query
    ON lease_task (queue_name, task_type, status, worker_id, created_at, task_id);
