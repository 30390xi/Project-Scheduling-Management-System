package com.company.scheduler.identity.api;

import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.shared.contract.CompanyAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final JdbcTemplate jdbc;
    private final CompanyAccess access;

    public WorkspaceController(JdbcTemplate jdbc, CompanyAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/me/companies")
    public ApiResponse<List<CompanySummary>> companies(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        return ApiResponse.of(jdbc.query("""
                SELECT c.id, c.name, c.timezone, m.role FROM company c
                JOIN company_member m ON m.company_id = c.id
                JOIN app_user u ON u.id = m.user_id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' AND u.enabled ORDER BY c.id
                """, (rs, row) -> new CompanySummary(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("timezone"), rs.getString("role")), userId));
    }

    @GetMapping("/projects")
    public ApiResponse<List<ProjectSummary>> projects(Authentication authentication,
                                                       @RequestHeader("X-Company-Id") UUID companyId) {
        var member = access.requireMember(UUID.fromString(authentication.getName()), companyId);
        // 管理员需要管理入口，可列出公司全部项目；项目内容仍按项目成员授权，不因此放宽
        if ("ADMIN".equals(member.role())) {
            return ApiResponse.of(jdbc.query("SELECT id, name FROM project WHERE company_id = ? ORDER BY id",
                    (rs, row) -> new ProjectSummary(rs.getObject("id", UUID.class), rs.getString("name")), companyId));
        }
        return ApiResponse.of(jdbc.query("""
                SELECT p.id, p.name FROM project p JOIN project_member pm
                  ON pm.company_id = p.company_id AND pm.project_id = p.id
                WHERE p.company_id = ? AND pm.member_id = ? ORDER BY p.id
                """, (rs, row) -> new ProjectSummary(rs.getObject("id", UUID.class), rs.getString("name")),
                companyId, member.memberId()));
    }

    public record CompanySummary(UUID id, String name, String timezone, String role) {}
    public record ProjectSummary(UUID id, String name) {}
}
