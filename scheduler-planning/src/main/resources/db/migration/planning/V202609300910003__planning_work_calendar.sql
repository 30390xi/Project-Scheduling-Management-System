-- 工具 C：公司工作日历与成员日历覆盖（请假、额外不可用）。
-- 无 calendar_day 行的日期按周一至周五为工作日推断；节假日/调休由管理员显式维护。
-- member_calendar_override.workday_flag 为 NULL 表示沿用公司日历。

CREATE TABLE calendar_day (
    company_id uuid NOT NULL REFERENCES company(id),
    work_date date NOT NULL,
    workday_flag boolean NOT NULL,
    source varchar(32) NOT NULL CHECK (source IN ('HOLIDAY', 'ADJUSTED_WORKDAY', 'MANUAL')),
    note varchar(500) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, work_date)
);

CREATE TABLE member_calendar_override (
    company_id uuid NOT NULL,
    member_id uuid NOT NULL,
    work_date date NOT NULL,
    workday_flag boolean,
    leave_minutes integer NOT NULL DEFAULT 0
        CHECK (leave_minutes >= 0 AND leave_minutes % 30 = 0),
    extra_unavailable_minutes integer NOT NULL DEFAULT 0
        CHECK (extra_unavailable_minutes >= 0 AND extra_unavailable_minutes % 30 = 0),
    reason varchar(500) NOT NULL DEFAULT '',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, member_id, work_date),
    FOREIGN KEY (company_id, member_id) REFERENCES company_member(company_id, id)
);

CREATE INDEX idx_member_calendar_range ON member_calendar_override(company_id, member_id, work_date);
CREATE INDEX idx_calendar_day_company_date ON calendar_day(company_id, work_date);
