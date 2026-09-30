package com.company.scheduler.identity.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 与 planning_guard 版本契约协作（TRD 9.1）：成员在职状态、项目成员与资源授权变化
 * 会影响排期可行性，必须在同一事务内递增公司 revision，使依赖旧快照的模拟方案失效。
 */
@Service
public class PlanningGuardRevision {
    private final JdbcTemplate jdbc;

    public PlanningGuardRevision(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void bump(UUID companyId) {
        jdbc.update("""
                INSERT INTO planning_guard(company_id, revision) VALUES (?, 1)
                ON CONFLICT (company_id) DO UPDATE SET revision = planning_guard.revision + 1
                """, companyId);
    }
}
