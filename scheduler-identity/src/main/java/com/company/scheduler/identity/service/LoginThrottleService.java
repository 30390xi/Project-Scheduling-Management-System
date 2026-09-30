package com.company.scheduler.identity.service;

import com.company.scheduler.shared.error.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 登录限流：账号级连续失败锁定持久化在 app_user（多实例共享、重启保留），
 * IP 级失败窗口保存在内存（重启清零即可接受）。均不区分"账号不存在"，避免泄露账号存在性。
 */
@Service
@EnableConfigurationProperties(LoginSecurityProperties.class)
public class LoginThrottleService {
    private final JdbcTemplate jdbc;
    private final LoginSecurityProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> ipFailures = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottleService(JdbcTemplate jdbc, LoginSecurityProperties properties) {
        this(jdbc, properties, Clock.systemUTC());
    }

    LoginThrottleService(JdbcTemplate jdbc, LoginSecurityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** 登录前检查。锁定期内一律拒绝，返回 429，不验证密码是否正确。 */
    public void checkAllowed(String username, String remoteIp) {
        checkIpWindow(remoteIp);
        Instant lockedUntil = jdbc.query("SELECT locked_until FROM app_user WHERE username = ?",
                rs -> {
                    if (!rs.next()) return null;
                    var value = rs.getObject(1, java.time.OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                }, username);
        if (lockedUntil != null && lockedUntil.isAfter(clock.instant())) {
            throw rateLimited(Duration.between(clock.instant(), lockedUntil).toSeconds());
        }
    }

    /** 认证失败后调用。账号计数达到阈值即锁定；IP 窗口同步计数。 */
    public void onFailure(String username, String remoteIp) {
        recordIpFailure(remoteIp);
        Instant lockUntil = Instant.now(clock).plus(Duration.ofMinutes(properties.getLockMinutes()));
        try {
            jdbc.update("""
                    UPDATE app_user SET
                        failed_login_attempts = failed_login_attempts + 1,
                        locked_until = CASE WHEN failed_login_attempts + 1 >= ? THEN ? ELSE locked_until END,
                        updated_at = now()
                    WHERE username = ?
                    """, properties.getMaxFailedAttempts(), java.sql.Timestamp.from(lockUntil), username);
        } catch (DuplicateKeyException ignored) {
            // 并发失败计数由数据库行锁串行化，理论上不会到这里
        }
    }

    /** 认证成功后调用，清除账号计数。IP 窗口保留（防止用正确密码探测其他账号失败计数）。 */
    public void onSuccess(String username) {
        jdbc.update("UPDATE app_user SET failed_login_attempts = 0, locked_until = NULL WHERE username = ?", username);
    }

    private void checkIpWindow(String remoteIp) {
        if (remoteIp == null) return;
        Deque<Instant> window = ipFailures.get(remoteIp);
        if (window == null) return;
        synchronized (window) {
            evictExpired(window);
            if (window.size() >= properties.getIpMaxFailedAttempts()) {
                throw rateLimited(properties.getIpWindowMinutes() * 60L);
            }
        }
    }

    private void recordIpFailure(String remoteIp) {
        if (remoteIp == null) return;
        Deque<Instant> window = ipFailures.computeIfAbsent(remoteIp, key -> new ArrayDeque<>());
        synchronized (window) {
            evictExpired(window);
            window.addLast(Instant.now(clock));
        }
    }

    private void evictExpired(Deque<Instant> window) {
        Instant cutoff = Instant.now(clock).minus(Duration.ofMinutes(properties.getIpWindowMinutes()));
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    private BusinessException rateLimited(long retryAfterSeconds) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED",
                "登录尝试过于频繁，请约 " + Math.max(1, retryAfterSeconds / 60) + " 分钟后再试");
    }
}
