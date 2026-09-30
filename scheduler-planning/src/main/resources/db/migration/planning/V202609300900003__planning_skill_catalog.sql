-- 工具 C：技能目录与成员认证技能。
-- 自报技能（self_reported=true）不作为排期准入依据，仅认证条目参与候选过滤。

CREATE TABLE skill (
    company_id uuid NOT NULL REFERENCES company(id),
    id uuid NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(200) NOT NULL,
    description varchar(2000) NOT NULL DEFAULT '',
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, id),
    UNIQUE (company_id, code),
    CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$')
);

CREATE TABLE member_skill (
    company_id uuid NOT NULL,
    member_id uuid NOT NULL,
    skill_id uuid NOT NULL,
    level integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    self_reported boolean NOT NULL DEFAULT false,
    certified_by uuid,
    certified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, member_id, skill_id),
    FOREIGN KEY (company_id, member_id) REFERENCES company_member(company_id, id),
    FOREIGN KEY (company_id, skill_id) REFERENCES skill(company_id, id),
    CHECK (
        (self_reported AND certified_by IS NULL AND certified_at IS NULL)
        OR (NOT self_reported AND certified_by IS NOT NULL AND certified_at IS NOT NULL)
    )
);

CREATE INDEX idx_member_skill_skill ON member_skill(company_id, skill_id, level);
