package com.company.scheduler.task;

import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.contract.AnalysisRequests;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.service.CompanyClock;
import com.company.scheduler.task.service.DependencyService;
import com.company.scheduler.task.service.PlanningGuardService;
import com.company.scheduler.task.service.RemainingEstimateService;
import com.company.scheduler.task.service.TaskAccessService;
import com.company.scheduler.task.service.TaskCatalogService;
import com.company.scheduler.task.service.TaskService;
import com.company.scheduler.task.service.TaskTransitionService;
import com.company.scheduler.task.service.WorkLogService;
import com.company.scheduler.task.store.TaskDependencyRepository;
import com.company.scheduler.task.store.TaskRemainingEstimateRepository;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRevisionRepository;
import com.company.scheduler.task.store.TaskSkillRepository;
import com.company.scheduler.task.store.TaskStatusEventRepository;
import com.company.scheduler.task.store.WorkLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Collections;
import java.util.Iterator;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 模块集成测试基座：真实 PostgreSQL 17（Testcontainers，Docker 不可用即失败，不静默跳过）。
 * 基础表来自 task-it-base.sql（镜像 app V1），任务表来自正式迁移 db/migration/task。
 * CompanyAccess 为与 identity 同语义的测试替身；AnalysisRequests 缺省（D 未合并）。
 */
abstract class TaskModuleITSupport {
    private static PostgreSQLContainer<?> PG;

    protected static synchronized PostgreSQLContainer<?> postgres() {
        if (PG == null) {
            PG = new PostgreSQLContainer<>("postgres:17-alpine");
            PG.start();
        }
        return PG;
    }

    /** 每个测试类一个独立数据库，类间隔离。 */
    protected static DataSource newDatabase() {
        var pg = postgres();
        String dbName = "task_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var admin = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        admin.execute("CREATE DATABASE " + dbName);
        String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/" + dbName;
        var ds = new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("task-it-base.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("基础 schema 初始化失败", e);
        }
        Flyway flyway = Flyway.configure().dataSource(ds)
                .locations("classpath:db/migration/task").baselineOnMigrate(true).load();
        flyway.migrate();
        return ds;
    }

    /** 与 JdbcCompanyAccess 同语义的公司成员/项目访问测试替身。 */
    static CompanyAccess stubCompanyAccess(JdbcTemplate jdbc) {
        return new CompanyAccess() {
            @Override
            public Membership requireMember(UUID userId, UUID companyId) {
                return jdbc.query("""
                                SELECT m.id, m.role FROM company_member m JOIN app_user u ON u.id = m.user_id
                                WHERE m.company_id = ? AND m.user_id = ? AND m.status = 'ACTIVE' AND u.enabled
                                """,
                        (rs, row) -> new Membership(rs.getObject("id", UUID.class), companyId, userId,
                                rs.getString("role")),
                        companyId, userId).stream().findFirst().orElseThrow(BusinessException::notFound);
            }

            @Override
            public void requireProjectAccess(UUID userId, UUID companyId, UUID projectId) {
                Membership member = requireMember(userId, companyId);
                Integer count = jdbc.queryForObject("""
                        SELECT count(*) FROM project_member WHERE company_id = ? AND project_id = ? AND member_id = ?
                        """, Integer.class, companyId, projectId, member.memberId());
                if (count == null || count == 0) throw BusinessException.notFound();
            }
        };
    }

    /** D 的可靠作业未合并：测试中不提供 dispatcher，服务走“无作业”路径。 */
    static ObjectProvider<AnalysisRequests> noAnalysisRequests() {
        return new ObjectProvider<>() {
            @Override
            public AnalysisRequests getObject(Object... args) throws BeansException {
                throw new IllegalStateException("AnalysisRequests 不可用");
            }

            @Override
            public AnalysisRequests getObject() throws BeansException {
                throw new IllegalStateException("AnalysisRequests 不可用");
            }

            @Override
            public AnalysisRequests getIfAvailable() {
                return null;
            }

            @Override
            public AnalysisRequests getIfUnique() {
                return null;
            }

            @Override
            public Iterator<AnalysisRequests> iterator() {
                return Collections.emptyIterator();
            }
        };
    }

