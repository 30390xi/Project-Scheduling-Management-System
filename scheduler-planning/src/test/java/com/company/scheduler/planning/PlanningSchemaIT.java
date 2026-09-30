package com.company.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 数据库约束与延迟对账触发器：绕过应用层直接写库也必须被拒绝。 */
class PlanningSchemaIT extends PlanningPostgresIT {

    @Test
    void rejectsInvalidBucketsAllocationsAndSkillRowsImmediately() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        LocalDate today = today(company);
        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, member, today);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE member_day_capacity SET occupied_minutes = 390 WHERE company_id = ? AND member_id = ? AND work_date = ?",
                company, member, today)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE member_day_capacity SET capacity_minutes = 350 WHERE company_id = ? AND member_id = ? AND work_date = ?",
                company, member, today)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO skill(company_id, id, code, name) VALUES (?, ?, 'java_core', 'Java')",
                company, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        var skillId = UUID.randomUUID();
        jdbc.update("INSERT INTO skill(company_id, id, code, name) VALUES (?, ?, 'JAVA', 'Java')", company, skillId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO member_skill(company_id, member_id, skill_id, level, self_reported) VALUES (?, ?, ?, 6, false)",
                company, member, skillId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO member_skill(company_id, member_id, skill_id, level, self_reported, certified_by, certified_at) "
                        + "VALUES (?, ?, ?, 3, false, NULL, NULL)",
                company, member, skillId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deferredTriggerRejectsBucketCountEditWithoutMatchingDetails() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        LocalDate date = today(company).plusDays(1);
        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, member, date);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                jdbc.update("UPDATE member_day_capacity SET occupied_minutes = occupied_minutes + 60 "
                        + "WHERE company_id = ? AND member_id = ? AND work_date = ?", company, member, date)))
                .isInstanceOf(RuntimeException.class);
        assertThat(bucketOccupied(company, member, date)).isZero();
    }

    @Test
    void deferredTriggerRejectsAllocationWithoutMatchingBucketUpdate() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        LocalDate date = today(company).plusDays(1);
        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, member, date);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes, occupancy_state, plan_id)
                VALUES (?, ?, ?, ?, ?, 60, 'RESERVED', ?)
                """, UUID.randomUUID(), company, UUID.randomUUID(), member, date, plan)))
                .isInstanceOf(RuntimeException.class);
        Integer allocations = jdbc.queryForObject(
                "SELECT count(*) FROM task_allocation WHERE company_id = ?", Integer.class, company);
        assertThat(allocations).isZero();
        assertThat(bucketOccupied(company, member, date)).isZero();
    }

    @Test
    void deferredTriggerRejectsTwoReservedAssigneesForOneTask() {
        UUID company = newCompany();
        UUID memberA = newMember(company, "MEMBER");
        UUID memberB = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, memberA);
        UUID task = UUID.randomUUID();
        LocalDate date1 = today(company).plusDays(1);
        LocalDate date2 = today(company).plusDays(2);
        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, memberA, date1);
        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, memberB, date2);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes, occupancy_state, plan_id)
                    VALUES (?, ?, ?, ?, ?, 60, 'RESERVED', ?)
                    """, UUID.randomUUID(), company, task, memberA, date1, plan);
            jdbc.update("""
                    INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes, occupancy_state, plan_id)
                    VALUES (?, ?, ?, ?, ?, 60, 'RESERVED', ?)
                    """, UUID.randomUUID(), company, task, memberB, date2, plan);
        })).isInstanceOf(RuntimeException.class);
        Integer allocations = jdbc.queryForObject(
                "SELECT count(*) FROM task_allocation WHERE company_id = ?", Integer.class, company);
        assertThat(allocations).isZero();
    }

    @Test
    void uniqueIndexAllowsSingleActiveReservationPerTaskAndDay() {
        UUID company = newCompany();
        UUID member = newMember(company, "MEMBER");
        UUID plan = newCommittedPlan(company, member);
        UUID task = UUID.randomUUID();
        LocalDate date = today(company).plusDays(1);
        jdbc.update("INSERT INTO member_day_capacity(company_id, member_id, work_date, capacity_minutes) VALUES (?, ?, ?, 360)",
                company, member, date);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes, occupancy_state, plan_id)
                    VALUES (?, ?, ?, ?, ?, 60, 'RESERVED', ?)
                    """, UUID.randomUUID(), company, task, member, date, plan);
            jdbc.update("UPDATE member_day_capacity SET occupied_minutes = 60 "
                    + "WHERE company_id = ? AND member_id = ? AND work_date = ?", company, member, date);
        });

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_allocation(id, company_id, task_id, member_id, work_date, minutes, occupancy_state, plan_id)
                VALUES (?, ?, ?, ?, ?, 30, 'RESERVED', ?)
                """, UUID.randomUUID(), company, task, member, date, plan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guardRowIsCreatedOnDemandAndRevisionBumps() {
        UUID company = newCompany();
        jdbc.update("DELETE FROM planning_guard WHERE company_id = ?", company);
        var guard = guardRepository();
        tx.executeWithoutResult(status -> {
            long revision = guard.lockGuard(company);
            assertThat(revision).isZero();
            guard.bumpRevision(company);
        });
        assertThat(guard.currentRevision(company)).isEqualTo(1);
        Integer guardRows = jdbc.queryForObject("SELECT count(*) FROM planning_guard WHERE company_id = ?",
                Integer.class, company);
        assertThat(guardRows).isEqualTo(1);
    }
}
