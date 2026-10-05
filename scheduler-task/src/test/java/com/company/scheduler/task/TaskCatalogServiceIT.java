package com.company.scheduler.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.scheduler.shared.contract.TaskCatalog;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.task.api.dto.CreateTaskRequest;
import com.company.scheduler.task.api.dto.DependencyUpdateRequest;
import com.company.scheduler.task.api.dto.RemainingEstimateRequest;
import com.company.scheduler.task.api.dto.SkillRequirementDto;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.TransitionRequest;
import com.company.scheduler.task.domain.DueType;
import com.company.scheduler.task.domain.TaskPriority;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.domain.TransitionAction;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** TaskCatalog 读取契约：真实已确认任务快照、硬截止继承、技能与依赖、权限过滤。 */
class TaskCatalogServiceIT extends TaskModuleITSupport {
    private static DataSource ds;

    @BeforeAll
    static void setup() {
        ds = newDatabase();
    }

    private TaskDetail createLeaf(Fixture f, String title, Integer estimate, List<SkillRequirementDto> skills) {
        return f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(), null, title,
                "需求描述", "BACKEND", TaskPriority.P2, null, null, null, estimate, null, null, null, skills), null);
    }

    private TaskDetail toReady(Fixture f, TaskDetail task) {
        var submitted = f.tasks().submit(f.adminUserId(), f.companyId(), task.id(), task.version());
        return f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.MARK_READY, null, null, null), submitted.version());
    }

    @Test
    void snapshotContainsEstimateSkillsPredecessorsAndInheritedDeadline() {
        var f = newFixture(ds);
        // 父任务带硬截止；叶子无自身截止 → 继承父链最早硬截止
        var parent = f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(), null,
                "父需求", "描述", null, null, LocalDate.of(2026, 11, 30), DueType.HARD, null, null, null, null,
                null, List.of()), null);
        var child = f.tasks().create(f.adminUserId(), f.companyId(), new CreateTaskRequest(f.projectId(),
                parent.id(), "叶子", "描述", null, null, null, null, null, 300, null, null, null,
                List.of(new SkillRequirementDto("java", 3, true),
                        new SkillRequirementDto("sql", 2, false))), null);
        toReady(f, child);

        var predecessor = toReady(f, createLeaf(f, "前置", 60, List.of()));
        f.dependencies().replacePredecessors(f.adminUserId(), f.companyId(), child.id(),
                new DependencyUpdateRequest(List.of(predecessor.id())),
                f.tasks().detail(f.adminUserId(), f.companyId(), child.id()).version());

        List<TaskCatalog.TaskSnapshot> snapshots = f.catalog().loadSchedulable(f.adminUserId(), f.companyId(),
                List.of(child.id()));
        assertThat(snapshots).hasSize(1);
        var snapshot = snapshots.get(0);
        assertThat(snapshot.taskId()).isEqualTo(child.id());
        assertThat(snapshot.projectId()).isEqualTo(f.projectId());
        assertThat(snapshot.estimatedMinutes()).isEqualTo(300);
        assertThat(snapshot.hardDeadline()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(snapshot.skills()).containsExactly(new TaskCatalog.SkillRequirement("JAVA", 3));
        assertThat(snapshot.predecessors()).containsExactly(predecessor.id());
    }

    @Test
    void inProgressTaskUsesConfirmedRemainingMinutes() {
        var f = newFixture(ds);
        var task = toReady(f, createLeaf(f, "进行中任务", 300, List.of()));
        f.jdbc().update("UPDATE task SET schedule_status = 'PLANNED' WHERE company_id = ? AND id = ?",
                f.companyId(), task.id());
        f.allocatedToday().add(task.id());
        var started = f.transitions().transition(f.adminUserId(), f.companyId(), task.id(),
                new TransitionRequest(TransitionAction.START, null, null, null), task.version());
        f.remainingEstimates().confirm(f.adminUserId(), f.companyId(), task.id(),
                new RemainingEstimateRequest(90, LocalDate.now()), started.version());

        var snapshots = f.catalog().loadSchedulable(f.adminUserId(), f.companyId(), List.of(task.id()));
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).estimatedMinutes()).isEqualTo(90);
    }

    @Test
    void onlySchedulableTasksReturned() {
        var f = newFixture(ds);
        var draft = createLeaf(f, "草稿", 60, List.of());
        var ready = toReady(f, createLeaf(f, "就绪", 60, List.of()));
        var done = toReady(f, createLeaf(f, "已完成", 60, List.of()));
        f.jdbc().update("UPDATE task SET status = 'DONE', schedule_status = 'CLOSED' WHERE company_id = ? AND id = ?",
                f.companyId(), done.id());

        var snapshots = f.catalog().loadSchedulable(f.adminUserId(), f.companyId(),
                List.of(draft.id(), ready.id(), done.id()));
        assertThat(snapshots).extracting(TaskCatalog.TaskSnapshot::taskId).containsExactly(ready.id());
    }

    @Test
    void invisibleOrMissingTasksAreRejected() {
        var f = newFixture(ds);
        var task = toReady(f, createLeaf(f, "受限任务", 60, List.of()));
        // 非项目成员不可见
        assertThatThrownBy(() -> f.catalog().loadSchedulable(f.outsiderUserId(), f.companyId(),
                List.of(task.id())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
        // 不存在的任务 id
        assertThatThrownBy(() -> f.catalog().loadSchedulable(f.adminUserId(), f.companyId(),
                List.of(task.id(), UUID.randomUUID())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
        // 空列表快速返回
        assertThat(f.catalog().loadSchedulable(f.adminUserId(), f.companyId(), List.of())).isEmpty();
    }
}
