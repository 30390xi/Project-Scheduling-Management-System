package com.company.scheduler.task.api;

import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.task.api.dto.DependencyUpdateRequest;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.service.DependencyService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskDependencyController {
    private final DependencyService dependencies;

    public TaskDependencyController(DependencyService dependencies) { this.dependencies = dependencies; }

    /** 整体替换前置任务集合；guard 写锁内校验同项目、叶子、无环。 */
    @PutMapping("/{id}/dependencies")
    public ApiResponse<TaskDetail> replacePredecessors(Authentication auth,
                                                       @RequestHeader("X-Company-Id") UUID companyId,
                                                       @PathVariable UUID id,
                                                       @RequestHeader("If-Match") String ifMatch,
                                                       @Valid @RequestBody DependencyUpdateRequest req) {
        return ApiResponse.of(dependencies.replacePredecessors(TaskController.userId(auth), companyId, id, req,
                TaskController.parseVersion(ifMatch)));
    }
}
