package com.company.scheduler.identity.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;

/** 当前请求的认证主体 + 已选择的公司上下文。公司头只是选择器，成员关系由 CompanyAccess 验证。 */
public record AuthenticatedActor(UUID userId, UUID companyId) {
    public static AuthenticatedActor of(Authentication authentication, UUID companyId) {
        return new AuthenticatedActor(UUID.fromString(authentication.getName()), companyId);
    }
}
