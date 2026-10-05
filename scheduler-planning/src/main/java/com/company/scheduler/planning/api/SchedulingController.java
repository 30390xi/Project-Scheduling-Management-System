package com.company.scheduler.planning.api;
import com.company.scheduler.planning.engine.SchedulingEngine;
import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.shared.contract.PlanningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class SchedulingController {
    private final SchedulingEngine engine;
    public SchedulingController(SchedulingEngine engine) { this.engine = engine; }
    public record Simulation(@NotEmpty @Size(max=200) List<UUID> rootTaskIds) {}
    public record Commit(@Min(0) long version) {}
    public record Lock(boolean locked, @NotBlank @Size(max=2000) String reason) {}
    @PostMapping("/schedule-simulations")
    public ApiResponse<PlanningService.SimulationJob> simulate(Authentication a, @RequestHeader("X-Company-Id") UUID company,
            @Valid @RequestBody Simulation request) { return ApiResponse.of(engine.requestSimulation(user(a), company, request.rootTaskIds())); }
    @GetMapping("/schedule-simulations/{jobId}")
    public ApiResponse<SchedulingEngine.Plan> job(Authentication a, @RequestHeader("X-Company-Id") UUID company,
            @PathVariable UUID jobId) { return ApiResponse.of(engine.byJob(user(a), company, jobId)); }
    @GetMapping("/schedule-plans/{id}")
    public ApiResponse<SchedulingEngine.Plan> plan(Authentication a, @RequestHeader("X-Company-Id") UUID company,
            @PathVariable UUID id) { return ApiResponse.of(engine.get(user(a), company, id)); }
    @PostMapping("/schedule-plans/{id}/commit")
    public ApiResponse<PlanningService.CommitResult> commit(Authentication a, @RequestHeader("X-Company-Id") UUID company,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Commit request) {
        return ApiResponse.of(engine.commit(user(a), company, id, request.version(), key));
    }
    @GetMapping("/schedules/members/{memberId}")
    public ApiResponse<List<SchedulingEngine.AllocationView>> member(Authentication a, @RequestHeader("X-Company-Id") UUID company,
            @PathVariable UUID memberId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return ApiResponse.of(engine.memberSchedule(user(a), company, memberId, from, to));
    }
    @PutMapping("/tasks/{taskId}/schedule-lock")
    public ApiResponse<Void> lock(Authentication a, @RequestHeader("X-Company-Id") UUID company,
            @PathVariable UUID taskId, @Valid @RequestBody Lock request) {
        engine.setLocked(user(a), company, taskId, request.locked(), request.reason()); return ApiResponse.of(null);
    }
    private UUID user(Authentication a) { return UUID.fromString(a.getName()); }
}
