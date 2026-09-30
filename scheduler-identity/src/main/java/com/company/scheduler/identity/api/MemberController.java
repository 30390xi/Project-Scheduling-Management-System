package com.company.scheduler.identity.api;

import com.company.scheduler.identity.security.AuthenticatedActor;
import com.company.scheduler.identity.service.MemberService;
import com.company.scheduler.identity.support.IfMatch;
import com.company.scheduler.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {
    private final MemberService members;

    public MemberController(MemberService members) { this.members = members; }

    @GetMapping
    public ApiResponse<List<MemberService.MemberView>> list(Authentication authentication,
                                                            @RequestHeader("X-Company-Id") UUID companyId) {
        return ApiResponse.of(members.list(AuthenticatedActor.of(authentication, companyId)));
    }

    @PostMapping
    public ApiResponse<MemberService.MemberCreated> create(Authentication authentication,
                                                           @RequestHeader("X-Company-Id") UUID companyId,
                                                           @Valid @RequestBody CreateMemberRequest body) {
        return ApiResponse.of(members.create(AuthenticatedActor.of(authentication, companyId), body.username(), body.role()));
    }

    @PutMapping("/{memberId}/role")
    public ApiResponse<MemberService.MemberView> changeRole(Authentication authentication,
                                                            @RequestHeader("X-Company-Id") UUID companyId,
                                                            @PathVariable UUID memberId,
                                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                            @Valid @RequestBody ChangeRoleRequest body) {
        return ApiResponse.of(members.changeRole(AuthenticatedActor.of(authentication, companyId),
                memberId, body.role(), IfMatch.require(ifMatch), body.reason()));
    }

    @PostMapping("/{memberId}/disable")
    public ApiResponse<MemberService.MemberView> disable(Authentication authentication,
                                                         @RequestHeader("X-Company-Id") UUID companyId,
                                                         @PathVariable UUID memberId,
                                                         @Valid @RequestBody DisableMemberRequest body) {
        return ApiResponse.of(members.setStatus(AuthenticatedActor.of(authentication, companyId), memberId, false, body.reason()));
    }

    @PostMapping("/{memberId}/enable")
    public ApiResponse<MemberService.MemberView> enable(Authentication authentication,
                                                        @RequestHeader("X-Company-Id") UUID companyId,
                                                        @PathVariable UUID memberId) {
        return ApiResponse.of(members.setStatus(AuthenticatedActor.of(authentication, companyId), memberId, true, null));
    }

    @PostMapping("/{memberId}/password-reset")
    public ApiResponse<MemberService.PasswordResetResult> resetPassword(Authentication authentication,
                                                                        @RequestHeader("X-Company-Id") UUID companyId,
                                                                        @PathVariable UUID memberId,
                                                                        @Valid @RequestBody PasswordResetRequest body) {
        return ApiResponse.of(members.resetPassword(AuthenticatedActor.of(authentication, companyId), memberId, body.reason()));
    }

    public record CreateMemberRequest(@NotBlank @Size(max = 100) String username,
                                      @NotBlank @Pattern(regexp = "ADMIN|MANAGER|MEMBER") String role) {}
    public record ChangeRoleRequest(@NotBlank @Pattern(regexp = "ADMIN|MANAGER|MEMBER") String role,
                                    @Size(max = 500) String reason) {}
    public record DisableMemberRequest(@NotBlank @Size(max = 500) String reason) {}
    public record PasswordResetRequest(@NotBlank @Size(max = 500) String reason) {}
}
