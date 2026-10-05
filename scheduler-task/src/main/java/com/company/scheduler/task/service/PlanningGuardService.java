package com.company.scheduler.task.service;

import com.company.scheduler.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 公司排期 guard：影响可行性的写入（状态、工时、截止、依赖、剩余工时等）
 * 在同一事务内锁定并递增 revision，供排期方案版本校验。调用方必须处于事务中。
 */
@Service
public class PlanningGuardService {
    private final JdbcTemplate jdbc;

    public PlanningGuardService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 锁定 guard 行并递增 revision；guard 缺失说明公司初始化不完整，明确失败。 */
    public long lockAndBump(UUID companyId) {
        long revision = lock(companyId);
        Long next = jdbc.queryForObject(
                "UPDATE planning_guard SET revision = revision + 1 WHERE company_id = ? RETURNING revision",
                Long.class, companyId);
        return next == null ? revision + 1 : next;
    }

    public long lock(UUID companyId) {
        Long revision = jdbc.query(
                "SELECT revision FROM planning_guard WHERE company_id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getLong("revision") : null, companyId);
        if (revision == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "GUARD_NOT_INITIALIZED",
                    "公司排期数据未初始化，请联系管理员");
        }
        return revision;
    }
}
