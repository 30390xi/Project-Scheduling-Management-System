ALTER TABLE schedule_plan ADD COLUMN job_id uuid;
ALTER TABLE schedule_plan ADD COLUMN result_snapshot jsonb;
ALTER TABLE schedule_plan ADD COLUMN commit_revision bigint;
CREATE TABLE task_plan_lock (
    company_id uuid NOT NULL REFERENCES company(id),
    task_id uuid NOT NULL,
    locked boolean NOT NULL DEFAULT true,
    reason text NOT NULL,
    changed_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, task_id),
    FOREIGN KEY (company_id, changed_by) REFERENCES company_member(company_id, id)
);
