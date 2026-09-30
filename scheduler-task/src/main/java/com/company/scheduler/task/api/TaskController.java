package com.company.scheduler.task.api;

import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.task.api.dto.CreateTaskRequest;
import com.company.scheduler.task.api.dto.Paged;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.TaskSummary;
import com.company.scheduler.task.api.dto.UpdateTaskRequest;
import com.company.scheduler.task.domain.NodeType;
import com.company.scheduler.task.domain.TaskStatus;
import com.company.scheduler.task.service.TaskService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) { this.tasks = tasks; }

    @PostMapping
    public ApiResponse<TaskDetail> create(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @Valid @RequestBody CreateTaskRequest req) {
        return ApiResponse.of(tasks.create(userId(auth), companyId, req, idempotencyKey));
    }

    @GetMapping
    public ApiResponse<Paged<TaskSummary>> list(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                                @RequestParam(required = false) UUID projectId,
                                                @RequestParam(required = false) TaskStatus status,
                                                @RequestParam(required = false) UUID assigneeId,
                                                @RequestParam(required = false) UUID parentId,
                                                @RequestParam(required = false) NodeType nodeType,
                                                @RequestParam(required = false) String cursor,
                                                @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(tasks.list(userId(auth), companyId, projectId, status, assigneeId, parentId,
                nodeType, cursor, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetail> detail(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                          @PathVariable UUID id) {
        return ApiResponse.of(tasks.detail(userId(auth), companyId, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TaskDetail> update(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                          @PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                          @Valid @RequestBody UpdateTaskRequest req) {
        return ApiResponse.of(tasks.update(userId(auth), companyId, id, req, parseVersion(ifMatch)));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<TaskDetail> submit(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                          @PathVariable UUID id, @RequestHeader("If-Match") String ifMatch) {
        return ApiResponse.of(tasks.submit(userId(auth), companyId, id, parseVersion(ifMatch)));
    }

    /** 仅未提交草稿可删除；已提交任务走取消流程。 */
    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                      @PathVariable UUID id) {
        tasks.deleteDraft(userId(auth), companyId, id);
        return ApiResponse.of("deleted");
    }

    static long parseVersion(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new com.company.scheduler.shared.error.BusinessException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "If-Match 版本格式不正确");
        }
    }

    static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
