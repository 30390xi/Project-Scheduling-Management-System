package com.company.scheduler.agent.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ai_analysis_run 持久化。所有状态迁移都是条件更新（乐观状态机），
 * 只允许从预期前态迁移，重复执行/并发写不会覆盖终态。
 */
@Repository
public class AiAnalysisRunRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AiAnalysisRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockSubmission(UUID companyId, String scopedKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", companyId + ":" + scopedKey), rs -> {});
    }

    public void insert(AiAnalysisRun run) {
        jdbc.update("""
                INSERT INTO ai_analysis_run(id, company_id, job_id, submitted_by_member_id,
                                            task_aggregate_id, task_text_hash, input_snapshot, model,
                                            prompt_version, schema_version, status)
                VALUES (:id, :companyId, :jobId, :submittedBy, :taskAggregateId, :taskTextHash,
                        :inputSnapshot::jsonb, :model, :promptVersion, :schemaVersion, :status)
                """, runParams(run));
    }

    public Optional<AiAnalysisRun> findById(UUID runId) {
        List<AiAnalysisRun> found = jdbc.query(
                "SELECT * FROM ai_analysis_run WHERE id = :id",
                new MapSqlParameterSource("id", runId), this::map);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** 相同输入哈希 + 模型 + 提示版本的成功结果复用，不重复付费调用。 */
    public Optional<AiAnalysisRun> findReusableSucceeded(UUID companyId, String taskTextHash,
            String model, String promptVersion) {
        List<AiAnalysisRun> found = jdbc.query("""
                SELECT * FROM ai_analysis_run
                WHERE company_id = :companyId AND task_text_hash = :hash AND model = :model
                  AND prompt_version = :promptVersion AND status = 'SUCCEEDED'
                ORDER BY created_at LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("hash", taskTextHash)
                .addValue("model", model)
                .addValue("promptVersion", promptVersion), this::map);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** 幂等进入执行态：PENDING 或已被上一次未完成尝试置为 RUNNING 都允许（租约才是并发权威）。 */
    public java.time.Instant startDeadline(UUID runId, java.time.Duration duration) {
        return jdbc.queryForObject("""
                UPDATE ai_analysis_run SET run_deadline = COALESCE(run_deadline,
                    clock_timestamp() + (:seconds * interval '1 second'))
                WHERE id = :id RETURNING run_deadline
                """, new MapSqlParameterSource("id", runId).addValue("seconds", duration.toSeconds()),
                java.sql.Timestamp.class).toInstant();
    }

    public void consumeCall(UUID runId, int maxCalls) {
        int updated = jdbc.update("""
                UPDATE ai_analysis_run SET provider_calls = provider_calls + 1
                WHERE id = :id AND status = 'RUNNING' AND provider_calls < :max
                  AND run_deadline > clock_timestamp()
                """, new MapSqlParameterSource("id", runId).addValue("max", maxCalls));
        if (updated != 1) throw new AiCallBudget.AiBudgetExceededException(
                "AI_BUDGET_EXCEEDED", "分析运行调用预算或截止时间已耗尽");
    }

    public boolean markRunning(UUID runId) {
        return jdbc.update("""
                UPDATE ai_analysis_run SET status = 'RUNNING', updated_at = now()
                WHERE id = :id AND status IN ('PENDING', 'RUNNING')
                """, new MapSqlParameterSource("id", runId)) == 1;
    }

    public boolean markSucceeded(UUID runId, String rawOutputJson, String validatedOutputJson,
            String usageJson, double qualityScore, int providerCalls) {
        return jdbc.update("""
                UPDATE ai_analysis_run SET
                    status = 'SUCCEEDED', raw_output = :rawOutput::jsonb,
                    validated_output = :validated::jsonb, usage = :usage::jsonb,
                    quality_score = :quality,
                    error_code = NULL, error_detail = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("id", runId)
                .addValue("rawOutput", rawOutputJson)
                .addValue("validated", validatedOutputJson)
                .addValue("usage", usageJson)
                .addValue("quality", qualityScore)
                .addValue("calls", providerCalls)) == 1;
    }

    public boolean markFailed(UUID runId, String errorCode, String errorDetail) {
        return jdbc.update("""
                UPDATE ai_analysis_run SET
                    status = 'FAILED', error_code = :errorCode, error_detail = :errorDetail,
                    updated_at = now()
                WHERE id = :id AND status IN ('PENDING', 'RUNNING')
                """, new MapSqlParameterSource()
                .addValue("id", runId)
                .addValue("errorCode", truncate(errorCode, 64))
                .addValue("errorDetail", truncate(errorDetail, 500))) == 1;
    }

    public boolean markReused(UUID runId, UUID sourceRunId) {
        return jdbc.update("""
                UPDATE ai_analysis_run SET
                    status = 'REUSED', error_code = NULL,
                    validated_output = (SELECT validated_output FROM ai_analysis_run WHERE id = :source),
                    quality_score = (SELECT quality_score FROM ai_analysis_run WHERE id = :source),
                    updated_at = now()
                WHERE id = :id AND status IN ('PENDING', 'RUNNING')
                """, new MapSqlParameterSource()
                .addValue("id", runId)
                .addValue("source", sourceRunId)) == 1;
    }

    public boolean attachJob(UUID runId, UUID jobId) {
        return jdbc.update("""
                UPDATE ai_analysis_run SET job_id = :jobId, updated_at = now()
                WHERE id = :id AND job_id IS NULL
                """, new MapSqlParameterSource()
                .addValue("id", runId)
                .addValue("jobId", jobId)) == 1;
    }

    public static String hashInput(String sanitizedText, String model, String promptVersion) {
        String input = model + "|" + promptVersion + "|" + sanitizedText;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private AiAnalysisRun map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AiAnalysisRun(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("submitted_by_member_id", UUID.class),
                rs.getObject("task_aggregate_id", UUID.class),
                rs.getString("task_text_hash"),
                rs.getString("input_snapshot"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("schema_version"),
                rs.getString("status"),
                rs.getInt("provider_calls"),
                rs.getString("raw_output"),
                rs.getString("validated_output"),
                rs.getString("usage"),
                rs.getObject("quality_score") == null ? null : rs.getDouble("quality_score"),
                rs.getString("error_code"),
                rs.getString("error_detail"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private MapSqlParameterSource runParams(AiAnalysisRun run) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", run.id());
        params.put("companyId", run.companyId());
        params.put("jobId", run.jobId());
        params.put("submittedBy", run.submittedByMemberId());
        params.put("taskAggregateId", run.taskAggregateId());
        params.put("taskTextHash", run.taskTextHash());
        params.put("inputSnapshot", run.inputSnapshotJson());
        params.put("model", run.model());
        params.put("promptVersion", run.promptVersion());
        params.put("schemaVersion", run.schemaVersion());
        params.put("status", run.status());
        return new MapSqlParameterSource(params);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
