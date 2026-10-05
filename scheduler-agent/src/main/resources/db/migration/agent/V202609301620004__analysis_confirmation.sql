ALTER TABLE ai_analysis_run ADD COLUMN confirmation_result jsonb;
ALTER TABLE ai_analysis_run ADD COLUMN confirmed_by uuid;
ALTER TABLE ai_analysis_run ADD COLUMN confirmed_at timestamptz;
