package com.company.scheduler.agent.job;

import com.company.scheduler.shared.contract.JobDispatcher;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * JobDispatcher 契约的数据库实现。
 * enqueue 使用 NamedParameterJdbcTemplate，自然加入调用者当前事务：
 * 业务写入回滚时，作业也一并回滚（可靠事务边界）。
 */
@Component
public class ReliableJobDispatcher implements JobDispatcher {

    private final AsyncJobRepository repository;
    private final AgentJobProperties properties;

    public ReliableJobDispatcher(AsyncJobRepository repository, AgentJobProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public UUID enqueue(UUID companyId, String jobType, UUID aggregateId, long aggregateVersion,
            String idempotencyKey) {
        if (companyId == null || jobType == null || jobType.isBlank() || jobType.length() > 64) {
            throw new IllegalArgumentException("companyId and jobType (1..64 chars) are required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey (1..128 chars) is required");
        }
        String payload = "{\"aggregateVersion\":" + aggregateVersion + "}";
        return repository.enqueue(companyId, jobType, aggregateId, aggregateVersion,
                idempotencyKey, payload, properties.defaultMaxAttempts()).jobId();
    }
}
