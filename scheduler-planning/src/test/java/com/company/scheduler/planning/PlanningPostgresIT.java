package com.company.scheduler.planning;

import com.company.scheduler.planning.calendar.CalendarService;
import com.company.scheduler.planning.capacity.CapacityBucketService;
import com.company.scheduler.planning.capacity.CapacityPolicyService;
import com.company.scheduler.planning.capacity.CapacityQueryService;
import com.company.scheduler.planning.ledger.CapacityLedgerService;
import com.company.scheduler.planning.ledger.IdempotencyService;
import com.company.scheduler.planning.ledger.PlanningGuardRepository;
import com.company.scheduler.planning.skill.SkillService;
import com.company.scheduler.planning.support.CompanyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 真实 PostgreSQL 测试基座（仅 -Pintegration 执行）。每个测试类一个容器，
 * 迁移顺序与生产一致：foundation（测试基线副本）→ planning。服务手动装配，事务由 TransactionTemplate 控制。
 */
public abstract class PlanningPostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    protected static final Clock CLOCK = Clock.systemUTC();

    protected static HikariDataSource dataSource;
    protected static JdbcTemplate jdbc;
    protected static NamedParameterJdbcTemplate namedJdbc;
    protected static TransactionTemplate tx;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(24);
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration/foundation", "classpath:db/migration/planning")
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stopDatabase() {
        dataSource.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void cleanSchema() {
        jdbc.execute("""
                TRUNCATE task_allocation, schedule_plan, planning_idempotency, member_day_capacity,
                         member_skill, skill, member_calendar_override, calendar_day, project_member,
                         project, company_member, planning_guard, company, app_user CASCADE
                """);
    }

    protected UUID newCompany() {
        UUID companyId = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, '测试公司')", companyId);
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (?)", companyId);
        return companyId;
    }

    protected UUID newMember(UUID companyId, String role) {
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, 'test-only')", userId, "user-" + userId);
        jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, ?)",
                memberId, companyId, userId, role);
        return memberId;
    }

    protected UUID newCommittedPlan(UUID companyId, UUID createdBy) {
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedule_plan(company_id, id, status, input_revision, root_task_ids,
                                          input_snapshot, algorithm_version, created_by, expires_at, committed_at)
                VALUES (?, ?, 'COMMITTED', 0, '[]'::jsonb, '{}'::jsonb, 'phase1-it', ?,
                        now() + interval '1 hour', now())
                """, companyId, planId, createdBy);
        return planId;
    }

    protected LocalDate today(UUID companyId) {
        return companyRepository().today(companyId);
    }

    protected CompanyRepository companyRepository() {
        return new CompanyRepository(namedJdbc, CLOCK);
    }

    protected PlanningGuardRepository guardRepository() {
        return new PlanningGuardRepository(namedJdbc);
    }

    protected CapacityBucketService bucketService() {
        return new CapacityBucketService(namedJdbc, companyRepository());
    }

    protected CapacityLedgerService ledgerService() {
        return new CapacityLedgerService(namedJdbc, guardRepository(), bucketService(), companyRepository());
    }

    protected IdempotencyService idempotencyService() {
        return new IdempotencyService(namedJdbc, new ObjectMapper());
    }

    protected CalendarService calendarService() {
        return new CalendarService(namedJdbc, guardRepository(), bucketService(), companyRepository());
    }

    protected CapacityPolicyService policyService() {
        return new CapacityPolicyService(guardRepository(), bucketService(), companyRepository(), namedJdbc);
    }

    protected CapacityQueryService queryService() {
        return new CapacityQueryService(bucketService(), namedJdbc);
    }

    protected SkillService skillService() {
        return new SkillService(namedJdbc, guardRepository());
    }

    protected int bucketCapacity(UUID companyId, UUID memberId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT capacity_minutes FROM member_day_capacity
                 WHERE company_id = ? AND member_id = ? AND work_date = ?
                """, Integer.class, companyId, memberId, date);
    }

    protected int bucketOccupied(UUID companyId, UUID memberId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT occupied_minutes FROM member_day_capacity
                 WHERE company_id = ? AND member_id = ? AND work_date = ?
                """, Integer.class, companyId, memberId, date);
    }
}
