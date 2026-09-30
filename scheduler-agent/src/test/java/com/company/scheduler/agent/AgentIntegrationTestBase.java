package com.company.scheduler.agent;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * agent 模块 PostgreSQL 集成测试基类。
 * 单例容器在所有子类间共享；Docker 不可用即失败，绝不静默跳过（AGENTS #10）。
 */
@SpringBootTest(classes = AgentTestApplication.class)
@ActiveProfiles("it")
public abstract class AgentIntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected NamedParameterJdbcTemplate jdbc;

    protected UUID companyId;
    protected UUID memberId;
    protected UUID secondMemberId;

    @BeforeEach
    void isolateAgentTablesAndCreateCompanyFixture() {
        // 队列与 Outbox 是全局领取/分发的；IT 类共享数据库，逐测试清空 agent 域表保证隔离。
        // audit_event 的追加写触发器会阻止 DELETE——这正是要验证的生产行为；
        // 测试清理用 replica 会话临时停用触发器（容器内超级用户），生产应用账号无此路径。
        jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                for (String sql : new String[] {
                        "DELETE FROM notification",
                        "DELETE FROM processed_event",
                        "DELETE FROM outbox_event",
                        "DELETE FROM audit_event",
                        "DELETE FROM ai_analysis_run",
                        "DELETE FROM async_job",
                        "DELETE FROM company_member",
                        "DELETE FROM company"}) {
                    statement.execute(sql);
                }
                statement.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
        companyId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        secondMemberId = UUID.randomUUID();
        jdbc.getJdbcTemplate().update("INSERT INTO company(id) VALUES (?)", companyId);
        jdbc.getJdbcTemplate().update("INSERT INTO company_member(company_id, id) VALUES (?, ?)",
                companyId, memberId);
        jdbc.getJdbcTemplate().update("INSERT INTO company_member(company_id, id) VALUES (?, ?)",
                companyId, secondMemberId);
    }
}
