package com.company.scheduler.agent.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 数据库作业队列。所有方法不自带事务边界：
 * enqueue 必须加入调用者业务事务（事务 Outbox 同理）；领取/完成使用独立短事务语义，
 * 由调用方（JobRunner）在事务外调用。
 */
@Repository
public class AsyncJobRepository {

    private static final String CLAIM_SQL = """
            UPDATE async_job SET
                status = 'RUNNING',
                lease_owner = :owner,
                lease_until = :leaseUntil,
                lease_generation = lease_generation + 1,
                attempts = attempts + 1,
                updated_at = now()
            WHERE id = (
                SELECT id FROM async_job
                WHERE status IN ('QUEUED', 'RETRY_WAIT') AND next_run_at <= :now
                ORDER BY next_run_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING id, company_id, job_type, aggregate_id, aggregate_version,
                      idempotency_key, attempts, max_attempts, lease_owner,
                      lease_generation, lease_until, payload
            """;

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<AsyncJob> JOB_MAPPER = (rs, i) -> new AsyncJob(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("job_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getLong("aggregate_version"),
            rs.getString("idempotency_key"),
            AsyncJobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            rs.getInt("max_attempts"),
            rs.getString("lease_owner"),
            rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
            rs.getLong("lease_generation"),
            rs.getTimestamp("next_run_at").toInstant(),
            rs.getString("payload") == null ? null : rs.getString("payload"),
            rs.getString("result") == null ? null : rs.getString("result"),
            rs.getString("last_error"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public AsyncJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EnqueueResult(UUID jobId, boolean created) {}

    /**
     * 幂等入队：(company_id, job_type, idempotency_key) 冲突时返回既有作业。
     * 必须在调用者事务内执行，与业务写入同生共死。
     */
    public EnqueueResult enqueue(UUID companyId, String jobType, UUID aggregateId,
            long aggregateVersion, String idempotencyKey, String payloadJson, int maxAttempts) {
        UUID id = UUID.randomUUID();
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("companyId", companyId)
                .addValue("jobType", jobType)
                .addValue("aggregateId", aggregateId)
                .addValue("aggregateVersion", aggregateVersion)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("payload", payloadJson == null ? "{}" : payloadJson)
                .addValue("maxAttempts", maxAttempts);
        int inserted = jdbc.update("""
                INSERT INTO async_job(id, company_id, job_type, aggregate_id, aggregate_version,
                                      idempotency_key, payload, max_attempts)
                VALUES (:id, :companyId, :jobType, :aggregateId, :aggregateVersion,
                        :idempotencyKey, :payload::jsonb, :maxAttempts)
                ON CONFLICT (company_id, job_type, idempotency_key) DO NOTHING
                """, params);
        if (inserted == 1) {
            return new EnqueueResult(id, true);
        }
        return new EnqueueResult(findByKey(companyId, jobType, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("idempotent enqueue conflict but existing job not found"))
                .id(), false);
    }

    public Optional<AsyncJob> findById(UUID jobId) {
        return queryOne("SELECT * FROM async_job WHERE id = :id", new MapSqlParameterSource("id", jobId));
    }

    public Optional<AsyncJob> findByKey(UUID companyId, String jobType, String idempotencyKey) {
        return queryOne("""
                SELECT * FROM async_job
                WHERE company_id = :companyId AND job_type = :jobType AND idempotency_key = :key
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("jobType", jobType)
                .addValue("key", idempotencyKey));
    }

    /**
     * 领取下一个到期作业：SKIP LOCKED 保证多实例并发领取互不阻塞且不重复。
     * attempts 在领取时递增，lease_generation 每次领取递增（fencing token）。
     */
    public Optional<ClaimedJob> claimNext(Instant now, String owner, Instant leaseUntil) {
        var params = new MapSqlParameterSource()
                .addValue("now", java.sql.Timestamp.from(now))
                .addValue("owner", owner)
                .addValue("leaseUntil", java.sql.Timestamp.from(leaseUntil));
        List<ClaimedJob> claimed = jdbc.query(CLAIM_SQL, params, (rs, i) -> new ClaimedJob(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("job_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getLong("aggregate_version"),
                rs.getString("idempotency_key"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("lease_owner"),
                rs.getLong("lease_generation"),
                rs.getTimestamp("lease_until").toInstant(),
                rs.getString("payload")));
        return claimed.isEmpty() ? Optional.empty() : Optional.of(claimed.get(0));
    }

    /** fencing 条件完成：只有当前租约持有者能写入成功结果。 */
    public boolean complete(UUID jobId, String owner, long leaseGeneration, String resultJson) {
        return jdbc.update("""
                UPDATE async_job SET status = 'SUCCEEDED', result = :result::jsonb,
                       lease_owner = NULL, lease_until = NULL, last_error = NULL, updated_at = now()
                WHERE id = :id AND lease_owner = :owner AND lease_generation = :generation
                      AND status = 'RUNNING'
                """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("owner", owner)
                .addValue("generation", leaseGeneration)
                .addValue("result", resultJson)) == 1;
    }

    /**
     * 失败处理：永久失败或 attempts 已达上限置 FAILED，否则 RETRY_WAIT 并按 nextRunAt 退避。
     * @return 更新后的状态；租约丢失返回 empty
     */
    public Optional<AsyncJobStatus> markAttemptFailed(UUID jobId, String owner, long leaseGeneration,
            String errorCode, Instant nextRunAt, boolean permanentFailure) {
        List<String> status = jdbc.query("""
                UPDATE async_job SET
                    last_error = :error,
                    status = CASE WHEN :forceFailed OR attempts >= max_attempts
                                  THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    next_run_at = CASE WHEN :forceFailed OR attempts >= max_attempts
                                       THEN next_run_at ELSE :nextRunAt END,
                    lease_owner = NULL, lease_until = NULL, updated_at = now()
                WHERE id = :id AND lease_owner = :owner AND lease_generation = :generation
                      AND status = 'RUNNING'
                RETURNING status
                """, new MapSqlParameterSource()
                .addValue("error", truncate(errorCode, 500))
                .addValue("nextRunAt", java.sql.Timestamp.from(nextRunAt))
                .addValue("forceFailed", permanentFailure)
                .addValue("id", jobId)
                .addValue("owner", owner)
                .addValue("generation", leaseGeneration), (rs, i) -> rs.getString(1));
        return status.isEmpty() ? Optional.empty() : Optional.of(AsyncJobStatus.valueOf(status.get(0)));
    }

    /** 续租，同时作为 fencing 检查：返回 false 表示租约已被回收或作业已取消。 */
    public boolean renewLease(UUID jobId, String owner, long leaseGeneration, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE async_job SET lease_until = :leaseUntil, updated_at = now()
                WHERE id = :id AND lease_owner = :owner AND lease_generation = :generation
                      AND status = 'RUNNING' AND lease_until > now()
                """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("owner", owner)
                .addValue("generation", leaseGeneration)
                .addValue("leaseUntil", java.sql.Timestamp.from(leaseUntil))) == 1;
    }

    /** 回收租约过期的 RUNNING 作业，重新入队等待下一次领取。 */
    public int reclaimExpired(Instant now) {
        return jdbc.update("""
                UPDATE async_job SET status = 'QUEUED', next_run_at = :now,
                       lease_owner = NULL, lease_until = NULL, updated_at = now()
                WHERE status = 'RUNNING' AND lease_until < :now
                """, new MapSqlParameterSource("now", java.sql.Timestamp.from(now)));
    }

    /** 取消只允许 QUEUED/RETRY_WAIT/RUNNING；RUNNING 的在途结果会被 fencing 拒绝。 */
    public boolean cancel(UUID companyId, UUID jobId) {
        return jdbc.update("""
                UPDATE async_job SET status = 'CANCELED', lease_owner = NULL, lease_until = NULL,
                       updated_at = now()
                WHERE id = :id AND company_id = :companyId
                      AND status IN ('QUEUED', 'RETRY_WAIT', 'RUNNING')
                """, new MapSqlParameterSource()
                .addValue("id", jobId)
                .addValue("companyId", companyId)) == 1;
    }

    public record QueueStats(long queued, long retryWait, long running, long oldestPendingSeconds) {}

    public QueueStats stats(Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'QUEUED') AS queued,
                       count(*) FILTER (WHERE status = 'RETRY_WAIT') AS retry_wait,
                       count(*) FILTER (WHERE status = 'RUNNING') AS running,
                       COALESCE(EXTRACT(EPOCH FROM :now - MIN(next_run_at))
                                FILTER (WHERE status IN ('QUEUED', 'RETRY_WAIT')), 0) AS oldest
                FROM async_job
                """, new MapSqlParameterSource("now", java.sql.Timestamp.from(now)),
                (rs, i) -> new QueueStats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    private Optional<AsyncJob> queryOne(String sql, MapSqlParameterSource params) {
        List<AsyncJob> jobs = jdbc.query(sql, params, JOB_MAPPER);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(jobs.get(0));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
