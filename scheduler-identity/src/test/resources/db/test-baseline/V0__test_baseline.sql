-- 仅用于 scheduler-identity 模块独立集成测试的基线：复制 app 模块 V1 基础表，
-- 使本模块可以不依赖 app 资源单独验证迁移与权限行为。V1 变更由集成人统一协调，若 V1 演进需同步本文件。
CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE company (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE company_member (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company(id),
    user_id uuid NOT NULL REFERENCES app_user(id),
    role varchar(32) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'MEMBER')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    accept_new_assignments boolean NOT NULL DEFAULT true,
    accept_auto_assign boolean NOT NULL DEFAULT true,
    standard_minutes integer NOT NULL DEFAULT 480 CHECK (standard_minutes BETWEEN 0 AND 1440),
    daily_limit_minutes integer NOT NULL DEFAULT 360,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(company_id, id),
    UNIQUE(company_id, user_id),
    CHECK (daily_limit_minutes >= 0 AND daily_limit_minutes <= standard_minutes AND daily_limit_minutes % 30 = 0)
);

CREATE TABLE project (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company(id),
    name varchar(200) NOT NULL,
    auto_assign_enabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE(company_id, id)
);

CREATE TABLE project_member (
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    member_id uuid NOT NULL,
    role varchar(16) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    resource_permission boolean NOT NULL DEFAULT false,
    PRIMARY KEY(company_id, project_id, member_id),
    FOREIGN KEY(company_id, project_id) REFERENCES project(company_id, id),
    FOREIGN KEY(company_id, member_id) REFERENCES company_member(company_id, id)
);

CREATE TABLE planning_guard (
    company_id uuid PRIMARY KEY REFERENCES company(id),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);

CREATE INDEX idx_project_member_member ON project_member(company_id, member_id);
