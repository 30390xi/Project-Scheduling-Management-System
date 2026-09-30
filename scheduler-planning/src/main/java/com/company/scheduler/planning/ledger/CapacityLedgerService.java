package com.company.scheduler.planning.ledger;

import com.company.scheduler.planning.capacity.CapacityBucketService;
import com.company.scheduler.planning.domain.CapacityPolicy;
import com.company.scheduler.planning.support.CompanyRepository;
import com.company.scheduler.shared.error.BusinessException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 容量账本写入。所有方法都要求调用方在同一事务内先锁公司 planning_guard（TRD 9.2 锁顺序）：
 * guard → 释放明细（按 ID 升序）→ 容量桶（按 (member_id, work_date) 升序）→ 分配明细。
 * 桶变更使用带条件的净变化 UPDATE，任何一步失败整个事务回滚，不出现"先释放再抢占"的中间态。
 */
@Service
public class CapacityLedgerService {

    /** One RESERVED fragment to write. All reservations in one call must belong to a single plan. */
    public record Reservation(UUID taskId, UUID memberId, UUID planId, LocalDate workDate, int minutes) {}

    private record ReleaseRow(UUID allocationId, UUID memberId, LocalDate workDate, int minutes) {}

    private record BucketKey(UUID memberId, LocalDate workDate) implements Comparable<BucketKey> {
        @Override
        public int compareTo(BucketKey other) {
            int byMember = memberId.compareTo(other.memberId);
            return byMember != 0 ? byMember : workDate.compareTo(other.workDate);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PlanningGuardRepository guard;
    private final CapacityBucketService buckets;
    private final CompanyRepository companies;

    public CapacityLedgerService(NamedParameterJdbcTemplate jdbc, PlanningGuardRepository guard,
                                 CapacityBucketService buckets, CompanyRepository companies) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.buckets = buckets;
        this.companies = companies;
    }

    /** Convenience for reserving one task's fragments on one member. */
    public void reserve(UUID companyId, UUID planId, UUID taskId, UUID memberId,
                        Map<LocalDate, Integer> minutesByDate) {
        apply(companyId, minutesByDate.entrySet().stream()
                .map(e -> new Reservation(taskId, memberId, planId, e.getKey(), e.getValue()))
                .toList(), List.of());
    }

    /** Convenience for releasing RESERVED rows. */
    public void release(UUID companyId, List<UUID> allocationIds) {
        apply(companyId, List.of(), allocationIds);
    }

    /** Atomically applies releases plus reservations inside the caller's transaction. */
    public void apply(UUID companyId, List<Reservation> reservations, List<UUID> releaseAllocationIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Capacity ledger writes require an active transaction");
        }
        validateReservations(companyId, reservations);
        var releases = loadReleasable(companyId, releaseAllocationIds);
        var inserts = aggregateInserts(reservations);

        var deltas = new TreeMap<BucketKey, Integer>();
        for (var release : releases) {
            deltas.merge(new BucketKey(release.memberId(), release.workDate()), -release.minutes(), Integer::sum);
        }
        for (var reservation : reservations) {
            deltas.merge(new BucketKey(reservation.memberId(), reservation.workDate()), reservation.minutes(), Integer::sum);
        }

        buckets.ensureBucketsFor(companyId, reservations.stream()
                .collect(java.util.stream.Collectors.groupingBy(Reservation::memberId, TreeMap::new,
                        java.util.stream.Collectors.mapping(Reservation::workDate,
                                java.util.stream.Collectors.toCollection(java.util.TreeSet::new)))));

        lockBuckets(companyId, deltas.keySet());
        for (var entry : deltas.entrySet()) {
            guardedAdjust(companyId, entry.getKey(), entry.getValue());
        }

        for (var release : releases) {
            int updated = jdbc.update("""
                    UPDATE task_allocation SET occupancy_state = 'RELEASED'
                     WHERE id = :id AND company_id = :companyId AND occupancy_state = 'RESERVED'
                    """, new MapSqlParameterSource()
                    .addValue("id", release.allocationId())
                    .addValue("companyId", companyId));
            if (updated != 1) {
                throw new IllegalStateException("Released row vanished inside transaction: " + release.allocationId());
            }
        }
        for (var insert : inserts) {
            jdbc.update("""
                    INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes,
                                                occupancy_state, execution_started, plan_id)
                    VALUES (:id, :companyId, :taskId, :memberId, :workDate, :minutes, 'RESERVED', false, :planId)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("companyId", companyId)
                    .addValue("taskId", insert.taskId())
                    .addValue("memberId", insert.memberId())
                    .addValue("workDate", insert.workDate())
                    .addValue("minutes", insert.minutes())
                    .addValue("planId", insert.planId()));
        }
        guard.bumpRevision(companyId);
    }

    private void validateReservations(UUID companyId, List<Reservation> reservations) {
        LocalDate today = companies.today(companyId);
        for (var reservation : reservations) {
            if (reservation.taskId() == null || reservation.memberId() == null || reservation.planId() == null) {
                throw invalidRequest("占用缺少任务、成员或方案标识");
            }
            if (reservation.minutes() <= 0 || reservation.minutes() % CapacityPolicy.SLOT_MINUTES != 0) {
                throw invalidRequest("占用分钟必须为 30 的正整数倍");
            }
            if (reservation.workDate().isBefore(today)) {
                throw invalidRequest("不可在历史日期占用容量");
            }
        }
        var planIds = reservations.stream().map(Reservation::planId).distinct().toList();
        if (planIds.size() > 1) {
            throw invalidRequest("一次占用必须属于同一份排期方案");
        }
    }

    private List<ReleaseRow> loadReleasable(UUID companyId, List<UUID> allocationIds) {
        if (allocationIds.isEmpty()) return List.of();
        var distinctIds = allocationIds.stream().distinct().sorted().toList();
        var rows = jdbc.query("""
                SELECT id, member_id, work_date, minutes, occupancy_state, execution_started
                  FROM task_allocation
                 WHERE company_id = :companyId AND id IN (:ids)
                 ORDER BY id
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("ids", distinctIds), (rs, i) -> new Object[] {
                        rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
                        rs.getObject("work_date", LocalDate.class), rs.getInt("minutes"),
                        rs.getString("occupancy_state"), rs.getBoolean("execution_started")});
        if (rows.size() != distinctIds.size()) {
            throw BusinessException.notFound();
        }
        var releases = new ArrayList<ReleaseRow>(rows.size());
        for (var row : rows) {
            String state = (String) row[4];
            boolean started = (Boolean) row[5];
            if (!"RESERVED".equals(state) || started) {
                throw new BusinessException(HttpStatus.CONFLICT, "ALLOCATION_NOT_RELEASABLE",
                        "仅未开始的 RESERVED 占用可释放");
            }
            releases.add(new ReleaseRow((UUID) row[0], (UUID) row[1], (LocalDate) row[2], (Integer) row[3]));
        }
        return releases;
    }

