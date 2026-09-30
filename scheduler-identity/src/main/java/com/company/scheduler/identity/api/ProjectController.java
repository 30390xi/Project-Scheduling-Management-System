package com.company.scheduler.identity.api;

import com.company.scheduler.identity.security.AuthenticatedActor;
import com.company.scheduler.identity.service.ProjectService;
import com.company.scheduler.identity.support.IfMatch;
import com.company.scheduler.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projects;

    public ProjectController(ProjectService projects) { this.projects = projects; }

    @PostMapping
    public ApiResponse<ProjectService.ProjectView> create(Authentication authentication,
                                                          @RequestHeader("X-Company-Id") UUID companyId,
                                                          @Valid @RequestBody CreateProjectRequest body) {
        return ApiResponse.of(projects.create(AuthenticatedActor.of(authentication, companyId), body.name()));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectService.ProjectView> get(Authentication authentication,
                                                       @RequestHeader("X-Company-Id") UUID companyId,
                                                       @PathVariable UUID projectId) {
        return ApiResponse.of(projects.get(AuthenticatedActor.of(authentication, companyId), projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectService.ProjectView> rename(Authentication authentication,
                                                          @RequestHeader("X-Company-Id") UUID companyId,
                                                          @PathVariable UUID projectId,
                                                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                          @Valid @RequestBody RenameProjectRequest body) {
        return ApiResponse.of(projects.rename(AuthenticatedActor.of(authentication, companyId),
                projectId, body.name(), IfMatch.require(ifMatch)));
    }

    @PutMapping("/{projectId}/policy")
    public ApiResponse<ProjectService.ProjectView> changePolicy(Authentication authentication,
                                                                @RequestHeader("X-Company-Id") UUID companyId,
                                                                @PathVariable UUID projectId,
                                                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                @Valid @RequestBody ProjectPolicyRequest body) {
        return ApiResponse.of(projects.changePolicy(AuthenticatedActor.of(authentication, companyId),
                projectId, body.autoAssignEnabled(), IfMatch.require(ifMatch), body.reason()));
    }

    @GetMapping("/{projectId}/members")
    public ApiResponse<List<ProjectService.ProjectMemberView>> listMembers(Authentication authentication,
                                                                           @RequestHeader("X-Company-Id") UUID companyId,
                                                                           @PathVariable UUID projectId) {
        return ApiResponse.of(projects.listMembers(AuthenticatedActor.of(authentication, companyId), projectId));
    }

    @PostMapping("/{projectId}/members")
    public ApiResponse<ProjectService.ProjectMemberView> addMember(Authentication authentication,
                                                                   @RequestHeader("X-Company-Id") UUID companyId,
                                                                   @PathVariable UUID projectId,
                                                                   @Valid @RequestBody AddProjectMemberRequest body) {
        return ApiResponse.of(projects.addMember(AuthenticatedActor.of(authentication, companyId),
                projectId, body.memberId(), body.role(), body.resourcePermission()));
    }

    @PutMapping("/{projectId}/members/{memberId}")
    public ApiResponse<ProjectService.ProjectMemberView> updateMember(Authentication authentication,
                                                                      @RequestHeader("X-Company-Id") UUID companyId,
                                                                      @PathVariable UUID projectId,
                                                                      @PathVariable UUID memberId,
                                                                      @Valid @RequestBody UpdateProjectMemberRequest body) {
        return ApiResponse.of(projects.updateMember(AuthenticatedActor.of(authentication, companyId),
                projectId, memberId, body.role(), body.resourcePermission(), body.reason()));
    }

    @DeleteMapping("/{projectId}/members/{memberId}")
    public ApiResponse<String> removeMember(Authentication authentication,
                                            @RequestHeader("X-Company-Id") UUID companyId,
                                            @PathVariable UUID projectId,
                                            @PathVariable UUID memberId,
                                            @RequestParam(required = false) String reason) {
        projects.removeMember(AuthenticatedActor.of(authentication, companyId), projectId, memberId, reason);
        return ApiResponse.of("removed");
    }

    public record CreateProjectRequest(@NotBlank @Size(max = 200) String name) {}
    public record RenameProjectRequest(@NotBlank @Size(max = 200) String name) {}
    public record ProjectPolicyRequest(@NotNull Boolean autoAssignEnabled, @Size(max = 500) String reason) {}
    public record AddProjectMemberRequest(@NotNull UUID memberId,
                                          @NotBlank @Pattern(regexp = "OWNER|MEMBER") String role,
                                          boolean resourcePermission) {}
    public record UpdateProjectMemberRequest(@NotBlank @Pattern(regexp = "OWNER|MEMBER") String role,
                                             boolean resourcePermission,
                                             @Size(max = 500) String reason) {}
}
