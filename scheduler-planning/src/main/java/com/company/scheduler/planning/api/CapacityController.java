package com.company.scheduler.planning.api;

import com.company.scheduler.planning.capacity.CapacityPolicyService;
import com.company.scheduler.planning.capacity.CapacityQueryService;
import com.company.scheduler.planning.support.PlanningPermissions;
import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.shared.contract.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Member capacity detail (C/U/R with sources) and the capacity policy adjustment path. */
@RestController
@RequestMapping("/api/v1")
public class CapacityController {
    private final CompanyAccess access;
    private final CapacityQueryService query;
    private final CapacityPolicyService policy;

    public CapacityController(CompanyAccess access, CapacityQueryService query, CapacityPolicyService policy) {
        this.access = access;
        this.query = query;
        this.policy = policy;
    }

    public record CapacityPolicyRequest(@NotNull @Min(0) @Max(1440) Integer standardMinutes,
                                        @NotNull @Min(0) @Max(1440) Integer dailyLimitMinutes) {}

    @GetMapping("/members/{memberId}/capacity")
    public ApiResponse<List<CapacityQueryService.CapacityDay>> capacity(
            Authentication authentication,
            @RequestHeader("X-Company-Id") UUID companyId,
            @PathVariable UUID memberId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireSelfOrManager(membership, memberId);
        return ApiResponse.of(query.capacity(companyId, memberId, from, to));
    }

    @PutMapping("/members/{memberId}/capacity-policy")
    public ApiResponse<Void> updatePolicy(Authentication authentication,
                                          @RequestHeader("X-Company-Id") UUID companyId,
                                          @PathVariable UUID memberId,
                                          @Valid @RequestBody CapacityPolicyRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireAdmin(membership);
        policy.updatePolicy(companyId, memberId, request.standardMinutes(), request.dailyLimitMinutes());
        return ApiResponse.of(null);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