    private record InsertRow(UUID taskId, UUID memberId, LocalDate workDate, int minutes, UUID planId) {}

    /** One RESERVED row per (task, date): merges duplicate fragments and rejects mixed assignees. */
    private List<InsertRow> aggregateInserts(List<Reservation> reservations) {
        record TaskDay(UUID taskId, LocalDate workDate) {}
        var grouped = new LinkedHashMap<TaskDay, List<Reservation>>();
        for (var reservation : reservations) {
            grouped.computeIfAbsent(new TaskDay(reservation.taskId(), reservation.workDate()),
                    k -> new ArrayList<>()).add(reservation);
        }
        var inserts = new ArrayList<InsertRow>(grouped.size());
        for (var entry : grouped.entrySet()) {
            var members = entry.getValue().stream().map(Reservation::memberId).distinct().toList();
            if (members.size() > 1) {
                throw invalidRequest("同一任务同一日期出现多个负责人");
            }
            int minutes = entry.getValue().stream().mapToInt(Reservation::minutes).sum();
            var first = entry.getValue().get(0);
            inserts.add(new InsertRow(first.taskId(), first.memberId(), first.workDate(), minutes, first.planId()));
        }
        return inserts;
    }

    private void lockBuckets(UUID companyId, Collection<BucketKey> keys) {
        if (keys.isEmpty()) return;
        var sorted = keys.stream().sorted().toList();
        var placeholders = String.join(",", Collections.nCopies(sorted.size(), "(?, ?)"));
        var args = new ArrayList<Object>(sorted.size() * 2 + 1);
        args.add(companyId);
        for (var key : sorted) {
            args.add(key.memberId());
            args.add(key.workDate());
        }
        var locked = jdbc.getJdbcTemplate().query("""
                SELECT member_id, work_date FROM member_day_capacity
                 WHERE company_id = ? AND (member_id, work_date) IN (%s)
                 ORDER BY member_id, work_date
                 FOR UPDATE
                """.formatted(placeholders), (rs, i) -> new BucketKey(
                        rs.getObject("member_id", UUID.class), rs.getObject("work_date", LocalDate.class)),
                args.toArray());
        if (locked.size() != sorted.size()) {
            throw new IllegalStateException("Capacity buckets missing at lock time: expected "
                    + sorted.size() + " found " + locked.size());
        }
    }

    private void guardedAdjust(UUID companyId, BucketKey key, int delta) {
        if (delta == 0) return;
        int updated = jdbc.update("""
                UPDATE member_day_capacity
                   SET occupied_minutes = occupied_minutes + :delta,
                       version = version + 1,
                       updated_at = now()
                 WHERE company_id = :companyId AND member_id = :memberId AND work_date = :workDate
                   AND occupied_minutes + :delta >= 0
                   AND occupied_minutes + :delta <= capacity_minutes
                """, new MapSqlParameterSource()
                .addValue("delta", delta)
                .addValue("companyId", companyId)
                .addValue("memberId", key.memberId())
                .addValue("workDate", key.workDate()));
        if (updated == 1) return;
        Integer capacity = jdbc.query("""
                        SELECT capacity_minutes FROM member_day_capacity
                         WHERE company_id = :companyId AND member_id = :memberId AND work_date = :workDate
                        """, new MapSqlParameterSource()
                        .addValue("companyId", companyId)
                        .addValue("memberId", key.memberId())
                        .addValue("workDate", key.workDate()),
                (rs, i) -> rs.getInt("capacity_minutes")).stream().findFirst().orElse(null);
        if (capacity == null) {
            throw new IllegalStateException("Capacity bucket missing during adjust: " + key);
        }
        if (delta > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "CAPACITY_FULL",
                    "成员当日剩余容量不足，拒绝占用");
        }
        throw new BusinessException(HttpStatus.CONFLICT, "CAPACITY_BELOW_RETAINED",
                "占用高于可释放余量，拒绝该调整");
    }

    private static BusinessException invalidRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
