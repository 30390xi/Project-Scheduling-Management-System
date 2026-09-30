package com.company.scheduler.planning.capacity;

import com.company.scheduler.planning.ledger.PlanningGuardRepository;
import com.company.scheduler.planning.support.CompanyRepository;
import com.company.scheduler.planning.support.PlanningLimits;
import com.company.scheduler.shared.error.BusinessException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成员标准工时与每日任务容量调整。写入 company_member 容量字段后立即按权威日历重算未来桶；
 * 任何一天的新容量低于已保留占用时整单拒绝（CAPACITY_BELOW_RETAINED），不允许出现非法中间态。
 */
@Service
public class CapacityPolicyService {

    private final PlanningGuardRepository guard;
    private final CapacityBucketService buckets;
    private final CompanyRepository companies;
    private final NamedParameterJdbcTemplate jdbc;

    public CapacityPolicyService(PlanningGuardRepository guard, CapacityBucketService buckets,
                                 CompanyRepository companies, NamedParameterJdbcTemplate jdbc) {
        this.guard = guard;
        this.buckets = buckets;
        this.companies = companies;
        this.jdbc = jdbc;
    }

    @Transactional
    public void updatePolicy(UUID companyId, UUID memberId, int standardMinutes, int dailyLimitMinutes) {
        if (standardMinutes < 0 || standardMinutes > 1440 || standardMinutes % 30 != 0) {
            throw invalidRequest("标准工时必须为 0～1440 且为 30 的整数倍（分钟）");
        }
        if (dailyLimitMinutes < 0 || dailyLimitMinutes > standardMinutes || dailyLimitMinutes % 30 != 0) {
            throw invalidRequest("每日任务容量必须介于 0 与标准工时之间且为 30 的整数倍（分钟）");
        }
        guard.lockGuard(companyId);
        int updated = jdbc.update("""
                UPDATE company_member
                   SET standard_minutes = :standard, daily_limit_minutes = :dailyLimit,
                       version = version + 1, updated_at = now()
                 WHERE company_id = :companyId AND id = :memberId
                """, new MapSqlParameterSource()
                .addValue("standard", standardMinutes)
                .addValue("dailyLimit", dailyLimitMinutes)
                .addValue("companyId", companyId)
                .addValue("memberId", memberId));
        if (updated == 0) throw BusinessException.notFound();
        LocalDate today = companies.today(companyId);
        buckets.recomputeFuture(companyId, memberId, today, today.plusDays(PlanningLimits.HORIZON_DAYS));
        guard.bumpRevision(companyId);
    }

    private static BusinessException invalidRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
