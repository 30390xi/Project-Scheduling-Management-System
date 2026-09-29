package com.company.scheduler;

import static org.assertj.core.api.Assertions.*;
import com.company.scheduler.bootstrap.DevBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs against real PostgreSQL with -Pintegration. Missing Docker is a failure, never a silent skip. */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootstrap.username=foundation-test", "bootstrap.password=Test-local-only-123"})
class FoundationIT {
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
    @Autowired Flyway flyway;

    @Test
    void migratesSchemaAndProtectsBusinessEndpoints() throws Exception {
        var client = client();
        assertThat(get(client, "/actuator/health").statusCode()).isEqualTo(200);
        var unauthorized = get(client, "/api/v1/me/companies");
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(json(unauthorized).path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(unauthorized.headers().firstValue("X-Request-Id")).isPresent();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void logsInPersistsSessionAndReturnsAuthorizedWorkspace() throws Exception {
        var client = login();
        var companies = get(client, "/api/v1/me/companies");
        assertThat(companies.statusCode()).isEqualTo(200);
        assertThat(json(companies).at("/data/0/id").asText()).isEqualTo(DevBootstrap.COMPANY_ID.toString());
        var projects = get(client, "/api/v1/projects", "X-Company-Id", DevBootstrap.COMPANY_ID.toString());
        assertThat(projects.statusCode()).isEqualTo(200);
        assertThat(json(projects).at("/data/0/id").asText()).isEqualTo(DevBootstrap.PROJECT_ID.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL", Integer.class)).isPositive();
        assertThat(get(client, "/api/v1/system/info").statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsMissingCsrfWrongPasswordAndUnknownFields() throws Exception {
        var client = client();
        assertThat(post(client, "/api/v1/auth/login", "{}", null).statusCode()).isEqualTo(403);
        String token = csrf(client);
        assertThat(post(client, "/api/v1/auth/login", "{\"username\":\"foundation-test\",\"password\":\"wrong\"}", token).statusCode()).isEqualTo(401);
        assertThat(post(client, "/api/v1/auth/login", "{\"username\":\"foundation-test\",\"password\":\"wrong\",\"role\":\"ADMIN\"}", token).statusCode()).isEqualTo(400);
        assertThat(post(client, "/api/v1/auth/login", "{\"username\":\"\",\"password\":\"\"}", token).statusCode()).isEqualTo(400);
        String longPassword = mapper.writeValueAsString(java.util.Map.of("username", "foundation-test", "password", "密".repeat(30)));
        assertThat(post(client, "/api/v1/auth/login", longPassword, token).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsSpoofedCompanyAndMalformedHeader() throws Exception {
        var client = login();
        UUID otherCompany = UUID.randomUUID();
        jdbc.update("INSERT INTO company(id, name) VALUES (?, 'Other company')", otherCompany);
        try {
            assertThat(get(client, "/api/v1/projects", "X-Company-Id", otherCompany.toString()).statusCode()).isEqualTo(404);
        } finally {
            jdbc.update("DELETE FROM company WHERE id = ?", otherCompany);
        }
        assertThat(get(client, "/api/v1/projects", "X-Company-Id", "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(get(client, "/api/v1/projects").statusCode()).isEqualTo(400);
    }

    @Test
    void logsOutAndInvalidatesSession() throws Exception {
        var client = login();
        assertThat(post(client, "/api/v1/auth/logout", "{}", csrf(client)).statusCode()).isEqualTo(200);
        assertThat(get(client, "/api/v1/me/companies").statusCode()).isEqualTo(401);
    }

    @Test
    void inactiveMembershipCannotUseAnExistingSessionForProjectAccess() throws Exception {
        var client = login();
        jdbc.update("UPDATE company_member SET status = 'INACTIVE' WHERE company_id = ?", DevBootstrap.COMPANY_ID);
        try {
            assertThat(get(client, "/api/v1/projects", "X-Company-Id", DevBootstrap.COMPANY_ID.toString()).statusCode()).isEqualTo(404);
            assertThat(json(get(client, "/api/v1/me/companies")).path("data").size()).isZero();
        } finally {
            jdbc.update("UPDATE company_member SET status = 'ACTIVE' WHERE company_id = ?", DevBootstrap.COMPANY_ID);
        }
    }

    @Test
    void databaseRejectsInvalidCapacityAndCrossCompanyProjectMembership() {
        UUID member = jdbc.queryForObject("SELECT id FROM company_member WHERE company_id = ? LIMIT 1", UUID.class, DevBootstrap.COMPANY_ID);
        assertThatThrownBy(() -> jdbc.update("UPDATE company_member SET daily_limit_minutes = 600 WHERE id = ?", member))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'MEMBER')",
                UUID.randomUUID(), DevBootstrap.PROJECT_ID, member)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void servesOpenApiForDevelopment() throws Exception {
        var response = get(client(), "/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("paths").has("/api/v1/auth/login")).isTrue();
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpClient login() throws Exception {
        var client = client();
        var result = post(client, "/api/v1/auth/login", "{\"username\":\"foundation-test\",\"password\":\"Test-local-only-123\"}", csrf(client));
        assertThat(result.statusCode()).describedAs(result.body()).isEqualTo(200);
        return client;
    }

    private String csrf(HttpClient client) throws Exception {
        return json(get(client, "/api/v1/auth/csrf")).at("/data/token").asText();
    }

    private HttpResponse<String> get(HttpClient client, String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) builder.headers(headers);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
}
