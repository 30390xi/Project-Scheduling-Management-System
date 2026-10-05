package com.company.scheduler.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * identity 模块端到端权限测试：真实 PostgreSQL（Testcontainers），覆盖 PRD AC-20
 * 跨公司/跨项目越权、禁用后访问、登录限流、强制改密与会话撤销。仅在 -Pintegration 下运行。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.locations=classpath:db/test-baseline,classpath:db/migration/identity",
        "spring.session.store-type=jdbc",
        "spring.session.jdbc.initialize-schema=always",
        "server.servlet.session.cookie.secure=false",
        "scheduler.auth.login.max-failed-attempts=3",
        "scheduler.auth.login.lock-minutes=10",
        "scheduler.auth.login.ip-max-failed-attempts=100",
        "scheduler.auth.login.ip-window-minutes=10"
})
class IdentityStage1IT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void memberLifecycleDisableBlocksAccessImmediatelyAndBumpsGuardRevision() throws Exception {
        UUID company = newCompany("成员生命周期公司");
        newUser("admin-life", "admin-life-pass-1");
        UUID adminMember = addMember(company, userId("admin-life"), "ADMIN");
        newUser("bob-life", "bob-life-pass-12");
        UUID bobMember = addMember(company, userId("bob-life"), "MEMBER");
        long guardBefore = guardRevision(company);

        var admin = loginOk("admin-life", "admin-life-pass-1");
        var members = get(admin, "/api/v1/members", company);
        assertThat(members.statusCode()).isEqualTo(200);
        assertThat(json(members).path("data").size()).isEqualTo(2);

        var bob = loginOk("bob-life", "bob-life-pass-12");
        assertThat(get(bob, "/api/v1/projects", company).statusCode()).isEqualTo(200);

        // 非管理员不能管理成员
        assertThat(write(bob, "POST", "/api/v1/members", company,
                "{\"username\":\"x-user-1\",\"role\":\"MEMBER\"}").statusCode()).isEqualTo(403);

        // 禁用：需要原因，禁用后立即失去公司访问，guard revision 递增
        assertThat(write(admin, "POST", "/api/v1/members/" + bobMember + "/disable", company, "{}").statusCode()).isEqualTo(400);
        var disabled = write(admin, "POST", "/api/v1/members/" + bobMember + "/disable", company, "{\"reason\":\"离职\"}");
        assertThat(disabled.statusCode()).describedAs(disabled.body()).isEqualTo(200);
        assertThat(json(disabled).at("/data/status").asText()).isEqualTo("INACTIVE");
        assertThat(guardRevision(company)).isGreaterThan(guardBefore);

        assertThat(json(get(bob, "/api/v1/me/companies", null)).path("data").size()).isZero();
        assertThat(get(bob, "/api/v1/projects", company).statusCode()).isEqualTo(404);
        assertThat(get(bob, "/api/v1/members", company).statusCode()).isEqualTo(404);

        // 启用后恢复
        assertThat(write(admin, "POST", "/api/v1/members/" + bobMember + "/enable", company, null).statusCode()).isEqualTo(200);
        assertThat(json(get(bob, "/api/v1/me/companies", null)).path("data").size()).isEqualTo(1);
        assertThat(get(bob, "/api/v1/projects", company).statusCode()).isEqualTo(200);

