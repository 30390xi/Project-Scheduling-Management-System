package com.company.scheduler.agent.ai;
import com.company.scheduler.shared.api.ApiResponse;
import java.util.UUID;
import java.security.Principal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class AnalysisController {
    private final AnalysisConfirmationService service;
    public AnalysisController(AnalysisConfirmationService service) {this.service=service;}
    public record ConfirmationRequest(boolean confirmAllAssumptions) {}
    @PostMapping("/tasks/{taskId}/ai-analysis-runs")
    public ApiResponse<com.company.scheduler.shared.contract.AnalysisRequests.Submission> analyze(
            Principal a, @RequestHeader("X-Company-Id") UUID company, @PathVariable UUID taskId) {
        return ApiResponse.of(service.analyze(UUID.fromString(a.getName()), company, taskId));
    }
    @GetMapping("/tasks/{taskId}/ai-analysis-runs")
    public ApiResponse<java.util.List<AiAnalysisRun>> list(Principal a,@RequestHeader("X-Company-Id") UUID company,@PathVariable UUID taskId) {
        return ApiResponse.of(service.list(UUID.fromString(a.getName()),company,taskId));
    }
    @GetMapping("/ai-analysis-runs/{id}")
    public ApiResponse<AiAnalysisRun> get(Principal a,@RequestHeader("X-Company-Id") UUID company,@PathVariable UUID id) {
        return ApiResponse.of(service.get(UUID.fromString(a.getName()),company,id));
    }
    @PostMapping("/ai-analysis-runs/{id}/confirm")
    public ApiResponse<AnalysisConfirmationService.Confirmation> confirm(Principal a,@RequestHeader("X-Company-Id") UUID company,
            @PathVariable UUID id,@RequestBody ConfirmationRequest body) {
        return ApiResponse.of(service.confirm(UUID.fromString(a.getName()),company,id,body.confirmAllAssumptions()));
    }
}
