package com.company.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.planning.calendar.CalendarService;
import com.company.scheduler.planning.ledger.CapacityLedgerService;
import com.company.scheduler.shared.error.BusinessException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 真实 PostgreSQL 并发抢占与回滚验证（AC-11/AC-12/AC-18 的容量部分）：
 * 任何入口（自动、人工、并发竞争）都不能超容量分配，任何失败都整体回滚。
 */
class CapacityLedgerConcurrencyIT extends PlanningPostgresIT {

    private LocalDate nextMonday(LocalDate from) {
        LocalDate date = from;
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private void seedReservation(UUID company, UUID plan, UUID task, UUID member, Map<LocalDate, Integer> minutes) {
        tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledgerService().reserve(company, plan, task, member, minutes);
        });
    }

    private void ensureBuckets(UUID company, UUID member, LocalDate from, LocalDate to) {
        tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            bucketService().ensureBuckets(company, member, from, to);
        });
    }

    /** AC-11：剩余 120 分钟，20 个并发事务各抢 120 分钟，至多一个成功，无超配、无重复占用。 */
    @Test
    void twentyConcurrentReservationsAdmitExactlyOne() throws Exception {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID seedPlan = newCommittedPlan(company, member);
        LocalDate monday = nextMonday(today(company));
        seedReservation(company, seedPlan, UUID.randomUUID(), member, Map.of(monday, 240));

        int threads = 20;
        var barrier = new CyclicBarrier(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                UUID plan = newCommittedPlan(company, member);
                UUID task = UUID.randomUUID();
                futures.add(pool.submit((Callable<String>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    tx.executeWithoutResult(status -> {
                        guardRepository().lockGuard(company);
                        ledgerService().reserve(company, plan, task, member, Map.of(monday, 120));
                    });
                    return "committed";
                }));
            }
            int successes = 0;
            int capacityFull = 0;
            for (var future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException e) {
                    var cause = e.getCause();
                    assertThat(cause).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) cause).code()).isEqualTo("CAPACITY_FULL");
                    capacityFull++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(capacityFull).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(bucketCapacity(company, member, monday)).isEqualTo(360);
        assertThat(bucketOccupied(company, member, monday)).isEqualTo(360);
        Integer reservedRows = jdbc.queryForObject("""
                SELECT count(*) FROM task_allocation
                 WHERE company_id = ? AND member_id = ? AND work_date = ? AND occupancy_state = 'RESERVED'
                """, Integer.class, company, member, monday);
        assertThat(reservedRows).isEqualTo(2);
    }

    /** 多日占用：最后一天放不下时全事务回滚，前面日期不留任何残留（TRD 17.2）。 */
    @Test
    void multiDayReservationFailsAtomicallyWhenLaterDayCannotFit() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        LocalDate monday = nextMonday(today(company));
        ensureBuckets(company, member, monday, monday.plusDays(1));
        tx.executeWithoutResult(status -> calendarService().putMemberOverrides(company, member, List.of(
                new CalendarService.MemberDayInput(monday.plusDays(1), null, 420, 0, "下午半天假"))));

        var ledger = ledgerService();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.reserve(company, plan, UUID.randomUUID(), member,
                    Map.of(monday, 60, monday.plusDays(1), 120));
        })).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("CAPACITY_FULL");

        assertThat(bucketOccupied(company, member, monday)).isZero();
        assertThat(bucketOccupied(company, member, monday.plusDays(1))).isZero();
        Integer allocations = jdbc.queryForObject(
                "SELECT count(*) FROM task_allocation WHERE company_id = ?", Integer.class, company);
        assertThat(allocations).isZero();
    }

    /** 改派事务中途失败：旧占用原样保留，新占用不存在（TRD 17.2）。 */
    @Test
    void reassignmentRollsBackCompletelyWhenNewMemberCannotFit() {
        UUID company = newCompany();
        UUID memberA = newMember(company, "MEMBER");
        UUID memberB = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, memberA);
        LocalDate monday = nextMonday(today(company));
        UUID task = UUID.randomUUID();
        seedReservation(company, plan, task, memberA, Map.of(monday, 300));
        tx.executeWithoutResult(status -> calendarService().putMemberOverrides(company, memberB, List.of(
                new CalendarService.MemberDayInput(monday, null, 420, 0, "半天假"))));

        UUID allocationId = jdbc.queryForObject("""
                SELECT id FROM task_allocation WHERE company_id = ? AND task_id = ? AND occupancy_state = 'RESERVED'
                """, UUID.class, company, task);

        var ledger = ledgerService();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.apply(company,
                    List.of(new CapacityLedgerService.Reservation(task, memberB, plan, monday, 300)),
                    List.of(allocationId));
        })).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("CAPACITY_FULL");

        String state = jdbc.queryForObject(
                "SELECT occupancy_state FROM task_allocation WHERE id = ?", String.class, allocationId);
        assertThat(state).isEqualTo("RESERVED");
        assertThat(bucketOccupied(company, memberA, monday)).isEqualTo(300);
        Integer memberBRows = jdbc.queryForObject(
                "SELECT count(*) FROM task_allocation WHERE member_id = ?", Integer.class, memberB);
        assertThat(memberBRows).isZero();
    }

    /** 同事务内"先释放后抢占"的净变化改派一次成功，新旧桶同时校验。 */
    @Test
    void reassignmentAtomicallyMovesOccupancyBetweenMembers() {
        UUID company = newCompany();
        UUID memberA = newMember(company, "MEMBER");
        UUID memberB = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, memberA);
        LocalDate monday = nextMonday(today(company));
        UUID task = UUID.randomUUID();
        seedReservation(company, plan, task, memberA, Map.of(monday, 300));
        long revisionBefore = guardRepository().currentRevision(company);

        UUID allocationId = jdbc.queryForObject("""
                SELECT id FROM task_allocation WHERE company_id = ? AND task_id = ? AND occupancy_state = 'RESERVED'
                """, UUID.class, company, task);

        tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledgerService().apply(company,
                    List.of(new CapacityLedgerService.Reservation(task, memberB, plan, monday, 300)),
                    List.of(allocationId));
        });

        String oldState = jdbc.queryForObject(
                "SELECT occupancy_state FROM task_allocation WHERE id = ?", String.class, allocationId);
        assertThat(oldState).isEqualTo("RELEASED");
        assertThat(bucketOccupied(company, memberA, monday)).isZero();
        assertThat(bucketOccupied(company, memberB, monday)).isEqualTo(300);
        Integer newRows = jdbc.queryForObject("""
                SELECT count(*) FROM task_allocation
                 WHERE company_id = ? AND task_id = ? AND member_id = ? AND occupancy_state = 'RESERVED'
                """, Integer.class, company, task, memberB);
        assertThat(newRows).isEqualTo(1);
        assertThat(guardRepository().currentRevision(company)).isGreaterThan(revisionBefore);
    }

    @Test
    void releaseRulesRejectRetainedStartedPastAndRepeatedReleases() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        UUID task = UUID.randomUUID();
        LocalDate monday = nextMonday(today(company));
        seedReservation(company, plan, task, member, Map.of(monday, 120));
        UUID allocationId = jdbc.queryForObject("""
                SELECT id FROM task_allocation WHERE company_id = ? AND task_id = ? AND occupancy_state = 'RESERVED'
                """, UUID.class, company, task);

        var ledger = ledgerService();
        // 历史日期、非 30 倍数分钟直接拒绝。
        LocalDate today = today(company);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.reserve(company, plan, UUID.randomUUID(), member, Map.of(today.minusDays(1), 60));
        })).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_REQUEST");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.reserve(company, plan, UUID.randomUUID(), member, Map.of(monday, 45));
        })).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_REQUEST");

        tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.release(company, List.of(allocationId));
        });
        // 已释放的占用不能再次释放。
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.release(company, List.of(allocationId));
        })).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("ALLOCATION_NOT_RELEASABLE");

        // 已开始执行的占用必须走完成/确认流程，不能直接释放。
        var startedReleaseId = new java.util.concurrent.atomic.AtomicReference<UUID>();
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                    company, member, monday.plusDays(1));
            UUID startedId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes,
                                                occupancy_state, execution_started, plan_id)
                    VALUES (?, ?, ?, ?, ?, 60, 'RESERVED', true, ?)
                    """, startedId, company, task, member, monday.plusDays(1), plan);
            jdbc.update("UPDATE member_day_capacity SET occupied_minutes = 60 "
                    + "WHERE company_id = ? AND member_id = ? AND work_date = ?", company, member, monday.plusDays(1));
            startedReleaseId.set(startedId);
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledger.release(company, List.of(startedReleaseId.get()));
        })).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("ALLOCATION_NOT_RELEASABLE");
    }

    /** 日历下调与新增占用并发：guard 串行化后恰好一方胜出，最终状态始终满足 0 ≤ U ≤ C。 */
    @Test
    void concurrentCalendarReductionAndReservationStayConsistent() throws Exception {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        LocalDate monday = nextMonday(today(company));
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var reserveTask = pool.submit((Callable<String>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                tx.executeWithoutResult(status -> {
                    guardRepository().lockGuard(company);
                    ledgerService().reserve(company, plan, UUID.randomUUID(), member, Map.of(monday, 360));
                });
                return "reserved";
            });
            var reduceTask = pool.submit((Callable<String>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                tx.executeWithoutResult(status -> calendarService().putMemberOverrides(company, member, List.of(
                        new CalendarService.MemberDayInput(monday, null, 480, 0, "全天请假"))));
                return "reduced";
            });
            String reserveOutcome = null;
            String reduceOutcome = null;
            try {
                reserveOutcome = reserveTask.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(((BusinessException) e.getCause()).code()).isEqualTo("CAPACITY_FULL");
            }
            try {
                reduceOutcome = reduceTask.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(((BusinessException) e.getCause()).code()).isEqualTo("CAPACITY_BELOW_RETAINED");
            }

            int capacity = bucketCapacity(company, member, monday);
            int occupied = bucketOccupied(company, member, monday);
            assertThat(occupied).isBetween(0, capacity);
            // 恰好一方胜出：先占满则下调被拒，先下调则新增被拒。
            assertThat(reserveOutcome != null ^ reduceOutcome != null).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
