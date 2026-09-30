package com.company.scheduler.planning.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.scheduler.planning.calendar.CalendarService;
import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.error.BusinessException;
import com.company.scheduler.shared.web.GlobalExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CalendarControllerTest {
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACTOR_MEMBER = UUID.randomUUID();

    private final CompanyAccess access = mock(CompanyAccess.class);
    private final CalendarService calendar = mock(CalendarService.class);
    private final Authentication authentication = mock(Authentication.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CalendarController(access, calendar))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(authentication.getName()).thenReturn(USER.toString());
        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "ADMIN"));
    }

    @Test
    void readsCompanyCalendarWithDateRange() throws Exception {
        when(calendar.companyDays(COMPANY, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)))
                .thenReturn(List.of(new CalendarService.CompanyDayView(LocalDate.of(2026, 10, 1),
                        false, "HOLIDAY", "国庆")));
        mockMvc.perform(get("/api/v1/calendar")
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].source").value("HOLIDAY"));
    }

    @Test
    void putCompanyDaysRequiresAdminAndDelegates() throws Exception {
        mockMvc.perform(put("/api/v1/calendar/days")
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":[{\"workDate\":\"2026-10-01\",\"workdayFlag\":false,\"note\":\"国庆\"}]}"))
                .andExpect(status().isOk());
        verify(calendar).putCompanyDays(eq(COMPANY), eq(List.of(
                new CalendarService.CompanyDayInput(LocalDate.of(2026, 10, 1), false, "国庆"))));

        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "MEMBER"));
        mockMvc.perform(put("/api/v1/calendar/days")
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":[{\"workDate\":\"2026-10-01\",\"workdayFlag\":false}]}"))
                .andExpect(status().isForbidden());
        // 普通成员请求被拒绝后不会产生新的日历写入。
        verify(calendar, times(1)).putCompanyDays(eq(COMPANY), anyList());
    }

    @Test
    void putMemberOverridesValidatesInputAndTranslatesBusinessErrors() throws Exception {
        mockMvc.perform(put("/api/v1/members/{memberId}/calendar", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":[{\"workDate\":\"2026-10-12\",\"leaveMinutes\":240,\"reason\":\"病假\"}]}"))
                .andExpect(status().isOk());
        verify(calendar).putMemberOverrides(eq(COMPANY), eq(ACTOR_MEMBER), eq(List.of(
                new CalendarService.MemberDayInput(LocalDate.of(2026, 10, 12), null, 240, null, "病假"))));

        org.mockito.Mockito.doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "历史日期不可修改日历")).when(calendar).putMemberOverrides(eq(COMPANY), eq(ACTOR_MEMBER), anyList());
        mockMvc.perform(put("/api/v1/members/{memberId}/calendar", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":[{\"workDate\":\"2020-01-01\",\"leaveMinutes\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(put("/api/v1/members/{memberId}/calendar", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrides\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memberCalendarReadsRequireSelfOrManager() throws Exception {
        UUID otherMember = UUID.randomUUID();
        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "MEMBER"));
        mockMvc.perform(get("/api/v1/members/{memberId}/calendar", otherMember)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .param("from", "2026-10-01").param("to", "2026-10-03"))
                .andExpect(status().isForbidden());
        verify(calendar, never()).memberDays(eq(COMPANY), eq(otherMember), eq(LocalDate.of(2026, 10, 1)),
                eq(LocalDate.of(2026, 10, 3)));
    }
}
