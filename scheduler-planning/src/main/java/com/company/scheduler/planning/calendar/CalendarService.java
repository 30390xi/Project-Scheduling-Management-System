package com.company.scheduler.planning.calendar;

import com.company.scheduler.planning.capacity.CapacityBucketService;
import com.company.scheduler.planning.ledger.PlanningGuardRepository;
import com.company.scheduler.planning.support.CompanyRepository;
import com.company.scheduler.planning.support.PlanningLimits;
import com.company.scheduler.shared.error.BusinessException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公司工作日历与成员请假/覆盖。仅允许维护今天及未来的日期；
 * 变更在同一 guard 事务内重算受影响容量桶，任何一天低于已保留占用即整单拒绝。
 * 已过去日期的桶快照不随日历变化重写（PRD 10.6）。
 */
@Service
public class CalendarService {

    public record CompanyDayInput(LocalDate workDate, boolean workdayFlag, String note) {}

    public record MemberDayInput(LocalDate workDate, Boolean workdayFlag,
                                 Integer leaveMinutes, Integer extraUnavailableMinutes, String reason) {}

    public record CompanyDayView(LocalDate workDate, boolean workdayFlag, String source, String note) {}

    public record MemberDayView(LocalDate workDate, Boolean workdayFlag,
                                int leaveMinutes, int extraUnavailableMinutes, String reason) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final PlanningGuardRepository guard;
    private final CapacityBucketService buckets;
    private final CompanyRepository companies;

    public CalendarService(NamedParameterJdbcTemplate jdbc, PlanningGuardRepository guard,
                           CapacityBucketService buckets, CompanyRepository companies) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.buckets = buckets;
        this.companies = companies;
    }

    @Transactional(readOnly = true)
    public List<CompanyDayView> companyDays(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT work_date, workday_flag, source, note FROM calendar_day
                 WHERE company_id = :companyId AND work_date BETWEEN :from AND :to
                 ORDER BY work_date
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("from", from)
                .addValue("to", to), (rs, i) -> new CompanyDayView(
                        rs.getObject("work_date", LocalDate.class), rs.getBoolean("workday_flag"),
                        rs.getString("source"), rs.getString("note")));
    }

    @Transactional
    public void putCompanyDays(UUID companyId, List<CompanyDayInput> days) {
        var dates = validateDates(days.stream().map(CompanyDayInput::workDate).toList(), companyId);
        guard.lockGuard(companyId);
        for (var day : days) {
            jdbc.update("""
                    INSERT INTO calendar_day(company_id, work_date, workday_flag, source, note, version)
                    VALUES (:companyId, :workDate, :workdayFlag, :source, :note, 1)
                    ON CONFLICT (company_id, work_date) DO UPDATE
                       SET workday_flag = EXCLUDED.workday_flag, source = EXCLUDED.source,
                           note = EXCLUDED.note, version = calendar_day.version + 1, updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("companyId", companyId)
                    .addValue("workDate", day.workDate())
                    .addValue("workdayFlag", day.workdayFlag())
                    .addValue("source", day.workdayFlag() ? "ADJUSTED_WORKDAY" : "HOLIDAY")
                    .addValue("note", day.note() == null ? "" : day.note()));
        }
        buckets.recomputeDatesForAllMembers(companyId, dates);
        guard.bumpRevision(companyId);
    }

    @Transactional(readOnly = true)
    public List<MemberDayView> memberDays(UUID companyId, UUID memberId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT work_date, workday_flag, leave_minutes, extra_unavailable_minutes, reason
                  FROM member_calendar_override
                 WHERE company_id = :companyId AND member_id = :memberId
                   AND work_date BETWEEN :from AND :to
                 ORDER BY work_date
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("memberId", memberId)
                .addValue("from", from)
                .addValue("to", to), (rs, i) -> new MemberDayView(
                        rs.getObject("work_date", LocalDate.class), (Boolean) rs.getObject("workday_flag"),
                        rs.getInt("leave_minutes"), rs.getInt("extra_unavailable_minutes"),
                        rs.getString("reason")));
    }

    @Transactional
    public void putMemberOverrides(UUID companyId, UUID memberId, List<MemberDayInput> overrides) {
        var dates = validateDates(overrides.stream().map(MemberDayInput::workDate).toList(), companyId);
        var policy = buckets.loadPolicy(companyId, memberId);
        for (var override : overrides) {
            int leave = override.leaveMinutes() == null ? 0 : override.leaveMinutes();
            int extra = override.extraUnavailableMinutes() == null ? 0 : override.extraUnavailableMinutes();
            if (leave > policy.standardMinutes()) {
                throw invalidRequest("请假时长不能超过标准工时");
            }
        }
        guard.lockGuard(companyId);
        for (var override : overrides) {
            jdbc.update("""
                    INSERT INTO member_calendar_override(company_id, member_id, work_date, workday_flag,
                                                          leave_minutes, extra_unavailable_minutes, reason, version)
                    VALUES (:companyId, :memberId, :workDate, :workdayFlag, :leave, :extra, :reason, 1)
                    ON CONFLICT (company_id, member_id, work_date) DO UPDATE
                       SET workday_flag = EXCLUDED.workday_flag, leave_minutes = EXCLUDED.leave_minutes,
                           extra_unavailable_minutes = EXCLUDED.extra_unavailable_minutes,
                           reason = EXCLUDED.reason, version = member_calendar_override.version + 1,
                           updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("companyId", companyId)
                    .addValue("memberId", memberId)
                    .addValue("workDate", override.workDate())
                    .addValue("workdayFlag", override.workdayFlag())
                    .addValue("leave", override.leaveMinutes() == null ? 0 : override.leaveMinutes())
                    .addValue("extra", override.extraUnavailableMinutes() == null
                            ? 0 : override.extraUnavailableMinutes())
                    .addValue("reason", override.reason() == null ? "" : override.reason()));
        }
        buckets.recomputeDates(companyId, memberId, dates);
        guard.bumpRevision(companyId);
    }

    private List<LocalDate> validateDates(List<LocalDate> dates, UUID companyId) {
        if (dates == null || dates.isEmpty() || dates.size() > PlanningLimits.MAX_CALENDAR_BATCH) {
            throw invalidRequest("单次日历维护条目数必须在 1～" + PlanningLimits.MAX_CALENDAR_BATCH + " 之间");
        }
        var distinct = new LinkedHashSet<LocalDate>();
        LocalDate today = companies.today(companyId);
        LocalDate horizon = today.plusDays(PlanningLimits.HORIZON_DAYS);
        for (var date : dates) {
            if (date == null) throw invalidRequest("日期不能为空");
            if (date.isBefore(today)) throw invalidRequest("历史日期不可修改日历");
            if (date.isAfter(horizon)) throw invalidRequest("日期超出容量规划范围（180 天）");
            if (!distinct.add(date)) throw invalidRequest("同一次提交中日期重复");
        }
        return List.copyOf(distinct);
    }

    private static BusinessException invalidRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
