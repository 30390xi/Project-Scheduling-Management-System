package com.company.scheduler.identity.service;

import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcCompanyAccess implements CompanyAccess {
    private final JdbcTemplate jdbc;
    public JdbcCompanyAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Membership requireMember(UUID userId, UUID companyId) {
        return jdbc.query("""
                SELECT m.id, m.role FROM company_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.company_id = ? AND m.user_id = ? AND m.status = 'ACTIVE' AND u.enabled
                """, (rs, row) -> new Membership(rs.getObject("id", UUID.class), companyId, userId, rs.getString("role")),
                companyId, userId).stream().findFirst().orElseThrow(BusinessException::notFound);
    }

    @Override
    public void requireProjectAccess(UUID userId, UUID companyId, UUID projectId) {
        Membership member = requireMember(userId, companyId);
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM project_member WHERE company_id = ? AND project_id = ? AND member_id = ?
                """, Integer.class, companyId, projectId, member.memberId());
        if (count == null || count == 0) throw BusinessException.notFound();
    }
}
