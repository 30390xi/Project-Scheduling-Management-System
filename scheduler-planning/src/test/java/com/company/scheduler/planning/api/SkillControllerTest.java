package com.company.scheduler.planning.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.scheduler.planning.skill.SkillService;
import com.company.scheduler.shared.contract.CompanyAccess;
import com.company.scheduler.shared.web.GlobalExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillControllerTest {
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACTOR_MEMBER = UUID.randomUUID();

    private final CompanyAccess access = mock(CompanyAccess.class);
    private final SkillService skills = mock(SkillService.class);
    private final Authentication authentication = mock(Authentication.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillController(access, skills))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(authentication.getName()).thenReturn(USER.toString());
    }

    private CompanyAccess.Membership membership(String role) {
        return new CompanyAccess.Membership(ACTOR_MEMBER, COMPANY, USER, role);
    }

    @Test
    void listsCatalogForAnyMember() throws Exception {
        when(access.requireMember(USER, COMPANY)).thenReturn(membership("MEMBER"));
        when(skills.list(COMPANY)).thenReturn(List.of(
                new SkillService.SkillView(UUID.randomUUID(), "JAVA", "Java", "", true)));
        mockMvc.perform(get("/api/v1/skills").header("X-Company-Id", COMPANY.toString()).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("JAVA"));
    }

    @Test
    void createsSkillOnlyForAdmin() throws Exception {
        when(access.requireMember(USER, COMPANY)).thenReturn(membership("ADMIN"));
        when(skills.create(COMPANY, "JAVA", "Java", null))
                .thenReturn(new SkillService.SkillView(UUID.randomUUID(), "JAVA", "Java", "", true));
        mockMvc.perform(post("/api/v1/skills").header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"JAVA\",\"name\":\"Java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("JAVA"));

        when(access.requireMember(USER, COMPANY)).thenReturn(membership("MANAGER"));
        mockMvc.perform(post("/api/v1/skills").header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SQL\",\"name\":\"SQL\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void rejectsInvalidCodeAndBody() throws Exception {
        when(access.requireMember(USER, COMPANY)).thenReturn(membership("ADMIN"));
        mockMvc.perform(post("/api/v1/skills").header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"java\",\"name\":\"Java\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/skills").header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"没有编号\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void certifiesForAdminAndSelfReportsForOwner() throws Exception {
        when(access.requireMember(USER, COMPANY)).thenReturn(membership("ADMIN"));
        var skillId = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/members/{memberId}/skills", ACTOR_MEMBER)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"" + skillId + "\",\"level\":3}]}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(skills).certifySet(eq(COMPANY), eq(ACTOR_MEMBER), eq(ACTOR_MEMBER),
                eq(List.of(new SkillService.SkillAssignment(skillId, 3))));

        when(access.requireMember(USER, COMPANY)).thenReturn(membership("MEMBER"));
        mockMvc.perform(put("/api/v1/my/skills")
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"" + skillId + "\",\"level\":2}]}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(skills).selfReportSet(eq(COMPANY), eq(ACTOR_MEMBER),
                eq(List.of(new SkillService.SkillAssignment(skillId, 2))));

        mockMvc.perform(put("/api/v1/my/skills")
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"" + skillId + "\",\"level\":9}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void memberViewsRequireSelfOrManager() throws Exception {
        UUID otherMember = UUID.randomUUID();
        when(access.requireMember(USER, COMPANY)).thenReturn(membership("MEMBER"));
        mockMvc.perform(get("/api/v1/members/{memberId}/skills", otherMember)
                        .header("X-Company-Id", COMPANY.toString()).principal(authentication))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verify(skills, org.mockito.Mockito.never()).memberSkills(COMPANY, otherMember);
    }
}
