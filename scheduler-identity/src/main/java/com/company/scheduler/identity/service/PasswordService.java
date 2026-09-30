package com.company.scheduler.identity.service;

import com.company.scheduler.shared.error.BusinessException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前登录用户自助修改密码：校验旧密码、强制密码策略、撤销其他会话。 */
@Service
public class PasswordService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SessionRevoker sessionRevoker;
    private final IdentityAuditService audit;

    public PasswordService(JdbcTemplate jdbc, PasswordEncoder encoder, PasswordPolicy policy,
                           SessionRevoker sessionRevoker, IdentityAuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policy = policy;
        this.sessionRevoker = sessionRevoker;
        this.audit = audit;
    }

    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword, String keepSessionId) {
        policy.validate(newPassword);
        String hash = jdbc.query("SELECT password_hash FROM app_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        if (hash == null || !encoder.matches(currentPassword, hash)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "当前密码不正确");
        }
        jdbc.update("""
                UPDATE app_user SET password_hash = ?, must_change_password = false, password_changed_at = now(),
                    failed_login_attempts = 0, locked_until = NULL, version = version + 1, updated_at = now()
                WHERE id = ?
                """, encoder.encode(newPassword), userId);
        int revoked = sessionRevoker.revokeAll(userId, keepSessionId);
        audit.record(null, userId, null, "PASSWORD_CHANGED", "APP_USER", userId, null,
                Map.of("revokedSessions", revoked));
    }
}
