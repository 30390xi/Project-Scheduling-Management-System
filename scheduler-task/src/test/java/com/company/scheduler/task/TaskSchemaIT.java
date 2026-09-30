package com.company.scheduler.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 迁移与数据库防线：复合外键、CHECK、唯一约束在真实 PostgreSQL 下生效。 */
class TaskSchemaIT extends TaskModuleITSupport {
    private static DataSource ds;
    private static JdbcTemplate jdbc;
    private static UUID companyId;
    private static UUID projectId;
    private static UUID memberId;

    @BeforeAll
    static void setup() {
        ds = newDatabase();
        jdbc = new JdbcTemplate(ds);
        companyId = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, 'schema 公司')", companyId);
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (?)", companyId);
        UUID userId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, 'x')", userId, "schema-user");
        jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, 'ADMIN')",
                memberId, companyId, userId);
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, company_id, name) VALUES (?, ?, 'schema 项目')", projectId, companyId);
        jdbc.update("INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'OWNER')",
                companyId, projectId, memberId);
    }

    private UUID insertTask() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO task(id, company_id, project_id, title, created_by) VALUES (?, ?, ?, 't', ?)",
                id, companyId, projectId, memberId);
        return id;
    }

    @Test
    void rejectsCrossCompanyOrCrossProjectReferences() {
        UUID taskA = insertTask();
        UUID otherCompany = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, '其他公司')", otherCompany);
        UUID otherUser = UUID.randomUUID();
        UUID otherMember = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, 'x')", otherUser, "schema-other");
        jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, 'ADMIN')",
                otherMember, otherCompany, otherUser);
        UUID otherProject = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, company_id, name) VALUES (?, ?, '其他项目')", otherProject, otherCompany);

        // 跨公司父子引用被拒绝
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task(id, company_id, project_id, parent_id, title, created_by)
                VALUES (?, ?, ?, ?, 'child', ?)
                """, UUID.randomUUID(), otherCompany, otherProject, taskA, otherMember))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 跨项目依赖被拒绝（复合外键同项目约束）
        UUID taskB = insertTask();
        UUID otherTask = UUID.randomUUID();
        jdbc.update("INSERT INTO task(id, company_id, project_id, title, created_by) VALUES (?, ?, ?, 'x', ?)",
                otherTask, otherCompany, otherProject, otherMember);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_dependency(company_id, project_id, predecessor_id, successor_id)
                VALUES (?, ?, ?, ?)
                """, companyId, projectId, taskA, otherTask))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesDomainCheckConstraints() {
        UUID taskA = insertTask();
        // 自依赖
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_dependency(company_id, project_id, predecessor_id, successor_id) VALUES (?, ?, ?, ?)
                """, companyId, projectId, taskA, taskA))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 计划工时必须是 30 的倍数
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task(id, company_id, project_id, title, estimated_minutes, created_by)
                VALUES (?, ?, ?, 'bad', 95, ?)
                """, UUID.randomUUID(), companyId, projectId, memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 非法状态
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task(id, company_id, project_id, title, status, created_by)
                VALUES (?, ?, ?, 'bad', 'ARCHIVED', ?)
                """, UUID.randomUUID(), companyId, projectId, memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 硬截止必须带日期
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task(id, company_id, project_id, title, due_type, created_by)
                VALUES (?, ?, ?, 'bad', 'HARD', ?)
                """, UUID.randomUUID(), companyId, projectId, memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 负的实际工时
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO work_log(id, company_id, task_id, member_id, work_date, minutes, created_by)
                VALUES (?, ?, ?, ?, current_date, -10, ?)
                """, UUID.randomUUID(), companyId, taskA, memberId, memberId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueness() {
        UUID taskA = insertTask();
        UUID taskB = insertTask();
        jdbc.update("UPDATE task SET task_no = 100 WHERE company_id = ? AND id = ?", companyId, taskA);
        assertThatThrownBy(() -> jdbc.update("UPDATE task SET task_no = 100 WHERE company_id = ? AND id = ?",
                companyId, taskB)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO task_dependency(company_id, project_id, predecessor_id, successor_id) VALUES (?, ?, ?, ?)",
                companyId, projectId, taskA, taskB);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_dependency(company_id, project_id, predecessor_id, successor_id) VALUES (?, ?, ?, ?)
                """, companyId, projectId, taskA, taskB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void taskSequenceIncrementsPerCompany() {
        var repository = new com.company.scheduler.task.store.TaskRepository(jdbc);
        long first = repository.nextTaskNo(companyId);
        long second = repository.nextTaskNo(companyId);
        assertThat(second).isEqualTo(first + 1);
        UUID otherCompany = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, '序列公司')", otherCompany);
        assertThat(repository.nextTaskNo(otherCompany)).isEqualTo(1);
    }
}
