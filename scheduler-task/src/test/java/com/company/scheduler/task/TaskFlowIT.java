package com.company.scheduler.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.CreateTaskRequest;
import com.company.scheduler.task.api.dto.DependencyUpdateRequest;
import com.company.scheduler.task.api.dto.RemainingEstimateRequest;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.TransitionRequest;
import com.company.scheduler.task.api.dto.UpdateTaskRequest;
import com.company.scheduler.task.api.dto.WorkLogRequest;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.RevisionSource;
import com.company.scheduler.task.domain.ScheduleStatus;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.domain.TransitionAction;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 任务域业务规则在真实 PostgreSQL 上的端到端验证（服务层直连，事务语义由 tx 模板显式控制）。 */
class TaskFlowIT extends TaskModuleITSupport {
    private static DataSource ds;

    @BeforeAll
    static void setup() {
        ds = newDatabase();
    }

    private TaskDetail createTask(Fixture f, UUID userId, String title, Integer estimate) {
        return f.tasks().create(userId, f.companyId(), new CreateTaskRequest(f.projectId(), null, title,
                "需求描述", "BACKEND", TaskPriority.P2, null, null, null, estimate, null, null, null,
                List.of()), null);
    }

    private long guardRevision(Fixture f) {
        Long revision = f.jdbc().queryForObject(
                "SELECT revision FROM planning_guard WHERE company_id = ?", Long.class, f.companyId());
        return revision == null ? -1 : revision;
    }

    private void setPlanned(Fixture f, UUID taskId) {
        f.jdbc().update("UPDATE task SET schedule_status = 'PLANNED' WHERE company_id = ? AND id = ?",
                f.companyId(), taskId);
    }

