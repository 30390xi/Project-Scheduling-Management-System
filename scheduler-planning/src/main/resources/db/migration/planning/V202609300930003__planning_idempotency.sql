-- 工具 C：排期写接口幂等记录。
-- 命名为 planning_idempotency 以避免与其他分支的通用 api_idempotency 表冲突；
-- 若集成人统一为共享表，可在集成迁移中迁移数据后废弃本表。
-- 同 key 不同请求 hash 返回 409；同 key 同内容在事务成功后重放返回首次结果。

CREATE TABLE planning_idempotency (
    company_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    route varchar(100) NOT NULL,
    idem_key varchar(200) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
    response_snapshot jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, actor_id, route, idem_key),
    FOREIGN KEY (company_id, actor_id) REFERENCES company_member(company_id, id),
    CHECK (status <> 'SUCCEEDED' OR response_snapshot IS NOT NULL)
);