        // 不能禁用自己
        assertThat(write(admin, "POST", "/api/v1/members/" + adminMember + "/disable", company, "{\"reason\":\"测试\"}")
                .statusCode()).isEqualTo(409);
    }

    @Test
    void memberCreationWithTemporaryPasswordForcesPasswordChange() throws Exception {
        UUID company = newCompany("创建成员公司");
        newUser("admin-create", "admin-create-pw1");
        addMember(company, userId("admin-create"), "ADMIN");
        var admin = loginOk("admin-create", "admin-create-pw1");

        var created = write(admin, "POST", "/api/v1/members", company, "{\"username\":\"newbie-1\",\"role\":\"MEMBER\"}");
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(200);
        String temporaryPassword = json(created).at("/data/temporaryPassword").asText();
        assertThat(temporaryPassword).hasSize(20);
        UUID memberId = UUID.fromString(json(created).at("/data/memberId").asText());

        // 临时密码可登录，但改密前其他接口一律 403
        var newbie = loginOk("newbie-1", temporaryPassword);
        var blocked = get(newbie, "/api/v1/me/companies", null);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json(blocked).path("code").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");

        // 错误当前密码 401；弱密码 400
        assertThat(write(newbie, "POST", "/api/v1/me/password", null,
                "{\"currentPassword\":\"wrong-current-pw\",\"newPassword\":\"new-strong-pass-1\"}").statusCode()).isEqualTo(401);
        assertThat(write(newbie, "POST", "/api/v1/me/password", null,
                "{\"currentPassword\":\"" + temporaryPassword + "\",\"newPassword\":\"short\"}").statusCode()).isEqualTo(400);

        assertThat(write(newbie, "POST", "/api/v1/me/password", null,
                "{\"currentPassword\":\"" + temporaryPassword + "\",\"newPassword\":\"new-strong-pass-1\"}").statusCode()).isEqualTo(200);
        assertThat(json(get(newbie, "/api/v1/me/companies", null)).path("data").size()).isEqualTo(1);
        // 旧临时密码不可再用
        assertThat(login("newbie-1", temporaryPassword).statusCode()).isEqualTo(401);

        // 重复创建同一用户名 → 409
        assertThat(write(admin, "POST", "/api/v1/members", company, "{\"username\":\"newbie-1\",\"role\":\"MEMBER\"}")
                .statusCode()).isEqualTo(409);
        // 非法角色 400
        assertThat(write(admin, "POST", "/api/v1/members", company, "{\"username\":\"newbie-2\",\"role\":\"SUPER\"}")
                .statusCode()).isEqualTo(400);

        // 审计留痕
        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM identity_audit_event WHERE action = 'MEMBER_CREATED' AND entity_id = ?",
                Integer.class, memberId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void passwordChangeRevokesOtherSessions() throws Exception {
        UUID company = newCompany("会话撤销公司");
        newUser("admin-sess", "admin-sess-pw-1");
        addMember(company, userId("admin-sess"), "ADMIN");
        newUser("carol-sess", "carol-sess-pw-1");
        addMember(company, userId("carol-sess"), "MEMBER");

        var sessionA = loginOk("carol-sess", "carol-sess-pw-1");
        var sessionB = loginOk("carol-sess", "carol-sess-pw-1");
        assertThat(write(sessionA, "POST", "/api/v1/me/password", null,
                "{\"currentPassword\":\"carol-sess-pw-1\",\"newPassword\":\"carol-sess-pw-2\"}").statusCode()).isEqualTo(200);
        assertThat(get(sessionA, "/api/v1/me/companies", null).statusCode()).isEqualTo(200);
        assertThat(get(sessionB, "/api/v1/me/companies", null).statusCode()).isEqualTo(401);
    }

    @Test
    void adminPasswordResetRevokesSessionsAndForcesChange() throws Exception {
        UUID company = newCompany("重置密码公司");
        newUser("admin-reset", "admin-reset-pw1");
        addMember(company, userId("admin-reset"), "ADMIN");
        newUser("dave-reset", "dave-reset-pw1");
        UUID daveMember = addMember(company, userId("dave-reset"), "MEMBER");

        var dave = loginOk("dave-reset", "dave-reset-pw1");
        var admin = loginOk("admin-reset", "admin-reset-pw1");

        assertThat(write(admin, "POST", "/api/v1/members/" + daveMember + "/password-reset", company, "{}").statusCode()).isEqualTo(400);
        var reset = write(admin, "POST", "/api/v1/members/" + daveMember + "/password-reset", company,
                "{\"reason\":\"成员忘记密码\"}");
        assertThat(reset.statusCode()).describedAs(reset.body()).isEqualTo(200);
        String temporaryPassword = json(reset).at("/data/temporaryPassword").asText();

        // 旧会话被撤销
        assertThat(get(dave, "/api/v1/me/companies", null).statusCode()).isEqualTo(401);
        // 临时密码登录后必须先改密
        var renewed = loginOk("dave-reset", temporaryPassword);
        assertThat(get(renewed, "/api/v1/me/companies", null).statusCode()).isEqualTo(403);
        assertThat(write(renewed, "POST", "/api/v1/me/password", null,
                "{\"currentPassword\":\"" + temporaryPassword + "\",\"newPassword\":\"dave-reset-pw-2\"}").statusCode()).isEqualTo(200);
        assertThat(get(renewed, "/api/v1/me/companies", null).statusCode()).isEqualTo(200);
    }

    @Test
    void loginIsRateLimitedAfterRepeatedFailures() throws Exception {
        newUser("erin-lock", "erin-lock-pw-12");
        for (int i = 0; i < 3; i++) {
            assertThat(login("erin-lock", "wrong-password-" + i).statusCode()).isEqualTo(401);
        }
        var blocked = login("erin-lock", "erin-lock-pw-12");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(json(blocked).path("code").asText()).isEqualTo("LOGIN_RATE_LIMITED");
    }

    @Test
    void projectManagementRespectsRolesVersionsAndLastOwner() throws Exception {
        UUID company = newCompany("项目管理公司");
        newUser("admin-proj", "admin-proj-pw-1");
        UUID adminMember = addMember(company, userId("admin-proj"), "ADMIN");
        newUser("fran-proj", "fran-proj-pw-1");
        UUID franMember = addMember(company, userId("fran-proj"), "MEMBER");
        newUser("gina-proj", "gina-proj-pw-1");
        UUID ginaMember = addMember(company, userId("gina-proj"), "MEMBER");

        var admin = loginOk("admin-proj", "admin-proj-pw-1");
        var fran = loginOk("fran-proj", "fran-proj-pw-1");

        // 创建：仅管理员；创建者自动成为项目负责人
        assertThat(write(fran, "POST", "/api/v1/projects", company, "{\"name\":\"越权项目\"}").statusCode()).isEqualTo(403);
        var created = write(admin, "POST", "/api/v1/projects", company, "{\"name\":\"支付重构\"}");
        assertThat(created.statusCode()).describedAs(created.body()).isEqualTo(200);
        String projectId = json(created).at("/data/id").asText();
        var members = get(admin, "/api/v1/projects/" + projectId + "/members", company);
        assertThat(json(members).at("/data/0/role").asText()).isEqualTo("OWNER");
        assertThat(json(members).at("/data/0/memberId").asText()).isEqualTo(adminMember.toString());

        // 版本控制
        assertThat(write(admin, "PUT", "/api/v1/projects/" + projectId, company, "{\"name\":\"支付重构二期\"}")
                .statusCode()).isEqualTo(400);
        assertThat(write(admin, "PUT", "/api/v1/projects/" + projectId, company, "{\"name\":\"支付重构二期\"}", "If-Match", "\"99\"")
                .statusCode()).isEqualTo(409);
        var renamed = write(admin, "PUT", "/api/v1/projects/" + projectId, company, "{\"name\":\"支付重构二期\"}", "If-Match", "\"0\"");
        assertThat(renamed.statusCode()).describedAs(renamed.body()).isEqualTo(200);
        assertThat(json(renamed).at("/data/version").asLong()).isEqualTo(1);

        // 非项目成员不可见
        assertThat(get(fran, "/api/v1/projects/" + projectId, company).statusCode()).isEqualTo(404);

        // 加入普通成员：可见但不可管理
        var added = write(admin, "POST", "/api/v1/projects/" + projectId + "/members", company,
                "{\"memberId\":\"" + franMember + "\",\"role\":\"MEMBER\",\"resourcePermission\":false}");
        assertThat(added.statusCode()).describedAs(added.body()).isEqualTo(200);
        assertThat(get(fran, "/api/v1/projects/" + projectId, company).statusCode()).isEqualTo(200);
        assertThat(write(fran, "PUT", "/api/v1/projects/" + projectId, company, "{\"name\":\"越权改名\"}", "If-Match", "\"1\"")
                .statusCode()).isEqualTo(403);
        // 重复加入 → 409
        assertThat(write(admin, "POST", "/api/v1/projects/" + projectId + "/members", company,
                "{\"memberId\":\"" + franMember + "\",\"role\":\"MEMBER\"}").statusCode()).isEqualTo(409);

        // 提升为项目负责人并授予资源授权后，可改派单策略
        var promoted = write(admin, "PUT", "/api/v1/projects/" + projectId + "/members/" + franMember, company,
                "{\"role\":\"OWNER\",\"resourcePermission\":true,\"reason\":\"授权资源调配\"}");
        assertThat(promoted.statusCode()).describedAs(promoted.body()).isEqualTo(200);
        assertThat(json(promoted).at("/data/resourcePermission").asBoolean()).isTrue();
        var policy = write(fran, "PUT", "/api/v1/projects/" + projectId + "/policy", company,
                "{\"autoAssignEnabled\":true,\"reason\":\"试点自动派单\"}", "If-Match", "\"1\"");
        assertThat(policy.statusCode()).describedAs(policy.body()).isEqualTo(200);

        // 项目成员变更加入审计与 guard 版本
        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM identity_audit_event WHERE action = 'PROJECT_MEMBER_UPDATED' AND company_id = ?",
                Integer.class, company);
        assertThat(audits).isEqualTo(1);

        // 最后一名负责人不可被移除/降级
        assertThat(write(admin, "DELETE", "/api/v1/projects/" + projectId + "/members/" + adminMember, company, null)
                .statusCode()).isEqualTo(200);
        assertThat(write(fran, "DELETE", "/api/v1/projects/" + projectId + "/members/" + franMember, company, null)
                .statusCode()).isEqualTo(409);
        assertThat(json(get(admin, "/api/v1/projects/" + projectId + "/members", company)).path("data").size()).isEqualTo(1);
        assertThat(ginaMember).isNotNull(); // 预留成员用于越权场景
    }

    @Test
    void crossCompanyAndCrossProjectAccessIsInvisible() throws Exception {
        UUID companyA = newCompany("越权公司A");
        newUser("admin-xa", "admin-xa-pass-1");
        addMember(companyA, userId("admin-xa"), "ADMIN");
        newUser("member-xa", "member-xa-pass1");
        UUID memberA = addMember(companyA, userId("member-xa"), "MEMBER");

        UUID companyB = newCompany("越权公司B");
        newUser("admin-xb", "admin-xb-pass-1");
        addMember(companyB, userId("admin-xb"), "ADMIN");

        var adminA = loginOk("admin-xa", "admin-xa-pass-1");
        var adminB = loginOk("admin-xb", "admin-xb-pass-1");
        var memberSessionA = loginOk("member-xa", "member-xa-pass1");

        var projectB = write(adminB, "POST", "/api/v1/projects", companyB, "{\"name\":\"公司B机密项目\"}");
        String projectBId = json(projectB).at("/data/id").asText();
        var projectA1 = write(adminA, "POST", "/api/v1/projects", companyA, "{\"name\":\"A项目一\"}");
        String projectA1Id = json(projectA1).at("/data/id").asText();
        var projectA2 = write(adminA, "POST", "/api/v1/projects", companyA, "{\"name\":\"A项目二\"}");
        String projectA2Id = json(projectA2).at("/data/id").asText();
        write(adminA, "POST", "/api/v1/projects/" + projectA1Id + "/members", companyA,
                "{\"memberId\":\"" + memberA + "\",\"role\":\"MEMBER\"}");

        // 跨公司：B 公司管理员持 A 公司头 → 404（无成员关系）
        assertThat(get(adminB, "/api/v1/projects", companyA).statusCode()).isEqualTo(404);
        assertThat(get(adminB, "/api/v1/members", companyA).statusCode()).isEqualTo(404);
        // 跨公司：A 管理员访问 B 公司项目 → 404，不泄露存在性
        assertThat(get(adminA, "/api/v1/projects/" + projectBId, companyA).statusCode()).isEqualTo(404);
        assertThat(write(adminA, "PUT", "/api/v1/projects/" + projectBId, companyA, "{\"name\":\"劫持\"}", "If-Match", "\"0\"")
                .statusCode()).isEqualTo(404);
        assertThat(write(adminA, "POST", "/api/v1/projects/" + projectBId + "/members", companyA,
                "{\"memberId\":\"" + memberA + "\",\"role\":\"MEMBER\"}").statusCode()).isEqualTo(404);
        // 跨公司成员加入 B 项目：B 管理员引用 A 公司成员 → 404
        assertThat(write(adminB, "POST", "/api/v1/projects/" + projectBId + "/members", companyB,
                "{\"memberId\":\"" + memberA + "\",\"role\":\"MEMBER\"}").statusCode()).isEqualTo(404);

        // 跨项目：仅项目一的普通成员访问项目二 → 404
        assertThat(get(memberSessionA, "/api/v1/projects/" + projectA2Id, companyA).statusCode()).isEqualTo(404);
        assertThat(get(memberSessionA, "/api/v1/projects/" + projectA2Id + "/members", companyA).statusCode()).isEqualTo(404);
        assertThat(get(memberSessionA, "/api/v1/projects/" + projectA1Id, companyA).statusCode()).isEqualTo(200);

        // 列表不泄露：member A 的项目列表只有项目一
        var list = get(memberSessionA, "/api/v1/projects", companyA);
        assertThat(json(list).path("data").size()).isEqualTo(1);
        assertThat(json(list).at("/data/0/id").asText()).isEqualTo(projectA1Id);
        // 管理员列表包含全公司项目（管理入口）
        assertThat(json(get(adminA, "/api/v1/projects", companyA)).path("data").size()).isEqualTo(2);
    }

    @Test
    void writeEndpointsStillRequireCsrf() throws Exception {
        UUID company = newCompany("CSRF公司");
        newUser("admin-csrf", "admin-csrf-pw-1");
        addMember(company, userId("admin-csrf"), "ADMIN");
        var admin = loginOk("admin-csrf", "admin-csrf-pw-1");
        assertThat(post(admin, "/api/v1/members", "{\"username\":\"no-csrf-user\",\"role\":\"MEMBER\"}", null, company)
                .statusCode()).isEqualTo(403);
    }

    @Test
    void reviewCompanyAdminCannotTakeOverAnotherCompanyAccount() throws Exception {
        UUID attackerCompany = newCompany("review attacker company");
        UUID victimCompany = newCompany("review victim company");
        newUser("review-attacker", "Review-attacker-pass1");
        newUser("review-victim", "Review-victim-pass1");
        addMember(attackerCompany, userId("review-attacker"), "ADMIN");
        addMember(victimCompany, userId("review-victim"), "ADMIN");
        var attacker = loginOk("review-attacker", "Review-attacker-pass1");
        var added = write(attacker, "POST", "/api/v1/members", attackerCompany,
                "{\"username\":\"review-victim\",\"role\":\"MEMBER\"}");
        assertThat(added.statusCode()).isEqualTo(409);
        assertThat(json(added).path("code").asText()).isEqualTo("ACCOUNT_INVITATION_REQUIRED");
        assertThat(get(attacker, "/api/v1/projects", victimCompany).statusCode()).isEqualTo(404);
        // Even a legacy, already-shared account cannot have its global password reset by one tenant.
        UUID sharedMember = addMember(attackerCompany, userId("review-victim"), "MEMBER");
        var reset = write(attacker, "POST", "/api/v1/members/" + sharedMember + "/password-reset",
                attackerCompany, "{\"reason\":\"review security test\"}");
        assertThat(reset.statusCode()).isEqualTo(403);
        assertThat(json(reset).path("code").asText()).isEqualTo("GLOBAL_ACCOUNT_RESET_FORBIDDEN");
        var victim = loginOk("review-victim", "Review-victim-pass1");
        assertThat(get(victim, "/api/v1/projects", victimCompany).statusCode()).isEqualTo(200);
    }

    // ---------- fixtures & http helpers ----------

    private UUID newCompany(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, ?)", id, name);
        jdbc.update("INSERT INTO planning_guard(company_id) VALUES (?)", id);
        return id;
    }

    private void newUser(String username, String rawPassword) {
        jdbc.update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, ?)",
                UUID.randomUUID(), username, encoder.encode(rawPassword));
    }

    private UUID userId(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", UUID.class, username);
    }

    private UUID addMember(UUID companyId, UUID userId, String role) {
        UUID memberId = UUID.randomUUID();
        jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, ?)",
                memberId, companyId, userId, role);
        return memberId;
    }

    private long guardRevision(UUID companyId) {
        Long revision = jdbc.queryForObject("SELECT revision FROM planning_guard WHERE company_id = ?", Long.class, companyId);
        return revision == null ? -1 : revision;
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpClient loginOk(String username, String password) throws Exception {
        var client = client();
        var response = post(client, "/api/v1/auth/login",
                mapper.writeValueAsString(java.util.Map.of("username", username, "password", password)), csrf(client), null);
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(200);
        return client;
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        var client = client();
        return post(client, "/api/v1/auth/login",
                mapper.writeValueAsString(java.util.Map.of("username", username, "password", password)), csrf(client), null);
    }

    private String csrf(HttpClient client) throws Exception {
        return json(get(client, "/api/v1/auth/csrf", null)).at("/data/token").asText();
    }

    private HttpResponse<String> get(HttpClient client, String path, UUID companyId, String... extra) throws Exception {
        return request(client, "GET", path, null, null, companyId, extra);
    }

    /** 写操作自动获取新的 CSRF token（登录后旧 token 已失效）。 */
    private HttpResponse<String> write(HttpClient client, String method, String path, UUID companyId, String body,
                                       String... extra) throws Exception {
        return request(client, method, path, body, csrf(client), companyId, extra);
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String csrf, UUID companyId,
                                      String... extra) throws Exception {
        return request(client, "POST", path, body, csrf, companyId, extra);
    }

    private HttpResponse<String> request(HttpClient client, String method, String path, String body, String csrf,
                                         UUID companyId, String... extra) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        if (companyId != null) builder.header("X-Company-Id", companyId.toString());
        if (extra != null && extra.length > 0) builder.headers(extra);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
}
