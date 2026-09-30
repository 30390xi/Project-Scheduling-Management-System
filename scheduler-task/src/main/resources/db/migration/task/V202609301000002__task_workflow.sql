-- 工具 B：任务与工作流第一阶段。业务表均带 company_id，跨表归属使用 (company_id, ...) 复合外键。
-- task_skill_requirement.skill_code 暂不外键引用技能目录（C 的技能表未合并），由集成迁移补齐。

CREATE TABLE task_sequence (
    company_id uuid PRIMARY KEY REFERENCES company(id),
    next_value bigint NOT NULL DEFAULT 1 CHECK (next_value > 0)
);

CREATE TABLE task (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    parent_id uuid,
    node_type varchar(8) NOT NULL DEFAULT 'LEAF' CHECK (node_type IN ('LEAF', 'PARENT')),
    task_no bigint,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'NEEDS_INFO', 'READY', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW', 'DONE', 'CANCELED')),
    schedule_status varchar(16) NOT NULL DEFAULT 'UNSCHEDULED'
        CHECK (schedule_status IN ('UNSCHEDULED', 'PLANNED', 'STALE', 'CLOSED')),
    blocked_from_status varchar(16) CHECK (blocked_from_status IN ('READY', 'IN_PROGRESS')),
    blocked_reason text,
    title varchar(300) NOT NULL,
    description text,
    task_type varchar(64),
    priority varchar(2) NOT NULL DEFAULT 'P2' CHECK (priority IN ('P0', 'P1', 'P2', 'P3')),
    due_type varchar(4) NOT NULL DEFAULT 'SOFT' CHECK (due_type IN ('HARD', 'SOFT')),
    due_date date,
    earliest_start_date date,
    assignee_id uuid,
    fixed_assignee_id uuid,
    preferred_member_id uuid,
    estimated_minutes integer CHECK (estimated_minutes IS NULL OR (estimated_minutes > 0 AND estimated_minutes % 30 = 0)),
    baseline_minutes integer CHECK (baseline_minutes IS NULL OR (baseline_minutes > 0 AND baseline_minutes % 30 = 0)),
    remaining_minutes integer CHECK (remaining_minutes IS NULL OR (remaining_minutes >= 0 AND remaining_minutes % 30 = 0)),
    actual_minutes integer NOT NULL DEFAULT 0 CHECK (actual_minutes >= 0),
    estimate_source varchar(16) CHECK (estimate_source IS NULL OR estimate_source IN ('MANUAL_FINAL', 'AI_CONFIRMED')),
    acceptance_criteria text,
    cancel_reason text,
    idempotency_key varchar(128),
    request_hash varchar(64),
    submitted_at timestamptz,
    completed_at timestamptz,
    canceled_at timestamptz,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (company_id, id),
    UNIQUE (company_id, project_id, id),
    UNIQUE (company_id, task_no),
    UNIQUE (company_id, created_by, idempotency_key),
    CHECK (parent_id IS NULL OR parent_id <> id),
    CHECK (due_date IS NOT NULL OR due_type = 'SOFT'),
    FOREIGN KEY (company_id, project_id) REFERENCES project(company_id, id),
    FOREIGN KEY (company_id, project_id, parent_id) REFERENCES task(company_id, project_id, id),
    FOREIGN KEY (company_id, assignee_id) REFERENCES company_member(company_id, id),
    FOREIGN KEY (company_id, fixed_assignee_id) REFERENCES company_member(company_id, id),
    FOREIGN KEY (company_id, preferred_member_id) REFERENCES company_member(company_id, id),
    FOREIGN KEY (company_id, created_by) REFERENCES company_member(company_id, id)
);

-- 当前任务列表与个人列表索引（TRD 6.4）
CREATE INDEX idx_task_list ON task(company_id, project_id, status, updated_at, id);
CREATE INDEX idx_task_assignee ON task(company_id, assignee_id, schedule_status);
CREATE INDEX idx_task_parent ON task(company_id, parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX idx_task_updated_cursor ON task(company_id, updated_at DESC, id DESC);

CREATE TABLE task_revision (
    company_id uuid NOT NULL,
    task_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    content_json jsonb NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('ORIGINAL', 'MANUAL_EDIT', 'REOPEN')),
    actor_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, task_id, revision),
    FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id),
    FOREIGN KEY (company_id, actor_id) REFERENCES company_member(company_id, id)
);

-- 技能代码在 C 的技能目录合并前仅按字符串保存，等级门槛沿用 1~5。
CREATE TABLE task_skill_requirement (
    company_id uuid NOT NULL,
    task_id uuid NOT NULL,
    skill_code varchar(64) NOT NULL,
    min_level integer NOT NULL CHECK (min_level BETWEEN 1 AND 5),
    required boolean NOT NULL DEFAULT true,
    PRIMARY KEY (company_id, task_id, skill_code),
    FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id)
);

CREATE TABLE task_dependency (
    company_id uuid NOT NULL,
    project_id uuid NOT NULL,
    predecessor_id uuid NOT NULL,
    successor_id uuid NOT NULL,
    dependency_type varchar(4) NOT NULL DEFAULT 'FS' CHECK (dependency_type = 'FS'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, predecessor_id, successor_id),
    CHECK (predecessor_id <> successor_id),
    FOREIGN KEY (company_id, project_id, predecessor_id) REFERENCES task(company_id, project_id, id),
    FOREIGN KEY (company_id, project_id, successor_id) REFERENCES task(company_id, project_id, id)
);

-- 依赖正反向索引（TRD 6.4）
CREATE INDEX idx_task_dependency_successor ON task_dependency(company_id, successor_id);
CREATE INDEX idx_task_dependency_project ON task_dependency(company_id, project_id);

CREATE TABLE work_log (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    task_id uuid NOT NULL,
    member_id uuid NOT NULL,
    work_date date NOT NULL,
    minutes integer NOT NULL CHECK (minutes > 0),
    note varchar(1000),
    idempotency_key varchar(128),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (company_id, task_id, member_id, idempotency_key),
    FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id),
    FOREIGN KEY (company_id, member_id) REFERENCES company_member(company_id, id),
    FOREIGN KEY (company_id, created_by) REFERENCES company_member(company_id, id)
);

CREATE INDEX idx_work_log_task ON work_log(company_id, task_id, work_date);

CREATE TABLE task_remaining_estimate (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    task_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    effective_after_date date NOT NULL,
    remaining_minutes integer NOT NULL CHECK (remaining_minutes >= 0 AND remaining_minutes % 30 = 0),
    confirmed_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (company_id, task_id, version),
    FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id),
    FOREIGN KEY (company_id, confirmed_by) REFERENCES company_member(company_id, id)
);

CREATE TABLE task_status_event (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    task_id uuid NOT NULL,
    from_status varchar(16),
    to_status varchar(16) NOT NULL,
    actor_id uuid NOT NULL,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id),
    FOREIGN KEY (company_id, actor_id) REFERENCES company_member(company_id, id)
);

CREATE INDEX idx_task_status_event_task ON task_status_event(company_id, task_id, created_at);
