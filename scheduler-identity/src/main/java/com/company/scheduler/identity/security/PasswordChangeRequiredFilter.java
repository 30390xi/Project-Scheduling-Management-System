package com.company.scheduler.identity.security;

import com.company.scheduler.shared.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 持有临时密码（创建/重置）的账号在改密前只能访问改密、退出和 CSRF 接口，
 * 其余接口一律 403 PASSWORD_CHANGE_REQUIRED。
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PasswordChangeRequiredFilter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.equals("/api/v1/auth/csrf")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/me/password");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            chain.doFilter(request, response);
            return;
        }
        Boolean mustChange = jdbc.query("SELECT must_change_password FROM app_user WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, UUID.fromString(authentication.getName()));
        if (Boolean.TRUE.equals(mustChange)) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    ApiError.of("PASSWORD_CHANGE_REQUIRED", "首次登录或密码已重置，请先通过 /api/v1/me/password 修改密码"));
            return;
        }
        chain.doFilter(request, response);
    }
}
