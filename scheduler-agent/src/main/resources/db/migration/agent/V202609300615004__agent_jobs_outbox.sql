-- 工具 D：可靠异步基础设施
-- async_job：数据库作业队列（领取、租约、fencing generation、重试、取消、幂等）
-- outbox_event / processed_event：事务 Outbox 与消费去重
-- notification：站内通知（唯一键保证同一事件同一收件人只产生一条）
-- audit_event：追加写审计（触发器阻止 UPDATE/DELETE）

CREATE TABLE async_job (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company(id),
    job_type varchar(64) NOT NULL,
    aggregate_id uuid,
    aggregate_version bigint NOT NULL DEFAULT 0,
    idempotency_key varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    lease_owner varchar(128),
    lease_until timestamptz,
    lease_generation bigint NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
    next_run_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    result jsonb,
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (company_id, job_type, idempotency_key),
    -- 租约字段只允许在 RUNNING 状态存在，状态与租约必须一起变更
    CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
    )
);

-- 作业领取扫描索引（TRD 6.4）
CREATE INDEX idx_async_job_claim ON async_job(status, next_run_at);
CREATE INDEX idx_async_job_company ON async_job(company_id, created_at);

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company(id),
    event_type varchar(64) NOT NULL,
    aggregate_type varchar(64),
    aggregate_id uuid,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending ON outbox_event(next_attempt_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_event (
    consumer_name varchar(64) NOT NULL,
    event_id uuid NOT NULL,
    company_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE notification (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    event_id uuid NOT NULL,
    recipient_member_id uuid NOT NULL,
    channel varchar(16) NOT NULL DEFAULT 'INBOX' CHECK (channel = 'INBOX'),
    category varchar(16) NOT NULL DEFAULT 'INFO' CHECK (category IN ('INFO', 'HIGH_RISK')),
    title varchar(200) NOT NULL,
    body varchar(2000) NOT NULL,
    aggregate_type varchar(64),
    aggregate_id uuid,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- 同一事件同一收件人同一渠道只有一条（TRD 6.4 通知唯一键）
    UNIQUE (company_id, event_id, recipient_member_id, channel),
    FOREIGN KEY (company_id, recipient_member_id)
        REFERENCES company_member(company_id, id)
);

CREATE INDEX idx_notification_recipient ON notification(company_id, recipient_member_id, created_at);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company(id),
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'SERVICE')),
    actor_id uuid,
    action varchar(64) NOT NULL,
    entity_type varchar(64) NOT NULL,
    entity_id uuid,
    before_json jsonb,
    after_json jsonb,
    reason varchar(500),
    request_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_event_company ON audit_event(company_id, entity_type, created_at);

-- 审计历史只允许追加，普通 UPDATE/DELETE 一律拒绝（TRD 6.1）
CREATE OR REPLACE FUNCTION agent_audit_event_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION agent_audit_event_append_only();
