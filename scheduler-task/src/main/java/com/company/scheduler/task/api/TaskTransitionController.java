package com.company.scheduler.task.api;

import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.task.api.dto.RemainingEstimateRequest;
import com.company.scheduler.task.api.dto.TaskDetail;
import com.company.scheduler.task.api.dto.TransitionRequest;
import com.company.scheduler.task.api.dto.WorkLogRequest;
import com.company.scheduler.task.service.RemainingEstimateService;
import com.company.scheduler.task.service.TaskTransitionService;
import com.company.scheduler.task.service.WorkLogService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskTransitionController {
    private final TaskTransitionService transitions;
    private final WorkLogService workLogs;
    private final RemainingEstimateService remainingEstimates;

    public TaskTransitionController(TaskTransitionService transitions, WorkLogService workLogs,
                                    RemainingEstimateService remainingEstimates) {
        this.transitions = transitions;
        this.workLogs = workLogs;
        this.remainingEstimates = remainingEstimates;
    }

    /** 状态操作：MARK_READY / START / BLOCK / UNBLOCK / SUBMIT_REVIEW / RETURN / ACCEPT / CANCEL / REOPEN。 */
    @PostMapping("/{id}/transitions")
    public ApiResponse<TaskDetail> transition(Authentication auth, @RequestHeader("X-Company-Id") UUID companyId,
                                              @PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                              @Valid @RequestBody TransitionRequest req) {
        return ApiResponse.of(transitions.transition(TaskController.userId(auth), companyId, id, req,
                TaskController.parseVersion(ifMatch)));
    }

    @PostMapping("/{id}/work-logs")
    public ApiResponse<TaskDetail.WorkLogView> addWorkLog(
            Authentication auth, @RequestHeader("X-Company-Id") UUID companyId, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody WorkLogRequest req) {
        return ApiResponse.of(workLogs.add(TaskController.userId(auth), companyId, id, req, idempotencyKey));
    }

    @PutMapping("/{id}/remaining-estimate")
    public ApiResponse<TaskDetail> confirmRemaining(Authentication auth,
                                                    @RequestHeader("X-Company-Id") UUID companyId,
                                                    @PathVariable UUID id,
                                                    @RequestHeader("If-Match") String ifMatch,
                                                    @Valid @RequestBody RemainingEstimateRequest req) {
        return ApiResponse.of(remainingEstimates.confirm(TaskController.userId(auth), companyId, id, req,
                TaskController.parseVersion(ifMatch)));
    }
}
