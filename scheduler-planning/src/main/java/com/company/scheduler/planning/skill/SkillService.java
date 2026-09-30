package com.company.scheduler.planning.skill;

import com.company.scheduler.planning.ledger.PlanningGuardRepository;
import com.company.scheduler.shared.error.BusinessException;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能目录与成员技能。管理员写入认证条目并递增 guard revision（影响候选可行性）；
 * 成员自报条目仅作待认证参考，不参与排期准入，也不影响计划有效性。
 */
@Service
public class SkillService {

    public record SkillAssignment(UUID skillId, int level) {}

    public record SkillView(UUID skillId, String code, String name, String description, boolean active) {}

    public record MemberSkillView(UUID skillId, String code, String name, int level,
                                  boolean selfReported, UUID certifiedBy, OffsetDateTime certifiedAt) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final PlanningGuardRepository guard;

    public SkillService(NamedParameterJdbcTemplate jdbc, PlanningGuardRepository guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    @Transactional
    public SkillView create(UUID companyId, String code, String name, String description) {
        UUID skillId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO skill(company_id, id, code, name, description)
                VALUES (:companyId, :id, :code, :name, :description)
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("id", skillId)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("description", description == null ? "" : description));
        if (inserted != 1) throw new IllegalStateException("Skill insert failed");
        return new SkillView(skillId, code, name, description == null ? "" : description, true);
    }

    @Transactional(readOnly = true)
    public List<SkillView> list(UUID companyId) {
        return jdbc.query("""
                SELECT id, code, name, description, active FROM skill
                 WHERE company_id = :companyId ORDER BY code
                """, new MapSqlParameterSource("companyId", companyId), (rs, i) -> new SkillView(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("active")));
    }

    @Transactional
    public SkillView update(UUID companyId, UUID skillId, String name, String description, Boolean active) {
        var current = jdbc.query("""
                SELECT name, description, active FROM skill
                 WHERE company_id = :companyId AND id = :skillId
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("skillId", skillId), (rs, i) -> new Object[] {
                        rs.getString("name"), rs.getString("description"), rs.getBoolean("active")})
                .stream().findFirst().orElseThrow(BusinessException::notFound);
        String newName = name != null ? name : (String) current[0];
        String newDescription = description != null ? description : (String) current[1];
        boolean newActive = active != null ? active : (Boolean) current[2];
        boolean activeChanged = newActive != (Boolean) current[2];
        if (activeChanged) guard.lockGuard(companyId);
        jdbc.update("""
                UPDATE skill SET name = :name, description = :description, active = :active,
                                 version = version + 1, updated_at = now()
                 WHERE company_id = :companyId AND id = :skillId
                """, new MapSqlParameterSource()
                .addValue("name", newName)
                .addValue("description", newDescription)
                .addValue("active", newActive)
                .addValue("companyId", companyId)
                .addValue("skillId", skillId));
        if (activeChanged) guard.bumpRevision(companyId);
        return new SkillView(skillId, null, newName, newDescription, newActive);
    }

    @Transactional(readOnly = true)
    public List<MemberSkillView> memberSkills(UUID companyId, UUID memberId) {
        return jdbc.query("""
                SELECT s.id, s.code, s.name, ms.level, ms.self_reported, ms.certified_by, ms.certified_at
                  FROM member_skill ms JOIN skill s
                    ON s.company_id = ms.company_id AND s.id = ms.skill_id
                 WHERE ms.company_id = :companyId AND ms.member_id = :memberId
                 ORDER BY s.code
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("memberId", memberId), (rs, i) -> new MemberSkillView(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getInt("level"), rs.getBoolean("self_reported"),
                        rs.getObject("certified_by", UUID.class),
                        rs.getObject("certified_at", OffsetDateTime.class)));
    }

    /** Admin: replaces the member's certified set; certified entries drive scheduling eligibility. */
    @Transactional
    public void certifySet(UUID companyId, UUID certifierMemberId, UUID memberId, List<SkillAssignment> items) {
        validate(items);
        guard.lockGuard(companyId);
        upsertSet(companyId, memberId, items, false, certifierMemberId);
        guard.bumpRevision(companyId);
    }

    /** Self report: replaces the member's self-reported set; certified entries are untouched. */
    @Transactional
    public void selfReportSet(UUID companyId, UUID memberId, List<SkillAssignment> items) {
        validate(items);
        upsertSet(companyId, memberId, items, true, null);
    }

    private void upsertSet(UUID companyId, UUID memberId, List<SkillAssignment> items,
                           boolean selfReported, UUID certifierId) {
        var skillIds = items.stream().map(SkillAssignment::skillId).toList();
        Integer known = jdbc.queryForObject("""
                SELECT count(*) FROM skill
                 WHERE company_id = :companyId AND id IN (:skillIds) AND active
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("skillIds", skillIds), Integer.class);
        if (known == null || known != skillIds.size()) {
            throw invalidRequest("技能不存在或已停用");
        }
        for (var item : items) {
            jdbc.update("""
                    INSERT INTO member_skill(company_id, member_id, skill_id, level, self_reported,
                                             certified_by, certified_at)
                    VALUES (:companyId, :memberId, :skillId, :level, :selfReported, :certifiedBy,
                            CASE WHEN CAST(:selfReported AS boolean) THEN NULL ELSE now() END)
                    ON CONFLICT (company_id, member_id, skill_id) DO UPDATE
                       SET level = EXCLUDED.level, self_reported = EXCLUDED.self_reported,
                           certified_by = EXCLUDED.certified_by, certified_at = EXCLUDED.certified_at,
                           updated_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("companyId", companyId)
                    .addValue("memberId", memberId)
                    .addValue("skillId", item.skillId())
                    .addValue("level", item.level())
                    .addValue("selfReported", selfReported)
                    .addValue("certifiedBy", certifierId));
        }
        jdbc.update("""
                DELETE FROM member_skill
                 WHERE company_id = :companyId AND member_id = :memberId
                   AND self_reported = :selfReported AND skill_id NOT IN (:skillIds)
                """, new MapSqlParameterSource()
                .addValue("companyId", companyId)
                .addValue("memberId", memberId)
                .addValue("selfReported", selfReported)
                .addValue("skillIds", skillIds));
    }

    private static void validate(List<SkillAssignment> items) {
        if (items == null || items.isEmpty()) {
            throw invalidRequest("技能列表不能为空");
        }
        var distinct = new LinkedHashSet<UUID>();
        for (var item : items) {
            if (item.skillId() == null || item.level() < 1 || item.level() > 5) {
                throw invalidRequest("技能等级必须介于 1 与 5 之间");
            }
            if (!distinct.add(item.skillId())) {
                throw invalidRequest("技能条目重复");
            }
        }
    }

    private static BusinessException invalidRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