    private TaskDetail driveToDone(Fixture f, TaskDetail task) {
        var submitted = f.tasks().submit(f.adminUserId(), f.companyId(), task.id(), task.version());
        var ready = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null), submitted.version());
        setPlanned(f, task.id());
        var started = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), ready.version());
        var review = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.SUBMIT_REVIEW, null, null, "交付物已提交"), started.version());
        return f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.ACCEPT, null, null, null), review.version());
    }

    /** AC-01：提交生成唯一编号，原始输入可追溯。 */
    @Test
    void submitAssignsStableNumberAndKeepsRevisions() {
        var f = newFixture(ds);
        var created = f.tasks().create(f.memberUserId(), f.companyId(), new CreateTaskRequest(f.projectId(), null,
                "实现登录接口", null, "BACKEND", TaskPriority.P2, null, null, null, 305, f.memberId(), null,
                "登录返回有效会话", List.of()), null);
        assertThat(created.status()).isEqualTo(TaskStatus.DRAFT);
        assertThat(created.estimatedMinutes()).isEqualTo(330); // 人工最终值向上取整到 30 分钟
        assertThat(created.revisions()).hasSize(1);
        assertThat(created.revisions().get(0).source()).isEqualTo(RevisionSource.ORIGINAL);
        assertThat(created.revisions().get(0).content().path("title").asText()).isEqualTo("实现登录接口");

        // 缺需求描述不能提交
        assertThatThrownBy(() -> f.tasks().submit(f.memberUserId(), f.companyId(), created.id(), created.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("DESCRIPTION_REQUIRED"));

        long guardBefore = guardRevision(f);
        var updated = f.tasks().update(f.memberUserId(), f.companyId(), created.id(),
                new UpdateTaskRequest("实现登录接口", "支持账号密码登录", "BACKEND", TaskPriority.P2, null, null,
                        null, 300, f.memberId(), null, "登录返回有效会话", null),
                created.version());
        assertThat(updated.estimatedMinutes()).isEqualTo(300);
        assertThat(updated.revisions()).hasSize(2);
        assertThat(updated.revisions().get(1).source()).isEqualTo(RevisionSource.MANUAL_EDIT);
        // 草稿阶段不影响排期 guard
        assertThat(guardRevision(f)).isEqualTo(guardBefore);

        var submitted = f.tasks().submit(f.memberUserId(), f.companyId(), created.id(), updated.version());
        assertThat(submitted.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(submitted.taskNo()).isNotNull();
        assertThat(submitted.submittedAt()).isNotNull();
        assertThat(guardRevision(f)).isGreaterThan(guardBefore);

        // 重复提交拒绝
        assertThatThrownBy(() -> f.tasks().submit(f.memberUserId(), f.companyId(), created.id(),
                submitted.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_STATUS_TRANSITION"));

        // 第二个任务获得递增编号
        var second = createTask(f, f.memberUserId(), "第二个任务", 60);
        var secondSubmitted = f.tasks().submit(f.memberUserId(), f.companyId(), second.id(), second.version());
        assertThat(secondSubmitted.taskNo()).isEqualTo(submitted.taskNo() + 1);
    }

    @Test
    void createIsIdempotentByKey() {
        var f = newFixture(ds);
        var req = new CreateTaskRequest(f.projectId(), null, "幂等任务", "描述", null, null, null, null, null,
                null, null, null, null, List.of());
        var first = f.tasks().create(f.memberUserId(), f.companyId(), req, "key-1");
        var replay = f.tasks().create(f.memberUserId(), f.companyId(), req, "key-1");
        assertThat(replay.id()).isEqualTo(first.id());

        var different = new CreateTaskRequest(f.projectId(), null, "幂等任务-不同", "描述", null, null, null, null,
                null, null, null, null, null, List.of());
        assertThatThrownBy(() -> f.tasks().create(f.memberUserId(), f.companyId(), different, "key-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void staleVersionIsRejected() {
        var f = newFixture(ds);
        var task = createTask(f, f.adminUserId(), "版本任务", 60);
        assertThatThrownBy(() -> f.tasks().update(f.adminUserId(), f.companyId(), task.id(),
                new UpdateTaskRequest("版本任务", "描述", null, null, null, null, null, 60, null, null, null, null),
                task.version() + 99))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void p0PriorityRequiresManagerApproval() {
        var f = newFixture(ds);
        var req = new CreateTaskRequest(f.projectId(), null, "紧急任务", "描述", null, TaskPriority.P0, null, null,
                null, null, null, null, null, List.of());
        assertThatThrownBy(() -> f.tasks().create(f.memberUserId(), f.companyId(), req, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("PRIORITY_NOT_ALLOWED"));
        assertThat(f.tasks().create(f.adminUserId(), f.companyId(), req, null).priority())
                .isEqualTo(TaskPriority.P0);
    }

    /** 完整执行闭环：READY → IN_PROGRESS → 工时/剩余 → IN_REVIEW → DONE → REOPEN → CANCEL。 */
    @Test
    void executionLifecycleWithWorkLogAndRemaining() {
        var f = newFixture(ds);
        // 硬指定管理员本人为负责人，工时记录到负责人名下
        var task = f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(), null,
                "执行闭环", "需求描述", "BACKEND", TaskPriority.P2, null, null, null, 300, f.adminMemberId(),
                null, null, List.of()), null);
        var submitted = f.tasks().submit(f.adminUserId(), f.companyId(), task.id(), task.version());

        // 普通成员不能 MARK_READY
        assertThatThrownBy(() -> f.transitions().transition(f.memberUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null), submitted.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("ACCESS_DENIED"));

        var ready = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null), submitted.version());
        assertThat(ready.status()).isEqualTo(TaskStatus.READY);
        assertThat(ready.baselineMinutes()).isEqualTo(300);
        assertThat(ready.remainingMinutes()).isEqualTo(300);

        // 无有效排期不能开工
        assertThatThrownBy(() -> f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), ready.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("SCHEDULE_NOT_PLANNED"));

        setPlanned(f, task.id());
        // 任务无负责人时成员视角不可用；由管理员代操作
        var started = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), ready.version());
        assertThat(started.status()).isEqualTo(TaskStatus.IN_PROGRESS);

        // 工时记录（管理员代填，任务无负责人，由管理员权限代录到自身）
        var log = f.workLogs().add(f.adminUserId(), f.companyId(), task.id(),
                new WorkLogRequest(LocalDate.now(), 120, "第一天"), "wl-1");
        assertThat(log.minutes()).isEqualTo(120);
        var replay = f.workLogs().add(f.adminUserId(), f.companyId(), task.id(),
                new WorkLogRequest(LocalDate.now(), 120, "第一天"), "wl-1");
        assertThat(replay.id()).isEqualTo(log.id());
        var afterLogs = f.tasks().detail(f.adminUserId(), f.companyId(), task.id());
        assertThat(afterLogs.actualMinutes()).isEqualTo(120); // 幂等重放不重复累计
        assertThat(afterLogs.workLogs()).hasSize(1);

        // 剩余工时确认并递增 guard
        long guardBefore = guardRevision(f);
        var confirmed = f.remainingEstimates().confirm(f.adminUserId(), f.companyId(), task.id(),
                new RemainingEstimateRequest(181, LocalDate.now()), afterLogs.version());
        assertThat(confirmed.remainingMinutes()).isEqualTo(210); // 向上取整到 30
        assertThat(confirmed.remainingEstimates()).hasSize(1);
        assertThat(guardRevision(f)).isGreaterThan(guardBefore);

        var review = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.SUBMIT_REVIEW, null, null, null), confirmed.version());
        assertThat(review.status()).isEqualTo(TaskStatus.IN_REVIEW);

        // 验收是管理操作
        assertThatThrownBy(() -> f.transitions().transition(f.memberUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.ACCEPT, null, null, null), review.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("ACCESS_DENIED"));

        var done = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.ACCEPT, null, null, null), review.version());
        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
        assertThat(done.scheduleStatus()).isEqualTo(ScheduleStatus.CLOSED);
        assertThat(done.remainingMinutes()).isEqualTo(0);
        assertThat(done.completedAt()).isNotNull();

        // 受控重开：原因 + 新剩余工时，保留旧完成事件
        var reopened = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.REOPEN, "验收遗漏场景", 90, null), done.version());
        assertThat(reopened.status()).isEqualTo(TaskStatus.READY);
        assertThat(reopened.scheduleStatus()).isEqualTo(ScheduleStatus.UNSCHEDULED);
        assertThat(reopened.remainingMinutes()).isEqualTo(90);
        assertThat(reopened.revisions()).anyMatch(r -> r.source() == RevisionSource.REOPEN);
        assertThat(reopened.events()).anyMatch(e -> e.toStatus() == TaskStatus.DONE);

        var canceled = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.CANCEL, "需求取消", null, null), reopened.version());
        assertThat(canceled.status()).isEqualTo(TaskStatus.CANCELED);
        assertThat(canceled.cancelReason()).isEqualTo("需求取消");
        // 终态不能再操作
        assertThatThrownBy(() -> f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), canceled.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    /** AC-08：环路依赖被拒绝且原依赖不变。 */
    @Test
    void dependencyCycleIsRejectedAndOriginalEdgesKept() {
        var f = newFixture(ds);
        var a = createTask(f, f.adminUserId(), "A", 60);
        var b = createTask(f, f.adminUserId(), "B", 60);
        var c = createTask(f, f.adminUserId(), "C", 60);

        var bUpdated = f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), b.id(),
                new DependencyUpdateRequest(List.of(a.id())), b.version());
        assertThat(bUpdated.predecessors()).extracting("taskId").containsExactly(a.id());
        f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), c.id(),
                new DependencyUpdateRequest(List.of(b.id())), c.version());

        // 尝试 C→A 的反向边形成 A→B→C→A
        var aFresh = f.tasks().detail(f.adminUserId(), f.companyId(), a.id());
        assertThatThrownBy(() -> f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), a.id(),
                new DependencyUpdateRequest(List.of(c.id())), aFresh.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo("DEPENDENCY_CYCLE");
                            assertThat(e.getMessage()).contains(a.id().toString());
                        });
        // 原依赖不变
        assertThat(f.tasks().detail(f.adminUserId(), f.companyId(), a.id()).predecessors()).isEmpty();
        assertThat(f.tasks().detail(f.adminUserId(), f.companyId(), b.id()).predecessors()).hasSize(1);
    }

    /** 并发加反向边：guard 串行化后至多一条成功，最终无环。 */
    @Test
    void concurrentOppositeDependenciesAtMostOneWins() throws Exception {
        var f = newFixture(ds);
        var a = createTask(f, f.adminUserId(), "并发A", 60);
        var b = createTask(f, f.adminUserId(), "并发B", 60);
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            Future<String> addBtoA = pool.submit(() -> {
                ready.countDown();
                go.await();
                return f.tx().execute(status -> {
                    f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), a.id(),
                            new DependencyUpdateRequest(List.of(b.id())), a.version());
                    return "ok";
                });
            });
            Future<String> addAtoB = pool.submit(() -> {
                ready.countDown();
                go.await();
                return f.tx().execute(status -> {
                    f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), b.id(),
                            new DependencyUpdateRequest(List.of(a.id())), b.version());
                    return "ok";
                });
            });
            ready.await();
            go.countDown();

            int ok = 0;
            int cycle = 0;
            for (Future<String> future : List.of(addBtoA, addAtoB)) {
                try {
                    future.get();
                    ok++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(BusinessException.class,
                            be -> assertThat(be.code()).isEqualTo("DEPENDENCY_CYCLE"));
                    cycle++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(cycle).isEqualTo(1);
            Integer edges = f.jdbc().queryForObject(
                    "SELECT count(*) FROM task_dependency WHERE company_id = ?", Integer.class, f.companyId());
            assertThat(edges).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** AC-10：前置未验收，后置即使排期有效也不能开工；前置取消后后置阻塞（TRD 5.2）。 */
    @Test
    void startGateAndCancelBlocksSuccessors() {
        var f = newFixture(ds);
        var a = createTask(f, f.adminUserId(), "前置", 60);
        var b = createTask(f, f.adminUserId(), "后置", 60);
        var bWithDep = f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), b.id(),
                new DependencyUpdateRequest(List.of(a.id())), b.version());

        var bSubmitted = f.tasks().submit(f.adminUserId(), f.companyId(), b.id(), bWithDep.version());
        var bReady = f.transitions().transition(f.adminUserId(), f.companyId(), b.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null), bSubmitted.version());
        setPlanned(f, b.id());

        // 前置未完成：拒绝开工
        assertThatThrownBy(() -> f.transitions().transition(f.adminUserId(), f.companyId(), b.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), bReady.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("PREDECESSOR_NOT_DONE"));

        // 前置验收完成后可以开工
        driveToDone(f, a);
        var bFresh = f.tasks().detail(f.adminUserId(), f.companyId(), b.id());
        var bStarted = f.transitions().transition(f.adminUserId(), f.companyId(), b.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), bFresh.version());
        assertThat(bStarted.status()).isEqualTo(TaskStatus.IN_PROGRESS);

        // 另一个后继在前置取消时进入阻塞
        var c = createTask(f, f.adminUserId(), "后继C", 60);
        var x = createTask(f, f.adminUserId(), "前置X", 60);
        var cWithDep = f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), c.id(),
                new DependencyUpdateRequest(List.of(x.id())), c.version());
        var cReady = f.transitions().transition(f.adminUserId(), f.companyId(), c.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null),
                f.tasks().submit(f.adminUserId(), f.companyId(), c.id(), cWithDep.version()).version());
        var xSubmitted = f.tasks().submit(f.adminUserId(), f.companyId(), x.id(), x.version());
        f.transitions().transition(f.adminUserId(), f.companyId(), x.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null), xSubmitted.version());
        var xFresh = f.tasks().detail(f.adminUserId(), f.companyId(), x.id());
        f.transitions().transition(f.adminUserId(), f.companyId(), x.id(),
                new TransitionRequest(TransitionAction.CANCEL, "需求变化", null, null), xFresh.version());

        var cAfter = f.tasks().detail(f.adminUserId(), f.companyId(), c.id());
        assertThat(cAfter.status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(cAfter.blockedFromStatus()).isEqualTo(TaskStatus.READY);
        // 解除阻塞回到原阶段
        var cUnblocked = f.transitions().transition(f.adminUserId(), f.companyId(), c.id(),
                new TransitionRequest(TransitionAction.UNBLOCK, null, null, null), cAfter.version());
        assertThat(cUnblocked.status()).isEqualTo(TaskStatus.READY);
    }

    /** AC-07 形态：父子结构、父节点不占容量、聚合 32h、深度限制。 */
    @Test
    void parentChildAggregationAndDepthLimit() {
        var f = newFixture(ds);
        var parent = createTask(f, f.adminUserId(), "后台模块", null);
        int[] estimates = {240, 600, 480, 240, 360}; // 32h 示例
        String[] names = {"需求与验收", "接口开发", "页面实现", "联调", "验收测试"};
        var children = new java.util.ArrayList<TaskDetail>();
        for (int i = 0; i < estimates.length; i++) {
            var child = f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(),
                    parent.id(), names[i], "交付物与验收标准", "BACKEND", TaskPriority.P2, null, null, null,
                    estimates[i], null, null, null, List.of()), null);
            children.add(child);
        }
        var parentDetail = f.tasks().detail(f.adminUserId(), f.companyId(), parent.id());
        assertThat(parentDetail.nodeType()).isEqualTo(NodeType.PARENT);
        assertThat(parentDetail.children()).hasSize(5);
        assertThat(parentDetail.progress().leafCount()).isEqualTo(5);
        assertThat(parentDetail.progress().plannedMinutes()).isEqualTo(1920); // 32h，父节点自身不占容量
        assertThat(parentDetail.estimatedMinutes()).isNull();

        // 父任务不能提交/标记就绪
        assertThatThrownBy(() -> f.tasks().submit(f.adminUserId(), f.companyId(), parent.id(),
                parentDetail.version()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("PARENT_TASK_NOT_EXECUTABLE"));

        // 深度限制 ≤3：parent(1) → child(2) → grandchild(3) 允许，第 4 层拒绝
        var child0 = children.get(0);
        var grandchild = f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(),
                child0.id(), "孙任务", "描述", null, null, null, null, null, 60, null, null, null, List.of()), null);
        assertThatThrownBy(() -> f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(
                f.projectId(), grandchild.id(), "曾孙任务", "描述", null, null, null, null, null, 60, null, null,
                null, List.of()), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("DECOMPOSITION_LIMIT"));

        // 全部叶子验收后父任务聚合为 DONE
        for (int i = 1; i < children.size(); i++) {
            driveToDone(f, children.get(i));
        }
        driveToDone(f, grandchild);
        // child0 已变为父节点（其原 240 分钟不再计入叶子汇总），孙任务完成后 child0 聚合 DONE，根节点全部完成
        var finalParent = f.tasks().detail(f.adminUserId(), f.companyId(), parent.id());
        assertThat(finalParent.status()).isEqualTo(TaskStatus.DONE);
        assertThat(finalParent.completedAt()).isNotNull();
        assertThat(finalParent.progress().doneLeafCount()).isEqualTo(5);
        // 叶子计划工时：600+480+240+360+60 = 1740，完成度按计划叶子数计算
        assertThat(finalParent.progress().leafCount()).isEqualTo(5);
        assertThat(finalParent.progress().plannedMinutes()).isEqualTo(1740);
    }

    /** 单棵树叶子数量上限 30。 */
    @Test
    void decompositionLeafLimit() {
        var f = newFixture(ds);
        var parent = createTask(f, f.adminUserId(), "大树", null);
        for (int i = 0; i < 30; i++) {
            f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(), parent.id(),
                    "子任务" + i, "描述", null, null, null, null, null, 30, null, null, null, List.of()), null);
        }
        assertThatThrownBy(() -> f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(
                f.projectId(), parent.id(), "第31个", "描述", null, null, null, null, null, 30, null, null, null,
                List.of()), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("DECOMPOSITION_LIMIT"));
    }

    /** AC-20：跨公司与跨项目访问统一 404，不泄露存在性。 */
    @Test
    void crossCompanyAndCrossProjectAccessIsInvisible() {
        var f = newFixture(ds);
        var task = createTask(f, f.adminUserId(), "隔离任务", 60);
        f.tasks().submit(f.adminUserId(), f.companyId(), task.id(), task.version());

        // 其他公司上下文中访问
        UUID otherCompany = UUID.randomUUID();
        f.jdbc().update("INSERT INTO company(id, name) VALUES (?, '另一公司')", otherCompany);
        UUID otherUser = UUID.randomUUID();
        UUID otherMember = UUID.randomUUID();
        f.jdbc().update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, 'x')", otherUser,
                "other-" + otherUser);
        f.jdbc().update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, 'ADMIN')",
                otherMember, otherCompany, otherUser);
        assertThatThrownBy(() -> f.tasks().detail(otherUser, otherCompany, task.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
        assertThat(f.tasks().list(otherUser, otherCompany, null, null, null, null, null, null, 100).items())
                .isEmpty();

        // 同公司但非项目成员：详情 404、指定项目列表 404
        assertThatThrownBy(() -> f.tasks().detail(f.outsiderUserId(), f.companyId(), task.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> f.tasks().list(f.outsiderUserId(), f.companyId(), f.projectId(), null, null,
                null, null, null, 100))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
        // 成员跨项目列表不含无权项目任务
        assertThat(f.tasks().list(f.outsiderUserId(), f.companyId(), null, null, null, null, null, null, 100)
                .items()).isEmpty();
    }

    @Test
    void draftDeleteRules() {
        var f = newFixture(ds);
        var draft = createTask(f, f.memberUserId(), "草稿", null);
        // 他人（非管理）不能删除
        UUID strangerUser = UUID.randomUUID();
        UUID strangerMember = UUID.randomUUID();
        f.jdbc().update("INSERT INTO app_user(id, username, password_hash) VALUES (?, ?, 'x')", strangerUser,
                "s-" + strangerUser);
        f.jdbc().update("INSERT INTO company_member(id, company_id, user_id, role) VALUES (?, ?, ?, 'MEMBER')",
                strangerMember, f.companyId(), strangerUser);
        f.jdbc().update("""
                INSERT INTO project_member(company_id, project_id, member_id, role) VALUES (?, ?, ?, 'MEMBER')
                """, f.companyId(), f.projectId(), strangerMember);
        assertThatThrownBy(() -> f.tasks().deleteDraft(strangerUser, f.companyId(), draft.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("ACCESS_DENIED"));
        // 创建者删除成功
        f.tasks().deleteDraft(f.memberUserId(), f.companyId(), draft.id());
        assertThatThrownBy(() -> f.tasks().detail(f.memberUserId(), f.companyId(), draft.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
        // 已提交任务不能删除
        var submitted = createTask(f, f.memberUserId(), "已提交", null);
        f.tasks().submit(f.memberUserId(), f.companyId(), submitted.id(), submitted.version());
        assertThatThrownBy(() -> f.tasks().deleteDraft(f.memberUserId(), f.companyId(), submitted.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void listFiltersAndCursorPagination() {
        var f = newFixture(ds);
        var t1 = createTask(f, f.adminUserId(), "分页1", 60);
        var t2 = createTask(f, f.adminUserId(), "分页2", 60);
        var t3 = createTask(f, f.adminUserId(), "分页3", 60);
        f.tasks().submit(f.adminUserId(), f.companyId(), t3.id(), t3.version());

        var page1 = f.tasks().list(f.adminUserId(), f.companyId(), f.projectId(), null, null, null, null, null, 2);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        var page2 = f.tasks().list(f.adminUserId(), f.companyId(), f.projectId(), null, null, null, null,
                page1.nextCursor(), 2);
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.nextCursor()).isNull();
        var ids = new java.util.HashSet<UUID>();
        page1.items().forEach(t -> ids.add(t.id()));
        page2.items().forEach(t -> ids.add(t.id()));
        assertThat(ids).containsExactlyInAnyOrder(t1.id(), t2.id(), t3.id());

        var drafts = f.tasks().list(f.adminUserId(), f.companyId(), f.projectId(), TaskStatus.DRAFT, null, null,
                null, null, 100);
        assertThat(drafts.items()).extracting(t -> t.status()).containsOnly(TaskStatus.DRAFT);
    }
}
