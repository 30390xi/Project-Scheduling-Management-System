package com.company.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.planning.calendar.CalendarService;
import com.company.scheduler.planning.capacity.CapacityBucketService;
import com.company.scheduler.shared.error.BusinessException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 容量桶生成与重算：默认工作日规则、节假日、请假、额外不可用，以及低于占用的下调拒绝。 */
class CapacityBucketServiceIT extends PlanningPostgresIT {

    private final CapacityBucketService buckets = bucketService();
    private final CalendarService calendar = calendarService();

    private LocalDate nextMonday(LocalDate from) {
        LocalDate date = from;
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    @Test
    void generatesBucketsFromWeekdayRuleHolidayAndMemberOverrides() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        LocalDate monday = nextMonday(today(company));

        int created = buckets.ensureBuckets(company, member, monday, monday.plusDays(6));
        assertThat(created).isEqualTo(7);

        tx.executeWithoutResult(status -> calendar.putCompanyDays(company, java.util.List.of(
                new CalendarService.CompanyDayInput(monday.plusDays(1), false, "国庆调休前补假"))));
        tx.executeWithoutResult(status -> calendar.putMemberOverrides(company, member, java.util.List.of(
                new CalendarService.MemberDayInput(monday.plusDays(4), null, 240, 0, "半天病假"),
                new CalendarService.MemberDayInput(monday.plusDays(3), null, 0, 60, "下午外部会议"),
                new CalendarService.MemberDayInput(monday.plusDays(6), true, 0, 0, "调休补班"))));

        assertThat(bucketCapacity(company, member, monday)).isEqualTo(360);
        assertThat(bucketCapacity(company, member, monday.plusDays(1))).isZero();
        assertThat(bucketCapacity(company, member, monday.plusDays(2))).isEqualTo(360);
        assertThat(bucketCapacity(company, member, monday.plusDays(3))).isEqualTo(300);
        assertThat(bucketCapacity(company, member, monday.plusDays(4))).isEqualTo(240);
        assertThat(bucketCapacity(company, member, monday.plusDays(5))).isZero();
        assertThat(bucketCapacity(company, member, monday.plusDays(6))).isEqualTo(360);

        assertThat(buckets.ensureBuckets(company, member, monday, monday.plusDays(6))).isZero();
    }

    @Test
    void recomputeRejectsCapacityBelowOccupiedButAllowsIncrease() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        UUID task = UUID.randomUUID();
        LocalDate monday = nextMonday(today(company));

        tx.executeWithoutResult(status -> calendar.putMemberOverrides(company, member, java.util.List.of(
                new CalendarService.MemberDayInput(monday, null, 240, 0, "上午请假"))));
        tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledgerService().reserve(company, plan, task, member, java.util.Map.of(monday, 240));
        });
        assertThat(bucketOccupied(company, member, monday)).isEqualTo(240);

        // 下调到 180 分钟低于已占用 240 → 整单拒绝，桶保持 240。
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> calendar.putMemberOverrides(company, member,
                java.util.List.of(new CalendarService.MemberDayInput(monday, null, 300, 0, "加长请假")))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("CAPACITY_BELOW_RETAINED");
        assertThat(bucketCapacity(company, member, monday)).isEqualTo(240);

        // 恢复满容量允许。
        tx.executeWithoutResult(status -> calendar.putMemberOverrides(company, member, java.util.List.of(
                new CalendarService.MemberDayInput(monday, null, 0, 0, "取消请假"))));
        assertThat(bucketCapacity(company, member, monday)).isEqualTo(360);
    }

    @Test
    void pastDateSnapshotsAreNeitherCreatedNorRewritten() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        LocalDate today = today(company);
        LocalDate past = today.minusDays(3);

        assertThat(buckets.ensureBuckets(company, member, past, today)).isEqualTo(1);
        Integer pastRows = jdbc.queryForObject("""
                SELECT count(*) FROM member_day_capacity
                 WHERE company_id = ? AND member_id = ? AND work_date < ?
                """, Integer.class, company, member, today);
        assertThat(pastRows).isZero();

        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, member, past);
        // 历史日期的日历修改直接拒绝。
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> calendar.putMemberOverrides(company, member,
                java.util.List.of(new CalendarService.MemberDayInput(past, null, 240, 0, "补录")))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_REQUEST");

        tx.executeWithoutResult(status -> policyService().updatePolicy(company, member, 600, 420));
        Integer pastCapacity = jdbc.queryForObject("""
                SELECT capacity_minutes FROM member_day_capacity
                 WHERE company_id = ? AND member_id = ? AND work_date = ?
                """, Integer.class, company, member, past);
        assertThat(pastCapacity).isEqualTo(360);
    }

    @Test
    void companyCalendarChangeRecomputesAllMembersAndBumpsRevision() {
        UUID company = newCompany();
        UUID memberA = newMember(company, "MEMBER");
        UUID memberB = newMember(company, "MEMBER");
        LocalDate monday = nextMonday(today(company));
        buckets.ensureBuckets(company, memberA, monday, monday);
        buckets.ensureBuckets(company, memberB, monday, monday);
        long revisionBefore = guardRepository().currentRevision(company);

        tx.executeWithoutResult(status -> calendar.putCompanyDays(company, java.util.List.of(
                new CalendarService.CompanyDayInput(monday, false, "临时假日"))));

        assertThat(bucketCapacity(company, memberA, monday)).isZero();
        assertThat(bucketCapacity(company, memberB, monday)).isZero();
        assertThat(guardRepository().currentRevision(company)).isGreaterThan(revisionBefore);
    }

    @Test
    void capacityPolicyUpdateRecomputesAndRejectsBelowOccupied() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        LocalDate monday = nextMonday(today(company));
        tx.executeWithoutResult(status -> {
            guardRepository().lockGuard(company);
            ledgerService().reserve(company, plan, UUID.randomUUID(), member, java.util.Map.of(monday, 360));
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                policyService().updatePolicy(company, member, 480, 300)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("CAPACITY_BELOW_RETAINED");
        var policyRow = jdbc.queryForMap(
                "SELECT standard_minutes, daily_limit_minutes FROM company_member WHERE id = ?", member);
        assertThat(policyRow.get("daily_limit_minutes")).isEqualTo(360);

        tx.executeWithoutResult(status -> policyService().updatePolicy(company, member, 600, 420));
        assertThat(bucketCapacity(company, member, monday)).isEqualTo(420);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                policyService().updatePolicy(company, member, 300, 420)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_REQUEST");
    }
}
