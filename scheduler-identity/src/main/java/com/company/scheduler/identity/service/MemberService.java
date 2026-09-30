package com.company.scheduler.identity.service;

import com.company.scheduler.identity.security.AuthenticatedActor;
import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.error.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 公司成员管理：查询、创建/邀请、角色、禁用/启用、管理员重置密码。仅公司管理员可写。 */
@Service
public class MemberService {
    private static final String ROLE_PATTERN = "ADMIN|MANAGER|MEMBER";

    private final JdbcTemplate jdbc;
    private final CompanyAccess access;
    private final PasswordEncoder encoder;
    private final SessionRevoker sessionRevoker;
    private final IdentityAuditService audit;
    private final PlanningGuardRevision guard;

    public MemberService(JdbcTemplate jdbc, CompanyAccess access, PasswordEncoder encoder,
                         SessionRevoker sessionRevoker,
                         IdentityAuditService audit, PlanningGuardRevision guard) {
        this.jdbc = jdbc;
        this.access = access;
        this.encoder = encoder;
        this.sessionRevoker = sessionRevoker;
        this.audit = audit;
        this.guard = guard;
    }

    /** 公司内任意有效成员可查看成员名册（不含敏感字段）；后续任务分派界面依赖该列表。 */
    public List<MemberView> list(AuthenticatedActor actor) {
        access.requireMember(actor.userId(), actor.companyId());
        return jdbc.query("""
                SELECT m.id, m.user_id, u.username, m.role, m.status, m.version
                FROM company_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.company_id = ? ORDER BY u.username
                """, (rs, row) -> new MemberView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("username"), rs.getString("role"), rs.getString("status"), rs.getLong("version")),
                actor.companyId());
    }

    /**
     * 创建/邀请成员。username 已存在时直接加入本公司（沿用其现有密码，不返回临时密码）；
     * 不存在时创建账号并生成一次性临时密码，首次登录强制改密。
     */
    @Transactional
    public MemberCreated create(AuthenticatedActor actor, String username, String role) {
        var admin = requireAdmin(actor);
        validateRole(role);
        UUID userId = jdbc.query("SELECT id FROM app_user WHERE username = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, username);
        String temporaryPassword = null;
        boolean accountCreated = false;
        if (userId == null) {
            userId = UUID.randomUUID();
            temporaryPassword = TemporaryPassword.generate();
            jdbc.update("INSERT INTO app_user(id, username, password_hash, must_change_password) VALUES (?, ?, ?, true)",
                    userId, username, encoder.encode(temporaryPassword));
            accountCreated = true;
        }
        UUID memberId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, ?)",
                    memberId, actor.companyId(), userId, role);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "MEMBERSHIP_EXISTS", "该用户已是本公司成员");
        }
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), admin.memberId(), "MEMBER_CREATED", "COMPANY_MEMBER",
                memberId, null, Map.of("username", username, "role", role, "accountCreated", accountCreated));
        return new MemberCreated(memberId, userId, username, role, temporaryPassword);
    }

    @Transactional
    public MemberView changeRole(AuthenticatedActor actor, UUID memberId, String role, long expectedVersion, String reason) {
        var admin = requireAdmin(actor);
        validateRole(role);
        MemberRow target = loadMember(actor.companyId(), memberId);
        if (target.userId().equals(actor.userId()) && !"ADMIN".equals(role)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CANNOT_CHANGE_OWN_ROLE", "不能降低自己的管理员角色");
        }
        if ("ADMIN".equals(target.role()) && !"ADMIN".equals(role)) {
            ensureNotLastAdmin(actor.companyId(), memberId, "LAST_ADMIN_REQUIRED", "公司至少需要一名在职管理员");
        }
        int updated = jdbc.update("""
                UPDATE company_member SET role = ?, version = version + 1, updated_at = now()
                WHERE company_id = ? AND id = ? AND version = ?
                """, role, actor.companyId(), memberId, expectedVersion);
        if (updated == 0) throw versionConflict();
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), admin.memberId(), "MEMBER_ROLE_CHANGED", "COMPANY_MEMBER",
                memberId, reason, Map.of("from", target.role(), "to", role));
        return new MemberView(memberId, target.userId(), target.username(), role, target.status(), expectedVersion + 1);
    }

    @Transactional
    public MemberView setStatus(AuthenticatedActor actor, UUID memberId, boolean active, String reason) {
        var admin = requireAdmin(actor);
        MemberRow target = loadMember(actor.companyId(), memberId);
        String newStatus = active ? "ACTIVE" : "INACTIVE";
        if (target.status().equals(newStatus)) {
            return new MemberView(memberId, target.userId(), target.username(), target.role(), target.status(), target.version());
        }
        if (!active) {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "禁用成员必须填写原因");
            }
            if (target.userId().equals(actor.userId())) {
                throw new BusinessException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "不能禁用自己的成员身份");
            }
            if ("ADMIN".equals(target.role())) {
                ensureNotLastAdmin(actor.companyId(), memberId, "LAST_ADMIN_REQUIRED", "公司至少需要一名在职管理员");
            }
        }
        jdbc.update("UPDATE company_member SET status = ?, version = version + 1, updated_at = now() WHERE company_id = ? AND id = ?",
                newStatus, actor.companyId(), memberId);
        guard.bump(actor.companyId());
        audit.record(actor.companyId(), actor.userId(), admin.memberId(),
                active ? "MEMBER_ENABLED" : "MEMBER_DISABLED", "COMPANY_MEMBER", memberId, reason, null);
        return new MemberView(memberId, target.userId(), target.username(), target.role(), newStatus, target.version() + 1);
    }

    /** 管理员重置成员密码：生成一次性临时密码、强制改密并撤销该用户全部会话。 */
    @Transactional
    public PasswordResetResult resetPassword(AuthenticatedActor actor, UUID memberId, String reason) {
        var admin = requireAdmin(actor);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "重置成员密码必须填写原因");
        }
        MemberRow target = loadMember(actor.companyId(), memberId);
        String temporaryPassword = TemporaryPassword.generate();
        jdbc.update("""
                UPDATE app_user SET password_hash = ?, must_change_password = true, password_changed_at = now(),
                    failed_login_attempts = 0, locked_until = NULL, version = version + 1, updated_at = now()
                WHERE id = ?
                """, encoder.encode(temporaryPassword), target.userId());
        int revoked = sessionRevoker.revokeAll(target.userId(), null);
        audit.record(actor.companyId(), actor.userId(), admin.memberId(), "MEMBER_PASSWORD_RESET", "COMPANY_MEMBER",
                memberId, reason, Map.of("revokedSessions", revoked));
        return new PasswordResetResult(memberId, target.userId(), temporaryPassword);
    }

    private CompanyAccess.Membership requireAdmin(AuthenticatedActor actor) {
        var member = access.requireMember(actor.userId(), actor.companyId());
        if (!"ADMIN".equals(member.role())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "仅公司管理员可执行成员管理操作");
        }
        return member;
    }

    private MemberRow loadMember(UUID companyId, UUID memberId) {
        return jdbc.query("""
                SELECT m.user_id, u.username, m.role, m.status, m.version
                FROM company_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.company_id = ? AND m.id = ?
                """, (rs, row) -> new MemberRow(rs.getObject("user_id", UUID.class), rs.getString("username"),
                rs.getString("role"), rs.getString("status"), rs.getLong("version")), companyId, memberId)
                .stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    private void ensureNotLastAdmin(UUID companyId, UUID memberId, String code, String message) {
        Integer admins = jdbc.queryForObject("""
                SELECT count(*) FROM company_member
                WHERE company_id = ? AND role = 'ADMIN' AND status = 'ACTIVE' AND id <> ?
                """, Integer.class, companyId, memberId);
        if (admins != null && admins == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, code, message);
        }
    }

    private void validateRole(String role) {
        if (role == null || !role.matches(ROLE_PATTERN)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "角色必须是 ADMIN、MANAGER 或 MEMBER");
        }
    }

    private BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "版本已变化，请刷新后重试");
    }

    private record MemberRow(UUID userId, String username, String role, String status, long version) {}

    public record MemberView(UUID memberId, UUID userId, String username, String role, String status, long version) {}
    public record MemberCreated(UUID memberId, UUID userId, String username, String role, String temporaryPassword) {}
    public record PasswordResetResult(UUID memberId, UUID userId, String temporaryPassword) {}
}
