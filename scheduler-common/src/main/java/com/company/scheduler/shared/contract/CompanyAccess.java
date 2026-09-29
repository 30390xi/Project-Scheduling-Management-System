package com.company.scheduler.shared.contract;

import java.util.UUID;

/** Implemented by identity. Always validate an authenticated actor, never trust the company header alone. */
public interface CompanyAccess {
    Membership requireMember(UUID userId, UUID companyId);
    void requireProjectAccess(UUID userId, UUID companyId, UUID projectId);

    record Membership(UUID memberId, UUID companyId, UUID userId, String role) {}
}
