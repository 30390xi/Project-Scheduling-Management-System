ALTER TABLE task_allocation ADD CONSTRAINT fk_allocation_company_task FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id);
ALTER TABLE task_plan_lock ADD CONSTRAINT fk_plan_lock_company_task FOREIGN KEY (company_id, task_id) REFERENCES task(company_id, id);
