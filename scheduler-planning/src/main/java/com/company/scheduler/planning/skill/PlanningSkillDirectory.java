package com.company.scheduler.planning.skill;
import com.company.scheduler.shared.contract.SkillDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class PlanningSkillDirectory implements SkillDirectory {
    private final JdbcTemplate jdbc;
    public PlanningSkillDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<String> activeCodes(UUID companyId) {
        return jdbc.queryForList("SELECT code FROM skill WHERE company_id = ? AND active = true ORDER BY code", String.class, companyId);
    }
}
