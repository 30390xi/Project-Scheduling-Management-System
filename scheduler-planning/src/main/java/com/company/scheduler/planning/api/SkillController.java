package com.company.scheduler.planning.api;

import com.company.scheduler.planning.skill.SkillService;
import com.company.scheduler.planning.support.PlanningPermissions;
import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.shared.contract.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skill catalog and member skill certification. Company header is a selector; membership is always verified. */
@RestController
@RequestMapping("/api/v1")
public class SkillController {
    private final CompanyAccess access;
    private final SkillService skills;

    public SkillController(CompanyAccess access, SkillService skills) {
        this.access = access;
        this.skills = skills;
    }

    public record CreateSkillRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {}

    public record UpdateSkillRequest(@Size(max = 200) String name,
                                     @Size(max = 2000) String description,
                                     Boolean active) {}

    public record SkillSetRequest(@NotEmpty @Valid List<SkillAssignmentItem> items) {}

    public record SkillAssignmentItem(@NotNull UUID skillId, @Min(1) @Max(5) int level) {}

    @GetMapping("/skills")
    public ApiResponse<List<SkillService.SkillView>> list(Authentication authentication,
                                                          @RequestHeader("X-Company-Id") UUID companyId) {
        access.requireMember(userId(authentication), companyId);
        return ApiResponse.of(skills.list(companyId));
    }

    @PostMapping("/skills")
    public ApiResponse<SkillService.SkillView> create(Authentication authentication,
                                                      @RequestHeader("X-Company-Id") UUID companyId,
                                                      @Valid @RequestBody CreateSkillRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireAdmin(membership);
        return ApiResponse.of(skills.create(companyId, request.code(), request.name(), request.description()));
    }

    @PutMapping("/skills/{skillId}")
    public ApiResponse<SkillService.SkillView> update(Authentication authentication,
                                                      @RequestHeader("X-Company-Id") UUID companyId,
                                                      @PathVariable UUID skillId,
                                                      @Valid @RequestBody UpdateSkillRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireAdmin(membership);
        return ApiResponse.of(skills.update(companyId, skillId, request.name(), request.description(),
                request.active()));
    }

    @GetMapping("/members/{memberId}/skills")
    public ApiResponse<List<SkillService.MemberSkillView>> memberSkills(Authentication authentication,
                                                                        @RequestHeader("X-Company-Id") UUID companyId,
                                                                        @PathVariable UUID memberId) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireSelfOrManager(membership, memberId);
        return ApiResponse.of(skills.memberSkills(companyId, memberId));
    }

    /** Company admin certification: replaces the member's certified skill set. */
    @PutMapping("/members/{memberId}/skills")
    public ApiResponse<Void> certify(Authentication authentication,
                                     @RequestHeader("X-Company-Id") UUID companyId,
                                     @PathVariable UUID memberId,
                                     @Valid @RequestBody SkillSetRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireAdmin(membership);
        skills.certifySet(companyId, membership.memberId(), memberId, request.items().stream()
                .map(item -> new SkillService.SkillAssignment(item.skillId(), item.level())).toList());
        return ApiResponse.of(null);
    }

    /** Member self report: pending certification, never used for scheduling eligibility. */
    @PutMapping("/my/skills")
    public ApiResponse<Void> selfReport(Authentication authentication,
                                        @RequestHeader("X-Company-Id") UUID companyId,
                                        @Valid @RequestBody SkillSetRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        skills.selfReportSet(companyId, membership.memberId(), request.items().stream()
                .map(item -> new SkillService.SkillAssignment(item.skillId(), item.level())).toList());
        return ApiResponse.of(null);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
