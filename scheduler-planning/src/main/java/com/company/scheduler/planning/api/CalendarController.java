package com.company.scheduler.planning.api;

import com.company.scheduler.planning.calendar.CalendarService;
import com.company.scheduler.planning.support.PlanningPermissions;
import com.company.scheduler.shared.api.ApiResponse;
import com.company.scheduler.shared.contract.CompanyAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** Company workdays plus member leave/overrides; changes recompute capacity buckets in the same transaction. */
@RestController
@RequestMapping("/api/v1")
public class CalendarController {
    private final CompanyAccess access;
    private final CalendarService calendar;

    public CalendarController(CompanyAccess access, CalendarService calendar) {
        this.access = access;
        this.calendar = calendar;
    }

    public record CompanyCalendarRequest(@NotEmpty @Valid List<CompanyDayInput> days) {}

    public record CompanyDayInput(@NotNull LocalDate workDate, @NotNull Boolean workdayFlag,
                                  @Size(max = 500) String note) {}

    public record MemberCalendarRequest(@NotEmpty @Valid List<MemberDayInput> overrides) {}

    public record MemberDayInput(@NotNull LocalDate workDate, Boolean workdayFlag,
                                 Integer leaveMinutes, Integer extraUnavailableMinutes,
                                 @Size(max = 500) String reason) {}

    @GetMapping("/calendar")
    public ApiResponse<List<CalendarService.CompanyDayView>> companyDays(
            Authentication authentication,
            @RequestHeader("X-Company-Id") UUID companyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.requireMember(userId(authentication), companyId);
        return ApiResponse.of(calendar.companyDays(companyId, from, to));
    }

    @PutMapping("/calendar/days")
    public ApiResponse<Void> putCompanyDays(Authentication authentication,
                                            @RequestHeader("X-Company-Id") UUID companyId,
                                            @Valid @RequestBody CompanyCalendarRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireAdmin(membership);
        calendar.putCompanyDays(companyId, request.days().stream()
                .map(day -> new CalendarService.CompanyDayInput(day.workDate(), day.workdayFlag(), day.note()))
                .toList());
        return ApiResponse.of(null);
    }

    @GetMapping("/members/{memberId}/calendar")
    public ApiResponse<List<CalendarService.MemberDayView>> memberDays(
            Authentication authentication,
            @RequestHeader("X-Company-Id") UUID companyId,
            @PathVariable UUID memberId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireSelfOrManager(membership, memberId);
        return ApiResponse.of(calendar.memberDays(companyId, memberId, from, to));
    }

    @PutMapping("/members/{memberId}/calendar")
    public ApiResponse<Void> putMemberOverrides(Authentication authentication,
                                                @RequestHeader("X-Company-Id") UUID companyId,
                                                @PathVariable UUID memberId,
                                                @Valid @RequestBody MemberCalendarRequest request) {
        var membership = access.requireMember(userId(authentication), companyId);
        PlanningPermissions.requireAdmin(membership);
        calendar.putMemberOverrides(companyId, memberId, request.overrides().stream()
                .map(day -> new CalendarService.MemberDayInput(day.workDate(), day.workdayFlag(),
                        day.leaveMinutes(), day.extraUnavailableMinutes(), day.reason()))
                .toList());
        return ApiResponse.of(null);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
