package com.company.scheduler.agent.ai;
import com.company.scheduler.shared.contract.*;
import com.company.scheduler.shared.error.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class TaskAnalysisRequests implements AnalysisRequests {
    private final AiAnalysisService service;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectProvider<CompanyAccess> access;
    private final ObjectProvider<SkillDirectory> skills;
    public TaskAnalysisRequests(AiAnalysisService service, ObjectProvider<CompanyAccess> access,
                                ObjectProvider<SkillDirectory> skills, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.service = service; this.access = access; this.skills = skills; this.jdbc = jdbc;
    }
    @Transactional
    public Submission submit(Request r) {
        var authorization = access.getIfAvailable();
        var directory = skills.getIfAvailable();
        if (authorization == null || directory == null) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                "ANALYSIS_INTEGRATION_UNAVAILABLE", "分析所需权限或技能服务不可用");
        var member = authorization.requireMember(r.userId(), r.companyId());
        authorization.requireProjectAccess(r.userId(), r.companyId(), r.projectId());
        if (!member.memberId().equals(r.memberId())) throw BusinessException.notFound();
        var result = service.submit(r.companyId(), r.memberId(), r.taskId(), r.taskText(),
                directory.activeCodes(r.companyId()), "task:" + r.taskId() + ":v" + r.taskVersion());
        jdbc.update("UPDATE ai_analysis_run SET input_snapshot = input_snapshot || ?::jsonb WHERE company_id = ? AND id = ?",
                com.company.scheduler.agent.AgentJson.toJson(java.util.Map.of("taskVersion", r.taskVersion(), "projectId", r.projectId().toString())),
                r.companyId(), result.runId());
        return new Submission(result.runId(), result.jobId());
    }
}
