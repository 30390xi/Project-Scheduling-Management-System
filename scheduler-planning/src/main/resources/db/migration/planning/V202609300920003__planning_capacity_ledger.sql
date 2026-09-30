-- 工具 C：容量账本。member_day_capacity 是容量计数唯一入口，task_allocation 是占用明细。
-- 两者通过 DEFERRABLE 约束触发器保证 committed 时 计数 = RESERVED+RETAINED 明细之和；
-- 直接改桶计数或只写明细都会在事务提交时被拒绝回滚。
-- task_allocation 对 task 表的 (company_id, task_id) 外键待 B 分支 task 表合并后由集成迁移补齐，
-- 当前以应用层校验 + 普通索引过渡。

CREATE TABLE member_day_capacity (
    company_id uuid NOT NULL,
    member_id uuid NOT NULL,
    work_date date NOT NULL,
    capacity_minutes integer NOT NULL
        CHECK (capacity_minutes >= 0 AND capacity_minutes % 30 = 0),
    occupied_minutes integer NOT NULL DEFAULT 0
        CHECK (occupied_minutes >= 0 AND occupied_minutes % 30 = 0),
    calendar_version bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, member_id, work_date),
    FOREIGN KEY (company_id, member_id) REFERENCES company_member(company_id, id),
    CHECK (occupied_minutes <= capacity_minutes)
);

CREATE INDEX idx_member_day_capacity_date ON member_day_capacity(company_id, work_date);

CREATE TABLE schedule_plan (
    company_id uuid NOT NULL,
    id uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN
        ('GENERATING', 'READY', 'INFEASIBLE', 'FAILED', 'COMMITTED', 'EXPIRED', 'REJECTED')),
    input_revision bigint NOT NULL CHECK (input_revision >= 0),
    root_task_ids jsonb NOT NULL,
    input_snapshot jsonb NOT NULL,
    algorithm_version varchar(32) NOT NULL,
    plan_hash varchar(64) NOT NULL DEFAULT '',
    created_by uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    committed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (company_id, id),
    FOREIGN KEY (company_id, created_by) REFERENCES company_member(company_id, id),
    CHECK (status <> 'COMMITTED' OR committed_at IS NOT NULL)
);

CREATE INDEX idx_schedule_plan_status ON schedule_plan(company_id, status, expires_at);

CREATE TABLE task_allocation (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL,
    task_id uuid NOT NULL,
    member_id uuid NOT NULL,
    work_date date NOT NULL,
    minutes integer NOT NULL CHECK (minutes > 0 AND minutes % 30 = 0),
    occupancy_state varchar(16) NOT NULL
        CHECK (occupancy_state IN ('RESERVED', 'RETAINED', 'RELEASED')),
    execution_started boolean NOT NULL DEFAULT false,
    plan_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (company_id, member_id, work_date)
        REFERENCES member_day_capacity(company_id, member_id, work_date),
    FOREIGN KEY (company_id, plan_id) REFERENCES schedule_plan(company_id, id)
);

CREATE UNIQUE INDEX uq_task_active_day
    ON task_allocation(company_id, task_id, work_date)
    WHERE occupancy_state = 'RESERVED';

CREATE INDEX idx_member_active_allocations
    ON task_allocation(company_id, member_id, work_date)
    WHERE occupancy_state IN ('RESERVED', 'RETAINED');

CREATE INDEX idx_task_allocations
    ON task_allocation(company_id, task_id, occupancy_state);

-- 桶计数必须等于该桶 RESERVED+RETAINED 明细之和；桶行不存在视为失衡。
CREATE OR REPLACE FUNCTION planning_assert_bucket_balanced(
    p_company_id uuid, p_member_id uuid, p_work_date date)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_occupied integer;
    v_details integer;
BEGIN
    SELECT occupied_minutes INTO v_occupied
      FROM member_day_capacity
     WHERE company_id = p_company_id AND member_id = p_member_id AND work_date = p_work_date;

    SELECT COALESCE(SUM(minutes), 0) INTO v_details
      FROM task_allocation
     WHERE company_id = p_company_id AND member_id = p_member_id AND work_date = p_work_date
       AND occupancy_state IN ('RESERVED', 'RETAINED');

    IF v_occupied IS NULL OR v_occupied <> v_details THEN
        RAISE EXCEPTION 'CAPACITY_ACCOUNTING_MISMATCH company=% member=% date=% bucket=% details=%',
            p_company_id, p_member_id, p_work_date, v_occupied, v_details
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION planning_assert_allocation_bucket()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM planning_assert_bucket_balanced(OLD.company_id, OLD.member_id, OLD.work_date);
    ELSE
        PERFORM planning_assert_bucket_balanced(NEW.company_id, NEW.member_id, NEW.work_date);
    END IF;
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION planning_assert_capacity_bucket()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM planning_assert_bucket_balanced(NEW.company_id, NEW.member_id, NEW.work_date);
    RETURN NULL;
END $$;

-- 同一叶子任务的所有当前 RESERVED 片段必须同一负责人；历史 RETAINED 可保留旧负责人。
CREATE OR REPLACE FUNCTION planning_assert_single_assignee()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_distinct integer;
BEGIN
    SELECT count(DISTINCT member_id) INTO v_distinct
      FROM task_allocation
     WHERE company_id = NEW.company_id AND task_id = NEW.task_id
       AND occupancy_state = 'RESERVED';
    IF v_distinct > 1 THEN
        RAISE EXCEPTION 'MULTIPLE_ASSIGNEES task=% distinct=%', NEW.task_id, v_distinct
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_allocation_bucket_balance
    AFTER INSERT OR UPDATE OR DELETE ON task_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION planning_assert_allocation_bucket();

CREATE CONSTRAINT TRIGGER trg_bucket_self_balance
    AFTER UPDATE OF occupied_minutes ON member_day_capacity
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION planning_assert_capacity_bucket();

CREATE CONSTRAINT TRIGGER trg_allocation_single_assignee
    AFTER INSERT OR UPDATE OF member_id, occupancy_state ON task_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION planning_assert_single_assignee();
