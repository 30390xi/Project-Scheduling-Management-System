package com.company.scheduler.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Local development only. Does not reset existing passwords or modify production data. */
@Component
@Profile("dev")
public class DevBootstrap implements ApplicationRunner {
    public static final UUID COMPANY_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public DevBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
                        @Value("${bootstrap.username}") String username,
                        @Value("${bootstrap.password}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("Dev bootstrap requires a username and a 12+ character password (max 72 UTF-8 bytes)");
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(735021)");
        if (jdbc.queryForObject("SELECT count(*) FROM app_user WHERE username = ?", Integer.class, username) > 0) return;
        UUID userId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, ?)", userId, username, encoder.encode(password));
        jdbc.update("INSERT INTO company(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", COMPANY_ID, "本地演示公司");
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (?) ON CONFLICT DO NOTHING", COMPANY_ID);
        jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, 'ADMIN')", memberId, COMPANY_ID, userId);
        jdbc.update("INSERT INTO project(id, company_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", PROJECT_ID, COMPANY_ID, "基础工程演示项目");
        jdbc.update("INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'OWNER')", COMPANY_ID, PROJECT_ID, memberId);
    }
}
