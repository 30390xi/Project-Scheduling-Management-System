-- 工具 A 第一阶段：登录加固字段与身份域审计事件。
-- 基础表 app_user 字段归属 identity 模块（见 docs/parallel-development.md），此处只做增量列，不改 V1/V2 内容。

ALTER TABLE app_user
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN password_changed_at timestamptz,
    ADD COLUMN failed_login_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN locked_until timestamptz;

-- 身份域高影响操作审计：成员/角色/项目/资源授权/密码重置。
-- 统一审计查询与跨模块汇聚由集成人协调，本表是 identity 的权威留痕来源。
CREATE TABLE identity_audit_event (
    id uuid PRIMARY KEY,
    company_id uuid REFERENCES company(id),
    actor_user_id uuid NOT NULL,
    actor_member_id uuid,
    action varchar(64) NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid,
    reason text,
    detail_json jsonb,
    request_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_identity_audit_company_time ON identity_audit_event(company_id, created_at DESC, id);
