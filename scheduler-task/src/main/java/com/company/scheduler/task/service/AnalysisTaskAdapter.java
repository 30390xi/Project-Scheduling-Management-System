package com.company.scheduler.task.service;
import com.company.scheduler.shared.contract.*;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.*;
import com.company.scheduler.task.domain.*;
import com.company.scheduler.task.store.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AnalysisTaskAdapter implements AnalysisTaskPort {
    private final TaskService service;
    private final TaskRepository repository;
    private final TaskAccessService access;
    private final PlanningGuardService guard;
    private final TaskTransitionService transitions;
    private final DependencyService dependencies;
    private final TaskStatusEventRepository events;
    private final JdbcTemplate jdbc;
    public AnalysisTaskAdapter(TaskService service, TaskRepository repository, TaskAccessService access,
            PlanningGuardService guard, TaskTransitionService transitions, DependencyService dependencies,
            TaskStatusEventRepository events, JdbcTemplate jdbc) {
        this.service=service; this.repository=repository; this.access=access; this.guard=guard;
        this.transitions=transitions; this.dependencies=dependencies; this.events=events; this.jdbc=jdbc;
    }
    public AnalysisRequests.Request request(UUID actor, UUID company, UUID taskId) {
        access.requireMembership(actor, company);
        var task = repository.find(company, taskId).orElseThrow(BusinessException::notFound);
        var member = access.requireProjectAccess(actor, company, task.projectId());
        if (!member.canManage() && !member.memberId().equals(task.createdBy()))
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只有创建者或项目管理人员可以重新分析");
        if (task.nodeType() != NodeType.LEAF || !Set.of(TaskStatus.SUBMITTED, TaskStatus.NEEDS_INFO).contains(task.status()))
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "只能重新分析尚未确认的叶子任务");
        return new AnalysisRequests.Request(actor, company, task.projectId(), member.memberId(), taskId,
                task.version(), task.title() + "\n" + task.description());
    }
    public void authorize(UUID actor, UUID company, UUID taskId) {
        access.requireMembership(actor, company);
        var task=repository.find(company,taskId).orElseThrow(BusinessException::notFound);
        access.requireProjectAccess(actor,company,task.projectId());
    }
    @Transactional
    public List<UUID> confirm(UUID actor,UUID company,UUID taskId,long taskVersion,List<Leaf> leaves,List<Edge> edges) {
        access.requireMembership(actor,company); guard.lockAndBump(company);
        var root=service.lockAndCheckVersion(company,taskId,taskVersion);
        var member=access.requireProjectAccess(actor,company,root.projectId()); access.requireManager(member);
        if (root.nodeType()!=NodeType.LEAF || !Set.of(TaskStatus.SUBMITTED,TaskStatus.NEEDS_INFO).contains(root.status()))
            throw new BusinessException(HttpStatus.CONFLICT,"ANALYSIS_TASK_CHANGED","任务已变更或已确认，请重新分析");
        if(leaves.isEmpty() || leaves.size()>30) throw new BusinessException(HttpStatus.CONFLICT,"INVALID_DECOMPOSITION","子任务数量无效");
        Map<String,UUID> ids=new LinkedHashMap<>();
        for(var leaf:leaves) {
            var skills=leaf.skills().stream().map(s->new SkillRequirementDto(s.code(),s.minimumLevel(),true)).toList();
            TaskDetail task;
            if(leaves.size()==1 && leaf.key().equals("ROOT")) {
                task=service.update(actor,company,taskId,new UpdateTaskRequest(root.title(),root.description(),root.taskType(),root.priority(),
                        root.dueDate(),root.dueType(),root.earliestStartDate(),leaf.minutes(),root.fixedAssigneeId(),root.preferredMemberId(),
                        String.join("\n",leaf.acceptanceCriteria()),skills),taskVersion);
            } else {
                task=service.create(actor,company,new CreateTaskRequest(root.projectId(),taskId,leaf.title(),leaf.scope(),root.taskType(),root.priority(),
                        root.dueDate(),root.dueType(),root.earliestStartDate(),leaf.minutes(),null,null,
                        String.join("\n",leaf.acceptanceCriteria()),skills),null);
                repository.markSubmitted(company,task.id(),repository.nextTaskNo(company));
                events.insert(company,task.id(),TaskStatus.DRAFT,TaskStatus.SUBMITTED,member.memberId(),"人工确认 AI 拆解");
                task=service.detail(actor,company,task.id());
            }
            jdbc.update("UPDATE task SET estimate_source='AI_CONFIRMED' WHERE company_id=? AND id=?",company,task.id());
            if(ids.put(leaf.key(),task.id())!=null) throw new BusinessException(HttpStatus.CONFLICT,"DUPLICATE_SUBTASK_KEY","子任务标识重复");
        }
        for(var leaf:leaves) {
            var predecessors=edges.stream().filter(e->e.to().equals(leaf.key())).map(e->ids.get(e.from())).toList();
            if(predecessors.stream().anyMatch(Objects::isNull)) throw new BusinessException(HttpStatus.CONFLICT,"INVALID_DEPENDENCY","依赖端点不存在");
            if(!predecessors.isEmpty()) {
                UUID id=ids.get(leaf.key()); var current=service.detail(actor,company,id);
                dependencies.replacePredecessors(actor,company,id,new DependencyUpdateRequest(predecessors),current.version());
            }
        }
        // Build the entire tree and DAG before READY transitions aggregate the parent state.
        for (UUID id : ids.values()) {
            var current = service.detail(actor, company, id);
            transitions.transition(actor, company, id,
                    new TransitionRequest(TransitionAction.MARK_READY, "人工确认 AI 建议", null, null), current.version());
        }
        return List.copyOf(ids.values());
    }
}
