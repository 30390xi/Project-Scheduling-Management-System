package com.company.scheduler.planning.support;

import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Company-scoped authorization decisions resolved from A's CompanyAccess membership. */
public final class PlanningPermissions {
    private PlanningPermissions() {}

    public static boolean isAdmin(CompanyAccess.Membership membership) {
        return "ADMIN".equals(membership.role());
    }

    public static boolean isManager(CompanyAccess.Membership membership) {
        return "ADMIN".equals(membership.role()) || "MANAGER".equals(membership.role());
    }

    public static void requireAdmin(CompanyAccess.Membership membership) {
        if (!isAdmin(membership)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "仅公司管理员可执行该操作");
        }
    }

    public static void requireSelfOrManager(CompanyAccess.Membership membership, UUID targetMemberId) {
        if (!membership.memberId().equals(targetMemberId) && !isManager(membership)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "仅本人或公司管理者可访问该成员数据");
        }
    }
}
