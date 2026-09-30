package com.company.scheduler.identity.service;

import com.company.scheduler.identity.security.AuthenticatedActor;
import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.error.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目与项目成员管理。创建仅公司管理员；已授权项目负责人（project_member.role = OWNER）
 * 可管理本项目。管理权限只覆盖项目元数据与成员，不扩展任何项目内容读取范围。
 */
@Service
public class ProjectService {
    private final JdbcTemplate jdbc;
    private final CompanyAccess access;
    private final IdentityAuditService audit;
    private final PlanningGuardRevision guard;

    public ProjectService(JdbcTemplate jdbc, CompanyAccess access,
                          IdentityAuditService audit, PlanningGuardRevision guard) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.guard = guard;
    }

    @Transactional
    public ProjectView create(AuthenticatedActor actor, String name) {
        var admin = requireAdmin(actor);
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, company_id, name) VALUES (?, ?, ?)", projectId, actor.companyId(), name);
        jdbc.update("INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'OWNER')",
                actor.companyId(), projectId, admin.memberId());
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), admin.memberId(), "PROJECT_CREATED", "PROJECT",
                projectId, null, Map.of("name", name));
        return new ProjectView(projectId, name, false, 0);
    }

    /** 公司管理员或本项目成员可查看项目元数据；其他人一律 404，不泄露存在性。 */
    public ProjectView get(AuthenticatedActor actor, UUID projectId) {
        requireProjectManagerOrMember(actor, projectId);
        return loadProject(actor.companyId(), projectId);
    }

    @Transactional
    public ProjectView rename(AuthenticatedActor actor, UUID projectId, String name, long expectedVersion) {
        var manager = requireProjectManager(actor, projectId);
        int updated = jdbc.update("""
                UPDATE project SET name = ?, version = version + 1, updated_at = now()
                WHERE company_id = ? AND id = ? AND version = ?
                """, name, actor.companyId(), projectId, expectedVersion);
        if (updated == 0) throw versionConflict();
        audit.record(actor.companyId(), actor.userId(), manager.memberId(), "PROJECT_RENAMED", "PROJECT",
                projectId, null, Map.of("name", name));
        return new ProjectView(projectId, name, manager.autoAssignEnabled(), expectedVersion + 1);
    }

    @Transactional
    public ProjectView changePolicy(AuthenticatedActor actor, UUID projectId, boolean autoAssignEnabled,
                                    long expectedVersion, String reason) {
        var manager = requireProjectManager(actor, projectId);
        int updated = jdbc.update("""
                UPDATE project SET auto_assign_enabled = ?, version = version + 1, updated_at = now()
                WHERE company_id = ? AND id = ? AND version = ?
                """, autoAssignEnabled, actor.companyId(), projectId, expectedVersion);
        if (updated == 0) throw versionConflict();
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), manager.memberId(), "PROJECT_POLICY_CHANGED", "PROJECT",
                projectId, reason, Map.of("autoAssignEnabled", autoAssignEnabled));
        return new ProjectView(projectId, manager.name(), autoAssignEnabled, expectedVersion + 1);
    }

    public List<ProjectMemberView> listMembers(AuthenticatedActor actor, UUID projectId) {
        requireProjectManagerOrMember(actor, projectId);
        return jdbc.query("""
                SELECT pm.member_id, m.user_id, u.username, pm.role, pm.resource_permission
                FROM project_member pm
                JOIN company_member m ON m.company_id = pm.company_id AND m.id = pm.member_id
                JOIN app_user u ON u.id = m.user_id
                WHERE pm.company_id = ? AND pm.project_id = ? ORDER BY u.username
                """, (rs, row) -> new ProjectMemberView(rs.getObject("member_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("username"),
                rs.getString("role"), rs.getBoolean("resource_permission")), actor.companyId(), projectId);
    }

    @Transactional
    public ProjectMemberView addMember(AuthenticatedActor actor, UUID projectId, UUID memberId,
                                       String role, boolean resourcePermission) {
        var manager = requireProjectManager(actor, projectId);
        validateProjectRole(role);
        requireActiveCompanyMember(actor.companyId(), memberId);
        try {
            jdbc.update("""
                    INSERT INTO project_member(company_id, project_id, member_id, role, resource_permission)
                    VALUES (?, ?, ?, ?, ?)
                    """, actor.companyId(), projectId, memberId, role, resourcePermission);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "PROJECT_MEMBERSHIP_EXISTS", "该成员已在项目中");
        }
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), manager.memberId(), "PROJECT_MEMBER_ADDED", "PROJECT",
                projectId, null, Map.of("memberId", memberId, "role", role, "resourcePermission", resourcePermission));
        return projectMemberView(actor.companyId(), projectId, memberId);
    }

    @Transactional
    public ProjectMemberView updateMember(AuthenticatedActor actor, UUID projectId, UUID memberId,
                                          String role, boolean resourcePermission, String reason) {
        var manager = requireProjectManager(actor, projectId);
        validateProjectRole(role);
        ProjectMemberRow existing = loadProjectMember(actor.companyId(), projectId, memberId);
        if ("OWNER".equals(existing.role()) && !"OWNER".equals(role)) {
            ensureNotLastOwner(actor.companyId(), projectId, memberId);
        }
        jdbc.update("""
                UPDATE project_member SET role = ?, resource_permission = ?
                WHERE company_id = ? AND project_id = ? AND member_id = ?
                """, role, resourcePermission, actor.companyId(), projectId, memberId);
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), manager.memberId(), "PROJECT_MEMBER_UPDATED", "PROJECT",
                projectId, reason, Map.of("memberId", memberId, "role", role, "resourcePermission", resourcePermission));
        return projectMemberView(actor.companyId(), projectId, memberId);
    }

    @Transactional
    public void removeMember(AuthenticatedActor actor, UUID projectId, UUID memberId, String reason) {
        var manager = requireProjectManager(actor, projectId);
        ProjectMemberRow existing = loadProjectMember(actor.companyId(), projectId, memberId);
        if ("OWNER".equals(existing.role())) {
            ensureNotLastOwner(actor.companyId(), projectId, memberId);
        }
        jdbc.update("DELETE FROM project_member WHERE company_id = ? AND project_id = ? AND member_id = ?",
                actor.companyId(), projectId, memberId);
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), manager.memberId(), "PROJECT_MEMBER_REMOVED", "PROJECT",
                projectId, reason, Map.of("memberId", memberId));
    }

    private CompanyAccess.Membership requireAdmin(AuthenticatedActor actor) {
        var member = access.requireMember(actor.userId(), actor.companyId());
        if (!"ADMIN".equals(member.role())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "仅公司管理员可创建项目");
        }
        return member;
    }

    /** 项目负责人（OWNER）或公司管理员。非成员非管理员一律 404。 */
    private ManagerContext requireProjectManager(AuthenticatedActor actor, UUID projectId) {
        var member = access.requireMember(actor.userId(), actor.companyId());
        ProjectView project = loadProject(actor.companyId(), projectId);
        if ("ADMIN".equals(member.role())) {
            return new ManagerContext(member.memberId(), project.name(), project.autoAssignEnabled());
        }
        String role = projectRole(actor.companyId(), projectId, member.memberId());
        if (role == null) throw BusinessException.notFound();
        if (!"OWNER".equals(role)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "仅项目负责人或公司管理员可执行该操作");
        }
        return new ManagerContext(member.memberId(), project.name(), project.autoAssignEnabled());
    }

    private void requireProjectManagerOrMember(AuthenticatedActor actor, UUID projectId) {
        var member = access.requireMember(actor.userId(), actor.companyId());
        loadProject(actor.companyId(), projectId);
        if ("ADMIN".equals(member.role())) return;
        if (projectRole(actor.companyId(), projectId, member.memberId()) == null) throw BusinessException.notFound();
    }

    private ProjectView loadProject(UUID companyId, UUID projectId) {
        return jdbc.query("SELECT id, name, auto_assign_enabled, version FROM project WHERE company_id = ? AND id = ?",
                (rs, row) -> new ProjectView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("auto_assign_enabled"), rs.getLong("version")),
                companyId, projectId).stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    private String projectRole(UUID companyId, UUID projectId, UUID memberId) {
        return jdbc.query("SELECT role FROM project_member WHERE company_id = ? AND project_id = ? AND member_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, companyId, projectId, memberId);
    }

    private void requireActiveCompanyMember(UUID companyId, UUID memberId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM company_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.company_id = ? AND m.id = ? AND m.status = 'ACTIVE' AND u.enabled
                """, Integer.class, companyId, memberId);
        if (count == null || count == 0) throw BusinessException.notFound();
    }

    private void ensureNotLastOwner(UUID companyId, UUID projectId, UUID memberId) {
        Integer owners = jdbc.queryForObject("""
                SELECT count(*) FROM project_member
                WHERE company_id = ? AND project_id = ? AND role = 'OWNER' AND member_id <> ?
                """, Integer.class, companyId, projectId, memberId);
        if (owners != null && owners == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "LAST_OWNER_REQUIRED", "项目至少需要一名负责人");
        }
    }

    private ProjectMemberRow loadProjectMember(UUID companyId, UUID projectId, UUID memberId) {
        return jdbc.query("""
                SELECT role, resource_permission FROM project_member
                WHERE company_id = ? AND project_id = ? AND member_id = ?
                """, (rs, row) -> new ProjectMemberRow(rs.getString("role"), rs.getBoolean("resource_permission")),
                companyId, projectId, memberId).stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    private ProjectMemberView projectMemberView(UUID companyId, UUID projectId, UUID memberId) {
        return jdbc.query("""
                SELECT pm.member_id, m.user_id, u.username, pm.role, pm.resource_permission
                FROM project_member pm
                JOIN company_member m ON m.company_id = pm.company_id AND m.id = pm.member_id
                JOIN app_user u ON u.id = m.user_id
                WHERE pm.company_id = ? AND pm.project_id = ? AND pm.member_id = ?
                """, (rs, row) -> new ProjectMemberView(rs.getObject("member_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("username"),
                rs.getString("role"), rs.getBoolean("resource_permission")), companyId, projectId, memberId)
                .stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    private void validateProjectRole(String role) {
        if (role == null || !role.matches("OWNER|MEMBER")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "项目角色必须是 OWNER 或 MEMBER");
        }
    }

    private BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "版本已变化，请刷新后重试");
    }

    private record ManagerContext(UUID memberId, String name, boolean autoAssignEnabled) {}
    private record ProjectMemberRow(String role, boolean resourcePermission) {}

    public record ProjectView(UUID id, String name, boolean autoAssignEnabled, long version) {}
    public record ProjectMemberView(UUID memberId, UUID userId, String username, String role, boolean resourcePermission) {}
}
