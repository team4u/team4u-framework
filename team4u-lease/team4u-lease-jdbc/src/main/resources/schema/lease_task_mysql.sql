CREATE TABLE IF NOT EXISTS lease_task
(
    task_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    queue_name       VARCHAR(128) NOT NULL,
    task_type        VARCHAR(128) NOT NULL,
    payload          TEXT,
    business_key     VARCHAR(256),
    state            VARCHAR(32)  NOT NULL,
    outcome          VARCHAR(32),
    failure_reason   VARCHAR(64),
    priority         INT          NOT NULL DEFAULT 0,
    delivery_count   INT          NOT NULL DEFAULT 0,
    failure_count    INT          NOT NULL DEFAULT 0,
    worker_id        VARCHAR(128),
    lease_token      VARCHAR(128),
    lease_expires_at BIGINT       NOT NULL DEFAULT 0,
    visible_at       BIGINT       NOT NULL,
    created_at       BIGINT       NOT NULL,
    updated_at       BIGINT       NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    error_message    TEXT,
    attributes_json  TEXT
);

-- 抢占优化索引：针对 READY 状态任务，通过队列、可见性及优先级进行排序查找
CREATE INDEX IF NOT EXISTS idx_lease_task_acquire_ready
    ON lease_task (queue_name, state, visible_at, priority, created_at, task_id);

-- 抢占优化索引：针对已超时的 RUNNING 任务（故障接管场景），通过租约过期时间进行快速定位
CREATE INDEX IF NOT EXISTS idx_lease_task_acquire_expired
    ON lease_task (queue_name, state, lease_expires_at, priority, created_at, task_id);

CREATE INDEX IF NOT EXISTS idx_lease_task_worker
    ON lease_task (worker_id, state);

CREATE INDEX IF NOT EXISTS idx_lease_task_type
    ON lease_task (queue_name, task_type, state);

CREATE UNIQUE INDEX IF NOT EXISTS uk_lease_task_business
    ON lease_task (queue_name, business_key);

CREATE INDEX IF NOT EXISTS idx_lease_task_closed_reason
    ON lease_task (state, outcome, failure_reason);
