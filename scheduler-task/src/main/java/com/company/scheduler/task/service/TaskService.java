package com.company.scheduler.task.service;

import com.company.scheduler.shared.contract.AnalysisRequests;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.CreateTaskRequest;
import com.company.scheduler.task.api.dto.Paged;
import com.company.scheduler.task.api.dto.SkillRequirementDto;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.TaskSummary;
import com.company.scheduler.task.api.dto.UpdateTaskRequest;
import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.RevisionSource;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.store.TaskDependencyRepository;
import com.company.scheduler.task.store.TaskRemainingEstimateRepository;
import com.company.scheduler.task.store.TaskRepository;
import com.company.scheduler.task.store.TaskRevisionRepository;
import com.company.scheduler.task.store.TaskRow;
import com.company.scheduler.task.store.TaskSkillRepository;
import com.company.scheduler.task.store.TaskStatusEventRepository;
import com.company.scheduler.task.store.WorkLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private static final int MAX_DEPTH = 3;
    private static final int MAX_LEAVES_PER_TREE = 30;
    private static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository tasks;
    private final TaskRevisionRepository revisions;
    private final TaskSkillRepository skills;
    private final TaskDependencyRepository dependencies;
    private final TaskStatusEventRepository events;
    private final WorkLogRepository workLogs;
    private final TaskRemainingEstimateRepository remainingEstimates;
    private final TaskAccessService accessService;
    private final PlanningGuardService guard;
    private final ObjectMapper mapper;
    private final ObjectProvider<AnalysisRequests> jobDispatcher;

    public TaskService(TaskRepository tasks, TaskRevisionRepository revisions, TaskSkillRepository skills,
                       TaskDependencyRepository dependencies, TaskStatusEventRepository events,
                       WorkLogRepository workLogs, TaskRemainingEstimateRepository remainingEstimates,
                       TaskAccessService accessService, PlanningGuardService guard, ObjectMapper mapper,
                       ObjectProvider<AnalysisRequests> jobDispatcher) {
        this.tasks = tasks;
        this.revisions = revisions;
        this.skills = skills;
        this.dependencies = dependencies;
        this.events = events;
        this.workLogs = workLogs;
        this.remainingEstimates = remainingEstimates;
        this.accessService = accessService;
        this.guard = guard;
        this.mapper = mapper;
        this.jobDispatcher = jobDispatcher;
    }

    // ---------- 创建 ----------

    @Transactional
    public TaskDetail create(UUID userId, UUID companyId, CreateTaskRequest req, String idempotencyKey) {
        var access = accessService.requireProjectAccess(userId, companyId, req.projectId());
        validateRequirement(req.title(), req.priority(), req.dueType(), req.dueDate(), req.fixedAssigneeId(),
                req.preferredMemberId(), access.canManage());
        Integer estimate = req.estimatedMinutes() == null ? null : ceil30(req.estimatedMinutes());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String hash = requestHash(req);
            var existing = tasks.findByIdempotencyKey(companyId, access.memberId(), idempotencyKey);
            if (existing.isPresent()) {
                // 幂等重放：同内容返回既有结果，不同内容拒绝（TRD 9.4）
                TaskRow found = existing.get();
                if (hash.equals(findRequestHash(found))) {
                    return detail(companyId, found.id());
                }
                throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "幂等键已使用且请求内容不同，请更换幂等键");
            }
            return insertNewTask(companyId, access, req, estimate, idempotencyKey, hash);
        }
        return insertNewTask(companyId, access, req, estimate, null, null);
    }

    private String findRequestHash(TaskRow task) {
        return tasks.findRequestHash(task.companyId(), task.id());
    }

    private TaskDetail insertNewTask(UUID companyId, TaskAccessService.ProjectAccess access,
                                     CreateTaskRequest req, Integer estimate,
                                     String idempotencyKey, String requestHash) {
        NodeType nodeType = NodeType.LEAF;
        UUID parentId = null;
        if (req.parentId() != null) {
            TaskRow parent = tasks.lockById(companyId, req.parentId())
                    .orElseThrow(BusinessException::notFound);
            validateNewChild(parent, req.projectId(), companyId);
            parentId = parent.id();
            if (parent.nodeType() == NodeType.LEAF) {
                tasks.promoteToParent(companyId, parent.id());
            }
        }
        var task = new TaskRow(UUID.randomUUID(), companyId, req.projectId(), parentId, nodeType, null,
                TaskStatus.DRAFT, ScheduleStatus.UNSCHEDULED, null, null, null,
                req.title().trim(), req.description(), req.taskType(),
                req.priority() == null ? TaskPriority.P2 : req.priority(),
                req.dueType() == null ? DueType.SOFT : req.dueType(), req.dueDate(), req.earliestStartDate(),
                null, req.fixedAssigneeId(), req.preferredMemberId(), estimate, null, null, 0,
                estimate == null ? null : "MANUAL_FINAL", req.acceptanceCriteria(),
                null, null, null, access.memberId(), null, null, 0);
        tasks.insert(task, idempotencyKey, requestHash);
        skills.replace(companyId, task.id(), toSkillRows(req.skills()));
        writeRevision(companyId, task.id(), RevisionSource.ORIGINAL, access.memberId(), snapshotOf(task, req.skills()));
        events.insert(companyId, task.id(), null, TaskStatus.DRAFT, access.memberId(), null);
        return detail(companyId, task.id());
    }

    private void validateNewChild(TaskRow parent, UUID projectId, UUID companyId) {
        if (!parent.projectId().equals(projectId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "父子任务必须属于同一项目");
        }
        if (parent.status() == TaskStatus.CANCELED || parent.status() == TaskStatus.DONE) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "终态任务不能添加子任务");
        }
        if (parent.status() != TaskStatus.DRAFT && parent.status() != TaskStatus.SUBMITTED
                && parent.status() != TaskStatus.NEEDS_INFO) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "任务已开始执行，拆解变更须通过变更流程保留版本与已发生工时");
        }
        int newDepth = tasks.depthOf(companyId, parent.id()) + 1;
        if (newDepth > MAX_DEPTH) {
            throw new BusinessException(HttpStatus.CONFLICT, "DECOMPOSITION_LIMIT", "拆解深度不能超过 " + MAX_DEPTH + " 层");
        }
        UUID root = tasks.rootOf(companyId, parent.id());
        int leaves = tasks.countLeavesUnder(companyId, root);
        int after = parent.nodeType() == NodeType.LEAF ? leaves : leaves + 1;
        if (after > MAX_LEAVES_PER_TREE) {
            throw new BusinessException(HttpStatus.CONFLICT, "DECOMPOSITION_LIMIT",
                    "单棵拆解树叶子任务不能超过 " + MAX_LEAVES_PER_TREE + " 个");
        }
    }

    // ---------- 修改需求 ----------

    @Transactional
    public TaskDetail update(UUID userId, UUID companyId, UUID taskId, UpdateTaskRequest req, long expectedVersion) {
        accessService.requireMembership(userId, companyId);
        guard.lock(companyId);
        TaskRow task = lockAndCheckVersion(companyId, taskId, expectedVersion);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        if (!access.canManage() && !task.createdBy().equals(access.memberId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只有创建者或项目管理人员可以修改需求");
        }
        if (task.status() != TaskStatus.DRAFT && task.status() != TaskStatus.NEEDS_INFO
                && task.status() != TaskStatus.SUBMITTED && task.status() != TaskStatus.READY) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "任务已开始执行或已终结，需求变更须走重开/变更流程");
        }
        validateRequirement(req.title(), req.priority(), req.dueType(), req.dueDate(), req.fixedAssigneeId(),
                req.preferredMemberId(), access.canManage());
        Integer estimate = req.estimatedMinutes() == null ? null : ceil30(req.estimatedMinutes());

        tasks.updateRequirement(companyId, taskId, req.title().trim(), req.description(), req.taskType(),
                req.priority() == null ? task.priority() : req.priority(),
                req.dueType() == null ? task.dueType() : req.dueType(), req.dueDate(), req.earliestStartDate(),
                req.fixedAssigneeId(), req.preferredMemberId(), estimate,
                estimate == null ? task.estimateSource() : "MANUAL_FINAL", req.acceptanceCriteria());
        if (req.skills() != null) {
            skills.replace(companyId, taskId, toSkillRows(req.skills()));
        }
        writeRevision(companyId, taskId, RevisionSource.MANUAL_EDIT, access.memberId(),
                snapshotOf(tasks.find(companyId, taskId).orElseThrow(),
                        req.skills() == null ? toSkillDtos(skills.listByTask(companyId, taskId)) : req.skills()));
        if (task.status() != TaskStatus.DRAFT) {
            tasks.invalidateSchedules(companyId, taskId);
            guard.lockAndBump(companyId);
        }
        return detail(companyId, taskId);
    }

    // ---------- 提交 ----------

    @Transactional
    public TaskDetail submit(UUID userId, UUID companyId, UUID taskId, long expectedVersion) {
        accessService.requireMembership(userId, companyId);
        guard.lockAndBump(companyId);
        TaskRow task = lockAndCheckVersion(companyId, taskId, expectedVersion);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        if (!access.canManage() && !task.createdBy().equals(access.memberId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只有创建者或项目管理人员可以提交任务");
        }
        if (task.nodeType() == NodeType.PARENT) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARENT_TASK_NOT_EXECUTABLE", "父任务由子任务聚合推进，不能单独提交");
        }
        if (task.status() != TaskStatus.DRAFT && task.status() != TaskStatus.NEEDS_INFO) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "当前状态 " + task.status() + " 不能提交");
        }
        if (task.description() == null || task.description().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DESCRIPTION_REQUIRED", "提交前必须填写需求描述");
        }
        long taskNo = task.taskNo() == null ? tasks.nextTaskNo(companyId) : task.taskNo();
        tasks.markSubmitted(companyId, taskId, taskNo);
        events.insert(companyId, taskId, task.status(), TaskStatus.SUBMITTED, access.memberId(), null);
        recomputeAncestors(companyId, task, access.memberId());

        // D 的可靠作业未合并时不返回假 jobId：仅登记 SUBMITTED，分析由后续契约集成触发。
        AnalysisRequests dispatcher = jobDispatcher.getIfAvailable();
        if (dispatcher != null) {
            dispatcher.submit(new AnalysisRequests.Request(userId, companyId, task.projectId(),
                    access.memberId(), taskId, task.version() + 1,
                    task.title() + "\n" + task.description()));
        }
        return detail(companyId, taskId);
    }

    // ---------- 删除草稿 ----------

    @Transactional
    public void deleteDraft(UUID userId, UUID companyId, UUID taskId) {
        TaskRow task = tasks.lockById(companyId, taskId).orElseThrow(BusinessException::notFound);
        var access = accessService.requireProjectAccess(userId, companyId, task.projectId());
        if (!access.isCompanyAdmin() && !task.createdBy().equals(access.memberId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "只能删除本人未提交的草稿");
        }
        if (task.status() != TaskStatus.DRAFT) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "只有草稿可以删除，已提交任务请取消");
        }
        if (task.nodeType() == NodeType.PARENT) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION", "请先删除全部子任务");
        }
        dependencies.deleteForTask(companyId, taskId);
        skills.deleteForTask(companyId, taskId);
        revisions.deleteForTask(companyId, taskId);
        events.deleteForTask(companyId, taskId);
        tasks.deleteDraft(companyId, taskId);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public TaskDetail detail(UUID userId, UUID companyId, UUID taskId) {
        TaskRow task = tasks.find(companyId, taskId).orElseThrow(BusinessException::notFound);
        accessService.requireProjectAccess(userId, companyId, task.projectId());
        return detail(companyId, taskId);
    }

    @Transactional(readOnly = true)
    public Paged<TaskSummary> list(UUID userId, UUID companyId, UUID projectId, TaskStatus status,
                                   UUID assigneeId, UUID parentId, NodeType nodeType,
                                   String cursor, int limit) {
        var membership = accessService.requireMembership(userId, companyId);
        if (projectId != null) {
            accessService.requireProjectAccess(userId, companyId, projectId);
        }
        int pageSize = limit <= 0 ? 20 : Math.min(limit, MAX_PAGE_SIZE);
        Instant cursorUpdatedAt = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            var decoded = decodeCursor(cursor);
            cursorUpdatedAt = decoded[0] instanceof Instant i ? i : null;
            cursorId = decoded[1] instanceof UUID u ? u : null;
        }
        List<TaskRow> rows = tasks.listPage(companyId, membership.memberId(), projectId, status, assigneeId,
                parentId, nodeType, cursorUpdatedAt, cursorId, pageSize + 1);
        String nextCursor = null;
        if (rows.size() > pageSize) {
            TaskRow last = rows.get(pageSize - 1);
            nextCursor = encodeCursor(last.updatedAt(), last.id());
            rows = rows.subList(0, pageSize);
        }
        return new Paged<>(rows.stream().map(this::toSummary).toList(), nextCursor);
    }

    // ---------- 内部装配 ----------

    TaskDetail detail(UUID companyId, UUID taskId) {
        TaskRow task = tasks.find(companyId, taskId).orElseThrow(BusinessException::notFound);
        var skillViews = skills.listByTask(companyId, taskId).stream()
                .map(s -> new TaskDetail.SkillRequirementView(s.skillCode(), s.minLevel(), s.required())).toList();
        var children = tasks.listChildren(companyId, taskId).stream().map(this::toSummary).toList();
        var predecessors = dependencies.listPredecessors(companyId, taskId).stream()
                .map(t -> new TaskDetail.DependencyView(t.id(), t.taskNo(), t.title(), t.status())).toList();
        var successors = dependencies.listSuccessors(companyId, taskId).stream()
                .map(t -> new TaskDetail.DependencyView(t.id(), t.taskNo(), t.title(), t.status())).toList();
        var revisionViews = revisions.listByTask(companyId, taskId).stream()
                .map(r -> new TaskDetail.RevisionView(r.revision(), r.source(), r.actorId(), r.createdAt(),
                        readJson(r.contentJson()))).toList();
        var eventViews = events.listByTask(companyId, taskId).stream()
                .map(e -> new TaskDetail.StatusEventView(e.fromStatus(), e.toStatus(), e.actorId(), e.reason(),
                        e.createdAt())).toList();
        var workLogViews = workLogs.listByTask(companyId, taskId).stream()
                .map(w -> new TaskDetail.WorkLogView(w.id(), w.memberId(), w.workDate(), w.minutes(), w.note(),
                        w.createdBy(), w.createdAt())).toList();
        var remainingViews = remainingEstimates.listByTask(companyId, taskId).stream()
                .map(r -> new TaskDetail.RemainingEstimateView(r.version(), r.effectiveAfterDate(),
                        r.remainingMinutes(), r.confirmedBy(), r.createdAt())).toList();
        TaskDetail.ProgressView progress = null;
        if (task.nodeType() == NodeType.PARENT) {
            progress = aggregateProgress(companyId, taskId);
        }
        return new TaskDetail(task.id(), task.taskNo(), task.projectId(), task.parentId(), task.nodeType(),
                task.title(), task.description(), task.taskType(), task.priority(), task.status(),
                task.scheduleStatus(), task.blockedFromStatus(), task.blockedReason(), task.cancelReason(),
                task.assigneeId(), task.fixedAssigneeId(), task.preferredMemberId(),
                task.estimatedMinutes(), task.baselineMinutes(), task.remainingMinutes(), task.actualMinutes(),
                task.estimateSource(), task.acceptanceCriteria(), task.dueDate(), task.dueType(),
                task.earliestStartDate(), task.submittedAt(), task.completedAt(), task.canceledAt(),
                task.createdBy(), task.createdAt(), task.updatedAt(), task.version(),
                skillViews, children, predecessors, successors, progress, revisionViews, eventViews,
                workLogViews, remainingViews);
    }

    private TaskDetail.ProgressView aggregateProgress(UUID companyId, UUID taskId) {
        int leafCount = 0;
        int done = 0;
        int planned = 0;
        int actual = 0;
        int remaining = 0;
        for (TaskRow node : tasks.listSubtree(companyId, taskId)) {
            if (node.nodeType() != NodeType.LEAF) continue;
            leafCount++;
            if (node.status() == TaskStatus.DONE) done++;
            planned += node.estimatedMinutes() == null ? 0 : node.estimatedMinutes();
            actual += node.actualMinutes();
            remaining += node.remainingMinutes() == null ? 0 : node.remainingMinutes();
        }
        return new TaskDetail.ProgressView(leafCount, done, planned, actual, remaining);
    }

    TaskSummary toSummary(TaskRow t) {
        return new TaskSummary(t.id(), t.taskNo(), t.projectId(), t.parentId(), t.nodeType(), t.title(),
                t.taskType(), t.priority(), t.status(), t.scheduleStatus(), t.effectiveAssignee(),
                t.estimatedMinutes(), t.actualMinutes(), t.remainingMinutes(), t.dueDate(), t.dueType(),
                t.updatedAt(), t.version());
    }

    // ---------- 父任务状态聚合 ----------

    /** 子任务状态变化后沿父链向上聚合；CANCELED 父任务不复活。 */
    void recomputeAncestors(UUID companyId, TaskRow child, UUID actorId) {
        UUID parentId = child.parentId();
        while (parentId != null) {
            TaskRow parent = tasks.lockById(companyId, parentId).orElse(null);
            if (parent == null) return;
            if (parent.status() == TaskStatus.CANCELED) return;
            List<TaskRow> children = tasks.listChildren(companyId, parent.id());
            TaskStatus aggregate = aggregateStatus(children);
            if (aggregate != parent.status()) {
                events.insert(companyId, parent.id(), parent.status(), aggregate, actorId,
                        "子任务状态聚合");
                tasks.updateAggregateStatus(companyId, parent.id(), aggregate,
                        aggregate == TaskStatus.DONE ? Instant.now() : null);
            }
            parentId = parent.parentId();
        }
    }

    static TaskStatus aggregateStatus(List<TaskRow> children) {
        if (children.isEmpty()) return TaskStatus.DRAFT;
        boolean allDone = children.stream().allMatch(c -> c.status() == TaskStatus.DONE);
        if (allDone) return TaskStatus.DONE;
        boolean anyActive = children.stream().anyMatch(c ->
                c.status() == TaskStatus.IN_PROGRESS || c.status() == TaskStatus.IN_REVIEW);
        if (anyActive) return TaskStatus.IN_PROGRESS;
        boolean anyBlocked = children.stream().anyMatch(c -> c.status() == TaskStatus.BLOCKED);
        if (anyBlocked) return TaskStatus.BLOCKED;
        boolean anyReady = children.stream().anyMatch(c -> c.status() == TaskStatus.READY);
        if (anyReady) return TaskStatus.READY;
        boolean anySubmitted = children.stream().anyMatch(c ->
                c.status() == TaskStatus.SUBMITTED || c.status() == TaskStatus.NEEDS_INFO);
        if (anySubmitted) return TaskStatus.SUBMITTED;
        boolean anyDraft = children.stream().anyMatch(c -> c.status() == TaskStatus.DRAFT);
        if (anyDraft) return TaskStatus.DRAFT;
        // 其余情形（如全部 CANCELED）保持非完成聚合，负责人显式取消父任务。
        return TaskStatus.READY;
    }

    // ---------- 工具 ----------

    TaskRow lockAndCheckVersion(UUID companyId, UUID taskId, long expectedVersion) {
        TaskRow task = tasks.lockById(companyId, taskId).orElseThrow(BusinessException::notFound);
        if (task.version() != expectedVersion) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "任务版本已变化（当前 " + task.version() + "），请刷新后重试");
        }
        return task;
    }

    private void validateRequirement(String title, TaskPriority priority, DueType dueType, java.time.LocalDate dueDate,
                                     UUID fixedAssigneeId, UUID preferredMemberId, boolean canManage) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "标题不能为空");
        }
        if (priority == TaskPriority.P0 && !canManage) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PRIORITY_NOT_ALLOWED", "P0 紧急等级需要管理员或项目负责人确认");
        }
        if (dueType == DueType.HARD && dueDate == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "硬截止必须提供截止日期");
        }
        if (fixedAssigneeId != null && preferredMemberId != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "硬指定负责人与偏好负责人只能二选一");
        }
    }

    /** 计划工时向上取整到 30 分钟；人工最终值直接取整，不再二次加缓冲。 */
    static int ceil30(int minutes) {
        if (minutes <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "工时必须是正整数分钟");
        }
        return (minutes + 29) / 30 * 30;
    }

    private void writeRevision(UUID companyId, UUID taskId, RevisionSource source, UUID actorId, ObjectNode content) {
        long revision = revisions.nextRevision(companyId, taskId);
        revisions.insert(companyId, taskId, revision, content.toString(), source, actorId);
    }

    private ObjectNode snapshotOf(TaskRow task, List<SkillRequirementDto> skillList) {
        ObjectNode node = mapper.createObjectNode();
        node.put("title", task.title());
        node.put("description", task.description());
        node.put("taskType", task.taskType());
        node.put("priority", task.priority().name());
        node.put("dueType", task.dueType().name());
        if (task.dueDate() != null) node.put("dueDate", task.dueDate().toString());
        if (task.earliestStartDate() != null) node.put("earliestStartDate", task.earliestStartDate().toString());
        if (task.estimatedMinutes() != null) node.put("estimatedMinutes", task.estimatedMinutes());
        if (task.estimateSource() != null) node.put("estimateSource", task.estimateSource());
        if (task.fixedAssigneeId() != null) node.put("fixedAssigneeId", task.fixedAssigneeId().toString());
        if (task.preferredMemberId() != null) node.put("preferredMemberId", task.preferredMemberId().toString());
        node.put("acceptanceCriteria", task.acceptanceCriteria());
        var skillsNode = node.putArray("skills");
        for (SkillRequirementDto skill : skillList == null ? List.<SkillRequirementDto>of() : skillList) {
            var s = skillsNode.addObject();
            s.put("skillCode", skill.skillCode());
            s.put("minLevel", skill.minLevel());
            s.put("required", skill.required());
        }
        return node;
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }

    private List<TaskSkillRepository.SkillRequirementRow> toSkillRows(List<SkillRequirementDto> dtos) {
        if (dtos == null) return List.of();
        var seen = new java.util.HashSet<String>();
        var rows = new ArrayList<TaskSkillRepository.SkillRequirementRow>();
        for (SkillRequirementDto dto : dtos) {
            String code = dto.skillCode().trim().toUpperCase();
            if (!seen.add(code)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "技能代码重复：" + code);
            }
            rows.add(new TaskSkillRepository.SkillRequirementRow(code, dto.minLevel(), dto.required()));
        }
        return rows;
    }

    private List<SkillRequirementDto> toSkillDtos(List<TaskSkillRepository.SkillRequirementRow> rows) {
        return rows.stream().map(r -> new SkillRequirementDto(r.skillCode(), r.minLevel(), r.required())).toList();
    }

    private String requestHash(CreateTaskRequest req) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = String.join("|", req.projectId().toString(), req.title(),
                    req.description() == null ? "" : req.description());
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encodeCursor(Instant updatedAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((updatedAt.toEpochMilli() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static Object[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            return new Object[]{Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1])};
        } catch (Exception e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "游标格式不正确");
        }
    }
}
