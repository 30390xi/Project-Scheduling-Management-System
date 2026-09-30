package com.company.scheduler.planning.capacity;

import com.company.scheduler.planning.domain.EffectiveDayCalculator;
import com.company.scheduler.planning.domain.EffectiveDayCalculator.EffectiveDay;
import com.company.scheduler.planning.support.CompanyRepository;
import com.company.scheduler.planning.support.PlanningLimits;
import com.company.scheduler.shared.error.BusinessException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日容量桶的生成与重算。桶行只由本服务与账本写入；
 * ensure 只补缺失行（绝不改写已有快照），recompute 仅作用于今天及未来并拒绝低于已保留占用的下调。
 * 调用方必须已持有公司 guard 并处于同一事务内。
 */
@Service
public class CapacityBucketService {

    public record MemberPolicy(UUID memberId, int standardMinutes, int dailyLimitMinutes) {}

    private record CompanyDay(boolean workdayFlag, long version) {}

    private record OverrideRow(Boolean workdayFlag, int leaveMinutes, int extraUnavailableMinutes, long version) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final CompanyRepository companies;

    public CapacityBucketService(NamedParameterJdbcTemplate jdbc, CompanyRepository companies) {
        this.jdbc = jdbc;
        this.companies = companies;
    }

    public MemberPolicy loadPolicy(UUID companyId, UUID memberId) {
        var rows = jdbc.query("""
                SELECT id, standard_minutes, daily_limit_minutes FROM company_member
                 WHERE company_id = :companyId AND id = :memberId
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("memberId", memberId), (rs, i) -> new MemberPolicy(
                        rs.getObject("id", UUID.class), rs.getInt("standard_minutes"),
                        rs.getInt("daily_limit_minutes")));
        return rows.stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    /** Read-only effective days for a contiguous range. */
    @Transactional(readOnly = true)
    public List<EffectiveDay> effectiveDays(UUID companyId, UUID memberId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return List.of();
        return computeDays(companyId, memberId, dates(from, to));
    }

    public List<EffectiveDay> computeDays(UUID companyId, UUID memberId, Collection<LocalDate> dates) {
        if (dates.isEmpty()) return List.of();
        var sorted = new TreeSet<>(dates);
        var policy = loadPolicy(companyId, memberId);
        var from = sorted.getFirst();
        var to = sorted.getLast();

        var companyDays = new HashMap<LocalDate, CompanyDay>();
        jdbc.query("""
                SELECT work_date, workday_flag, version FROM calendar_day
                 WHERE company_id = :companyId AND work_date BETWEEN :from AND :to
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("from", from)
                .addValue("to", to), rs -> {
            companyDays.put(rs.getObject("work_date", LocalDate.class),
                    new CompanyDay(rs.getBoolean("workday_flag"), rs.getLong("version")));
        });
        var overrides = new HashMap<LocalDate, OverrideRow>();
        jdbc.query("""
                SELECT work_date, workday_flag, leave_minutes, extra_unavailable_minutes, version
                  FROM member_calendar_override
                 WHERE company_id = :companyId AND member_id = :memberId
                   AND work_date BETWEEN :from AND :to
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("memberId", memberId)
                .addValue("from", from)
                .addValue("to", to), rs -> {
            overrides.put(rs.getObject("work_date", LocalDate.class), new OverrideRow(
                    (Boolean) rs.getObject("workday_flag"), rs.getInt("leave_minutes"),
                    rs.getInt("extra_unavailable_minutes"), rs.getLong("version")));
        });

        var result = new ArrayList<EffectiveDay>(sorted.size());
        for (var date : sorted) {
            var companyDay = companyDays.get(date);
            var override = overrides.get(date);
            result.add(EffectiveDayCalculator.compute(date,
                    companyDay == null ? null : companyDay.workdayFlag(),
                    companyDay == null ? null : companyDay.version(),
                    override == null ? null : new EffectiveDayCalculator.MemberDayOverride(
                            override.workdayFlag(), override.leaveMinutes(),
                            override.extraUnavailableMinutes(), override.version()),
                    policy.standardMinutes(), policy.dailyLimitMinutes()));
        }
        return result;
    }

    /** Creates missing bucket rows for [from, to]; past dates are clamped to today. Returns created count. */
    public int ensureBuckets(UUID companyId, UUID memberId, LocalDate from, LocalDate to) {
        LocalDate today = companies.today(companyId);
        if (to.isBefore(today)) return 0;
        LocalDate start = from.isBefore(today) ? today : from;
        var days = computeDays(companyId, memberId, dates(start, to));
        return insertMissing(companyId, memberId, days);
    }

    /** Creates missing bucket rows for several members' explicit dates. Past dates are ignored. */
    public void ensureBucketsFor(UUID companyId, Map<UUID, ? extends Collection<LocalDate>> byMember) {
        var today = companies.today(companyId);
        for (var memberEntry : new TreeMap<>(byMember).entrySet()) {
            var future = memberEntry.getValue().stream()
                    .filter(d -> !d.isBefore(today)).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            if (future.isEmpty()) continue;
            var days = computeDays(companyId, memberEntry.getKey(), future);
            insertMissing(companyId, memberEntry.getKey(), days);
        }
    }

    /** Recomputes today+future capacity from the authoritative calendar; clamps past dates away. */
    public void recomputeFuture(UUID companyId, UUID memberId, LocalDate from, LocalDate to) {
        LocalDate today = companies.today(companyId);
        if (to.isBefore(today)) return;
        LocalDate start = from.isBefore(today) ? today : from;
        recomputeDates(companyId, memberId, dates(start, to));
    }

    public void recomputeDates(UUID companyId, UUID memberId, Collection<LocalDate> dates) {
        var today = companies.today(companyId);
        var future = dates.stream().filter(d -> !d.isBefore(today)).sorted().toList();
        if (future.isEmpty()) return;
        var days = computeDays(companyId, memberId, future);
        for (var day : days) {
            var occupied = jdbc.query("""
                            SELECT occupied_minutes FROM member_day_capacity
                             WHERE company_id = :companyId AND member_id = :memberId AND work_date = :workDate
                            """, new MapSqlParameterSource()
                            .addValue("companyId", companyId)
                            .addValue("memberId", memberId)
                            .addValue("workDate", day.workDate()),
                    (rs, i) -> rs.getInt("occupied_minutes")).stream().findFirst().orElse(null);
            if (occupied != null && day.capacityMinutes() < occupied) {
                throw new BusinessException(HttpStatus.CONFLICT, "CAPACITY_BELOW_RETAINED",
                        "新容量低于当日已保留占用，拒绝直接下调");
            }
            if (occupied == null) {
                jdbc.update("""
                        INSERT INTO member_day_capacity(company_id, member_id, work_date,
                                                        capacity_minutes, calendar_version)
                        VALUES (:companyId, :memberId, :workDate, :capacity, :calendarVersion)
                        ON CONFLICT DO NOTHING
                        """, new MapSqlParameterSource()
                        .addValue("companyId", companyId)
                        .addValue("memberId", memberId)
                        .addValue("workDate", day.workDate())
                        .addValue("capacity", day.capacityMinutes())
                        .addValue("calendarVersion", day.calendarVersion()));
            } else {
                jdbc.update("""
                        UPDATE member_day_capacity
                           SET capacity_minutes = :capacity, calendar_version = :calendarVersion,
                               version = version + 1, updated_at = now()
                         WHERE company_id = :companyId AND member_id = :memberId AND work_date = :workDate
                        """, new MapSqlParameterSource()
                        .addValue("companyId", companyId)
                        .addValue("memberId", memberId)
                        .addValue("workDate", day.workDate())
                        .addValue("capacity", day.capacityMinutes())
                        .addValue("calendarVersion", day.calendarVersion()));
            }
        }
    }

    /** Recomputes explicit dates for every active member; bucket updates follow the (member, date) lock order. */
    public void recomputeDatesForAllMembers(UUID companyId, Collection<LocalDate> dates) {
        var today = companies.today(companyId);
        var future = dates.stream().filter(d -> !d.isBefore(today)).sorted().toList();
        if (future.isEmpty()) return;
        var memberIds = jdbc.query("""
                SELECT id FROM company_member WHERE company_id = :companyId AND status = 'ACTIVE' ORDER BY id
                """, new MapSqlParameterSource("companyId", companyId),
                (rs, i) -> rs.getObject("id", UUID.class));
        for (var memberId : memberIds) {
            recomputeDates(companyId, memberId, future);
        }
    }

    private int insertMissing(UUID companyId, UUID memberId, List<EffectiveDay> days) {
        var batch = new ArrayList<Object[]>(days.size());
        for (var day : days) {
            batch.add(new Object[] {companyId, memberId, day.workDate(), day.capacityMinutes(), day.calendarVersion()});
        }
        int[] results = jdbc.getJdbcTemplate().batchUpdate("""
                INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes, calendar_version)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, batch);
        int created = 0;
        for (int result : results) {
            if (result == 1) created++;
        }
        return created;
    }

    private static List<LocalDate> dates(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return List.of();
        long count = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        if (count > PlanningLimits.HORIZON_DAYS + 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "日期范围超出容量规划上限");
        }
        var result = new ArrayList<LocalDate>((int) count);
        for (var date = from; !date.isAfter(to); date = date.plusDays(1)) {
            result.add(date);
        }
        return result;
    }
}
