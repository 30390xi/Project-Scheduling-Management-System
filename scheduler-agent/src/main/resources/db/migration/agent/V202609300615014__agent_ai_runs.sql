-- 工具 D：AI 分析运行记录
-- ai_analysis_run 是一次 AI 分析的事实来源：输入快照、原始输出、校验后的建议版本、
-- 预算与用量。分析结果只保存为“建议”，确认后交给任务模块建立任务。

CREATE TABLE ai_analysis_run (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES company(id),
    job_id uuid,
    submitted_by_member_id uuid NOT NULL,
    task_aggregate_id uuid,
    task_text_hash varchar(64) NOT NULL,
    input_snapshot jsonb NOT NULL,
    model varchar(64) NOT NULL,
    prompt_version varchar(32) NOT NULL,
    schema_version varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REUSED')),
    provider_calls integer NOT NULL DEFAULT 0 CHECK (provider_calls >= 0),
    raw_output jsonb,
    validated_output jsonb,
    usage jsonb,
    quality_score numeric(4, 3),
    error_code varchar(64),
    error_detail varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (company_id, id),
    FOREIGN KEY (company_id, submitted_by_member_id)
        REFERENCES company_member(company_id, id)
);

-- 相同输入 + 模型 + 提示模板版本的结果复用查询（不重复付费调用）
CREATE INDEX idx_ai_run_dedup
    ON ai_analysis_run(company_id, task_text_hash, model, prompt_version);
