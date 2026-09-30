package com.company.scheduler.planning.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.scheduler.planning.capacity.CapacityPolicyService;
import com.company.scheduler.planning.capacity.CapacityQueryService;
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

class CapacityControllerTest {
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACTOR_MEMBER = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 10, 12);
    private static final LocalDate TO = LocalDate.of(2026, 10, 16);

    private final CompanyAccess access = mock(CompanyAccess.class);
    private final CapacityQueryService query = mock(CapacityQueryService.class);
    private final CapacityPolicyService policy = mock(CapacityPolicyService.class);
    private final Authentication authentication = mock(Authentication.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CapacityController(access, query, policy))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(authentication.getName()).thenReturn(USER.toString());
    }

    @Test
    void returnsCapacityDaysForSelfOrManager() throws Exception {
        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "MEMBER"));
        when(query.capacity(COMPANY, ACTOR_MEMBER, FROM, TO)).thenReturn(List.of(
                new CapacityQueryService.CapacityDay(ACTOR_MEMBER, FROM, true, 480, 360,
                        0, 0, 360, 120, 240, 0)));
        mockMvc.perform(get("/api/v1/members/{memberId}/capacity", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .param("from", "2026-10-12").param("to", "2026-10-16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].capacityMinutes").value(360))
                .andExpect(jsonPath("$.data[0].remainingMinutes").value(240));
    }

    @Test
    void plainMemberCannotReadAnotherMember() throws Exception {
        UUID otherMember = UUID.randomUUID();
        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "MEMBER"));
        mockMvc.perform(get("/api/v1/members/{memberId}/capacity", otherMember)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .param("from", "2026-10-12").param("to", "2026-10-16"))
                .andExpect(status().isForbidden());
        verify(query, never()).capacity(eq(COMPANY), eq(otherMember), eq(FROM), eq(TO));
    }

    @Test
    void policyUpdateRequiresAdminAndRejectsInvalidValues() throws Exception {
        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "ADMIN"));
        mockMvc.perform(put("/api/v1/members/{memberId}/capacity-policy", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"standardMinutes\":480,\"dailyLimitMinutes\":360}"))
                .andExpect(status().isOk());
        verify(policy).updatePolicy(COMPANY, ACTOR_MEMBER, 480, 360);

        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "MEMBER"));
        mockMvc.perform(put("/api/v1/members/{memberId}/capacity-policy", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"standardMinutes\":480,\"dailyLimitMinutes\":360}"))
                .andExpect(status().isForbidden());

        when(access.requireMember(USER, COMPANY)).thenReturn(
                new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, "ADMIN"));
        org.mockito.Mockito.doThrow(new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "每日任务容量必须介于 0 与标准工时之间且为 30 的整数倍（分钟）"))
                .when(policy).updatePolicy(COMPANY, ACTOR_MEMBER, 480, 720);
        mockMvc.perform(put("/api/v1/members/{memberId}/capacity-policy", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"standardMinutes\":480,\"dailyLimitMinutes\":720}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
