package com.company.scheduler.planning.capacity;

import com.company.scheduler.planning.domain.EffectiveDayCalculator.EffectiveDay;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only C/U/R view; occupancy truth comes from the bucket row, capacity is derived from the authoritative calendar. */
@Service
public class CapacityQueryService {

    public record CapacityDay(UUID memberId, LocalDate workDate, boolean workday,
                              int standardMinutes, int dailyLimitMinutes,
                              int leaveMinutes, int extraUnavailableMinutes,
                              int capacityMinutes, int occupiedMinutes, int remainingMinutes,
                              long calendarVersion) {}

    private final CapacityBucketService buckets;
    private final NamedParameterJdbcTemplate jdbc;

    public CapacityQueryService(CapacityBucketService buckets, NamedParameterJdbcTemplate jdbc) {
        this.buckets = buckets;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CapacityDay> capacity(UUID companyId, UUID memberId, LocalDate from, LocalDate to) {
        List<EffectiveDay> days = buckets.effectiveDays(companyId, memberId, from, to);
        var policy = buckets.loadPolicy(companyId, memberId);
        var occupiedByDate = new HashMap<LocalDate, Integer>();
        if (!days.isEmpty()) {
            jdbc.query("""
                    SELECT work_date, occupied_minutes FROM member_day_capacity
                     WHERE company_id = :companyId AND member_id = :memberId
                       AND work_date BETWEEN :from AND :to
                    """, new MapSqlParameterSource()
                    .addValue("companyId", companyId)
                    .addValue("memberId", memberId)
                    .addValue("from", from)
                    .addValue("to", to), rs -> {
                occupiedByDate.put(rs.getObject("work_date", LocalDate.class), rs.getInt("occupied_minutes"));
            });
        }
        return days.stream().map(day -> {
            int occupied = occupiedByDate.getOrDefault(day.workDate(), 0);
            return new CapacityDay(memberId, day.workDate(), day.workday(),
                    policy.standardMinutes(), policy.dailyLimitMinutes(),
                    day.leaveMinutes(), day.extraUnavailableMinutes(),
                    day.capacityMinutes(), occupied, day.capacityMinutes() - occupied,
                    day.calendarVersion());
        }).toList();
    }
}