    /** 一套隔离的公司/项目/成员夹具和手工装配的服务。 */
    record Fixture(JdbcTemplate jdbc, TransactionTemplate tx, UUID companyId, UUID projectId,
                   UUID adminUserId, UUID adminMemberId, UUID memberUserId, UUID memberId,
                   UUID outsiderUserId, UUID outsiderMemberId,
                   TaskService tasks, TaskTransitionService transitions, DependencyService dependencies,
                   WorkLogService workLogs, RemainingEstimateService remainingEstimates,
                   TaskCatalogService catalog, java.util.Set<UUID> allocatedToday) {}

    protected Fixture newFixture(DataSource ds) {
        var jdbc = new JdbcTemplate(ds);
        PlatformTransactionManager txManager = new DataSourceTransactionManager(ds);
        var tx = new TransactionTemplate(txManager);

        UUID companyId = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, '测试公司')", companyId);
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (?)", companyId);
        UUID adminUserId = UUID.randomUUID();
        UUID adminMemberId = UUID.randomUUID();
        UUID memberUserId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID outsiderUserId = UUID.randomUUID();
        UUID outsiderMemberId = UUID.randomUUID();
        insertMember(jdbc, companyId, adminUserId, adminMemberId, "ADMIN");
        insertMember(jdbc, companyId, memberUserId, memberId, "MEMBER");
        insertMember(jdbc, companyId, outsiderUserId, outsiderMemberId, "MEMBER");
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, company_id, name) VALUES (?, ?, '测试项目')", projectId, companyId);
        jdbc.update("INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'OWNER')",
                companyId, projectId, adminMemberId);
        jdbc.update("INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'MEMBER')",
                companyId, projectId, memberId);
        // outsider 是公司成员但不是项目成员

        CompanyAccess access = stubCompanyAccess(jdbc);
        var tasks = new TaskRepository(jdbc);
        var revisions = new TaskRevisionRepository(jdbc);
        var skills = new TaskSkillRepository(jdbc);
        var dependencies = new TaskDependencyRepository(jdbc);
        var events = new TaskStatusEventRepository(jdbc);
        var workLogRepo = new WorkLogRepository(jdbc);
        var remainingRepo = new TaskRemainingEstimateRepository(jdbc);
        var accessService = new TaskAccessService(access, jdbc);
        var guard = new PlanningGuardService(jdbc);
        var clock = new CompanyClock(jdbc);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var taskService = new TaskService(tasks, revisions, skills, dependencies, events, workLogRepo,
                remainingRepo, accessService, guard, mapper, noAnalysisRequests());
        var allocatedToday = new java.util.HashSet<UUID>();
        var allocationPort = new com.company.scheduler.shared.contract.AllocationLifecycle() {
            public void start(UUID company, UUID task, UUID member) {
                if (!allocatedToday.contains(task)) throw new BusinessException(
                        org.springframework.http.HttpStatus.CONFLICT, "NO_ALLOCATION_TODAY", "今日无排期");
            }
            public void close(UUID company, UUID task) { allocatedToday.remove(task); }
        };
        var transitionService = new TaskTransitionService(tasks, dependencies, events, revisions, remainingRepo,
                accessService, guard, clock, taskService, mapper, allocationPort);
        var dependencyService = new DependencyService(tasks, dependencies, accessService, guard, taskService);
        var workLogService = new WorkLogService(tasks, workLogRepo, accessService, clock);
        var remainingService = new RemainingEstimateService(tasks, remainingRepo, accessService, guard, clock,
                taskService);
        var catalog = new TaskCatalogService(tasks, dependencies, skills, access, jdbc);
        return new Fixture(jdbc, tx, companyId, projectId, adminUserId, adminMemberId, memberUserId, memberId,
                outsiderUserId, outsiderMemberId, taskService, transitionService, dependencyService,
                workLogService, remainingService, catalog, allocatedToday);
    }

    private static void insertMember(JdbcTemplate jdbc, UUID companyId, UUID userId, UUID memberId, String role) {
        jdbc.update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, 'x')",
                userId, "u-" + userId);
        jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, ?)",
                memberId, companyId, userId, role);
    }
}
