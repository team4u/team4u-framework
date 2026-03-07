CREATE TABLE IF NOT EXISTS lease_task
(
    task_id
    VARCHAR
(
    64
) NOT NULL PRIMARY KEY,
    queue_name VARCHAR
(
    128
) NOT NULL,
    task_type VARCHAR
(
    128
) NOT NULL,
    payload TEXT,
    status VARCHAR
(
    32
) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    delivery_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    worker_id VARCHAR
(
    128
),
    lease_token VARCHAR
(
    128
),
    lease_expires_at BIGINT NOT NULL DEFAULT 0,
    visible_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    last_error TEXT,
    attributes_json TEXT
    );

CREATE INDEX IF NOT EXISTS idx_lease_task_acquire
    ON lease_task(queue_name, status, visible_at, lease_expires_at, priority, created_at);

CREATE INDEX IF NOT EXISTS idx_lease_task_worker
    ON lease_task(worker_id, status);

CREATE INDEX IF NOT EXISTS idx_lease_task_type
    ON lease_task(queue_name, task_type, status);
