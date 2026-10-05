package com.company.scheduler.agent.ai;

import com.company.scheduler.agent.AgentJson;
import com.company.scheduler.agent.audit.*;
import com.company.scheduler.shared.contract.*;
import com.company.scheduler.shared.error.BusinessException;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisConfirmationService {
    public record Confirmation(List<UUID> taskIds, UUID simulationJobId) {}
    private final AiAnalysisRunRepository runs;
    private final ObjectProvider<AnalysisRequests> requests;
    private final ObjectProvider<AnalysisTaskPort> tasks;
    private final ObjectProvider<PlanningService> planning;
    private final ObjectProvider<CompanyAccess> access;
    private final ObjectProvider<SkillDirectory> skills;
    private final AiOutputValidator validator;
    private final AiGatewayProperties properties;
    private final EstimationService estimation;
    private final AuditRecorder audit;
    private final JdbcTemplate jdbc;
    public AnalysisConfirmationService(AiAnalysisRunRepository runs, ObjectProvider<AnalysisTaskPort> tasks,
            ObjectProvider<PlanningService> planning, ObjectProvider<CompanyAccess> access, ObjectProvider<SkillDirectory> skills,
            AiOutputValidator validator, AiGatewayProperties properties, EstimationService estimation, AuditRecorder audit, JdbcTemplate jdbc, ObjectProvider<AnalysisRequests> requests) {
        this.requests = requests;
        this.runs=runs; this.tasks=tasks; this.planning=planning; this.access=access; this.skills=skills;
        this.validator=validator; this.properties=properties; this.estimation=estimation; this.audit=audit; this.jdbc=jdbc;
    }
    @Transactional
    public AnalysisRequests.Submission analyze(UUID actor, UUID company, UUID taskId) {
        return requests.getObject().submit(tasks.getObject().request(actor, company, taskId));
    }
    public List<AiAnalysisRun> list(UUID actor, UUID company, UUID taskId) {
        access.getObject().requireMember(actor,company);
        tasks.getObject().authorize(actor,company,taskId);
        return jdbc.queryForList("SELECT id FROM ai_analysis_run WHERE company_id=? AND task_aggregate_id=? ORDER BY created_at DESC LIMIT 50",
                UUID.class,company,taskId).stream().map(id->runs.findById(id).orElseThrow()).toList();
    }
    public AiAnalysisRun get(UUID actor, UUID company, UUID runId) {
        access.getObject().requireMember(actor,company);
        var run=runs.findById(runId).filter(r->r.companyId().equals(company)).orElseThrow(BusinessException::notFound);
        if(run.taskAggregateId()==null) throw BusinessException.notFound();
        tasks.getObject().authorize(actor,company,run.taskAggregateId());
        return run;
    }
    @Transactional
    public Confirmation confirm(UUID actor, UUID company, UUID runId, boolean confirmAssumptions) {
        var run=get(actor,company,runId);
        jdbc.queryForObject("SELECT id FROM ai_analysis_run WHERE company_id=? AND id=? FOR UPDATE",UUID.class,company,runId);
        String previous=jdbc.queryForObject("SELECT confirmation_result::text FROM ai_analysis_run WHERE company_id=? AND id=?",String.class,company,runId);
        if(previous!=null) return AgentJson.fromJson(previous,Confirmation.class);
        if(!Set.of("SUCCEEDED","REUSED").contains(run.status())) throw error("ANALYSIS_NOT_READY","分析尚未成功，不能确认");
        var tree=AgentJson.toJsonNode(run.validatedOutputJson());
        var validation=validator.validate(tree.path("output").toString(),skills.getObject().activeCodes(company));
        if(!validation.valid()) throw error("ANALYSIS_STALE","当前技能或规则已变更，请重新分析");
        var output=validation.output();
        if(!output.missingInformation().isEmpty()) throw error("ANALYSIS_NEEDS_INFO","请补充缺失信息后重新提交，或使用人工估算");
        if(!output.assumptions().isEmpty() && !confirmAssumptions) throw error("ASSUMPTIONS_UNCONFIRMED","请明确确认关键假设");
        var snapshot=AgentJson.toJsonNode(run.inputSnapshotJson());
        if(!snapshot.has("taskVersion")) throw error("ANALYSIS_CONTEXT_MISSING","分析缺少任务版本，必须重新提交");
        List<AnalysisTaskPort.Leaf> leaves;
        if(output.subtasks().isEmpty()) {
            leaves=List.of(new AnalysisTaskPort.Leaf("ROOT",output.summary(),output.summary(),
                    estimation.compute(output.estimate(),properties.bufferRatio()).plannedMinutesWithBuffer(),
                    requirements(output.requiredSkills()),output.acceptanceCriteria()));
        } else leaves=output.subtasks().stream().map(s->new AnalysisTaskPort.Leaf(s.clientKey(),s.title(),s.scope(),
                estimation.compute(s.estimate(),properties.bufferRatio()).plannedMinutesWithBuffer(),
                requirements(s.requiredSkills()),s.acceptanceCriteria())).toList();
        var edges=output.dependencies().stream().map(e->new AnalysisTaskPort.Edge(e.from(),e.to())).toList();
        var ids=tasks.getObject().confirm(actor,company,run.taskAggregateId(),snapshot.path("taskVersion").asLong(),leaves,edges);
        var job=planning.getObject().requestSimulation(actor,company,ids);
        var result=new Confirmation(ids,job.jobId());
        var member=access.getObject().requireMember(actor,company);
        jdbc.update("UPDATE ai_analysis_run SET confirmation_result=?::jsonb, confirmed_by=?, confirmed_at=now() WHERE company_id=? AND id=?",
                AgentJson.toJson(result),member.memberId(),company,runId);
        audit.record(company,AuditEvent.ActorType.USER,member.memberId(),"AI_ANALYSIS_CONFIRMED","AI_ANALYSIS_RUN",runId,
                null,AgentJson.toJson(Map.of("taskCount",ids.size(),"assumptionsConfirmed",confirmAssumptions)),"人工确认建议",null);
        return result;
    }
    private List<TaskCatalog.SkillRequirement> requirements(List<AiAnalysisOutput.SkillRequirement> values) {
        return values.stream().map(s->new TaskCatalog.SkillRequirement(s.code(),s.minLevel())).toList();
    }
    private BusinessException error(String code,String message) { return new BusinessException(HttpStatus.CONFLICT,code,message); }
}
