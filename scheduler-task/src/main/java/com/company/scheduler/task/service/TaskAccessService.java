package com.company.scheduler.task.service;

import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 任务域权限。公司角色来自 CompanyAccess 契约；项目内角色暂由本模块只读查询 project_member，
 * 待集成人把“项目角色”纳入公共契约后替换（见本轮协调事项）。
 */
@Service
public class TaskAccessService {
    private final CompanyAccess companyAccess;
    private final JdbcTemplate jdbc;

    public TaskAccessService(CompanyAccess companyAccess, JdbcTemplate jdbc) {
        this.companyAccess = companyAccess;
        this.jdbc = jdbc;
    }

    public record ProjectAccess(UUID memberId, String companyRole, String projectRole) {
        public boolean isCompanyAdmin() { return "ADMIN".equals(companyRole); }
        public boolean isProjectOwner() { return "OWNER".equals(projectRole); }
        /** 公司管理员或项目负责人：批准拆解、验收、取消、代填等管理操作。 */
        public boolean canManage() { return isCompanyAdmin() || isProjectOwner(); }
    }

    /** 仅校验公司成员身份，用于跨项目列表等场景。 */
    public CompanyAccess.Membership requireMembership(UUID userId, UUID companyId) {
        return companyAccess.requireMember(userId, companyId);
    }

    /** 校验成员身份与项目成员关系；不满足时统一 404，不泄露存在性。 */
    public ProjectAccess requireProjectAccess(UUID userId, UUID companyId, UUID projectId) {
        var membership = companyAccess.requireMember(userId, companyId);
        String projectRole = jdbc.query("""
                SELECT role FROM project_member WHERE company_id = ? AND project_id = ? AND member_id = ?
                """, rs -> rs.next() ? rs.getString("role") : null, companyId, projectId, membership.memberId());
        if (projectRole == null) throw BusinessException.notFound();
        return new ProjectAccess(membership.memberId(), membership.role(), projectRole);
    }

    public void requireManager(ProjectAccess access) {
        if (!access.canManage()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "需要公司管理员或项目负责人权限");
        }
    }
}
