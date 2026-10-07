import { reactive, computed } from "vue";
import { api, setApiCompany } from "./api";
import type { Company, Project, Member, Skill, ProjectMember } from "./types";
export const state = reactive({
    ready: false,
    authenticated: false,
    passwordRequired: false,
    userId: sessionStorage.getItem("scheduler.user") || "",
    companies: [] as Company[],
    companyId: "",
    projects: [] as Project[],
    projectId: "",
    members: [] as Member[],
    skills: [] as Skill[],
    projectMembers: [] as ProjectMember[],
    error: "",
    notice: "",
    busy: false,
});
export const company = computed(() =>
    state.companies.find((c) => c.id === state.companyId),
);
export const isAdmin = computed(() => company.value?.role === "ADMIN");
export const isManager = computed(() =>
    ["ADMIN", "MANAGER"].includes(company.value?.role || ""),
);
export const me = computed(() =>
    state.members.find((m) => m.userId === state.userId),
);
export const project = computed(() =>
    state.projects.find((p) => p.id === state.projectId),
);
export const canManageProject = computed(
    () =>
        isAdmin.value ||
        state.projectMembers.some(
            (m) => m.memberId === me.value?.memberId && m.role === "OWNER",
        ),
);
export function errorMessage(error: unknown) {
    return error instanceof Error ? error.message : "操作失败，请重试";
}
export function notify(text: string) {
    state.notice = text;
    window.setTimeout(() => {
        if (state.notice === text) state.notice = "";
    }, 6000);
}
let generation = 0;
export async function loadCompany(id: string) {
    const current = ++generation;
    state.busy = true;
    state.error = "";
    state.companyId = id;
    state.projectId = "";
    state.projects = [];
    state.members = [];
    state.skills = [];
    state.projectMembers = [];
    setApiCompany(id);
    try {
        const [projects, members, skills] = await Promise.all([
            api<Project[]>("/projects"),
            api<Member[]>("/members"),
            api<Skill[]>("/skills"),
        ]);
        if (current !== generation) return;
        state.projects = projects;
        state.members = members;
        state.skills = skills;
        const saved = sessionStorage.getItem("scheduler.project." + id);
        await selectProject(
            projects.find((p) => p.id === saved)?.id || projects[0]?.id || "",
        );
        sessionStorage.setItem("scheduler.company", id);
    } catch (e) {
        if (current === generation) state.error = errorMessage(e);
    } finally {
        if (current === generation) state.busy = false;
    }
}
export async function selectProject(id: string) {
    state.projectId = id;
    state.projectMembers = [];
    if (!id) return;
    sessionStorage.setItem("scheduler.project." + state.companyId, id);
    try {
        const members = await api<ProjectMember[]>(`/projects/${id}/members`);
        if (state.projectId === id) state.projectMembers = members;
    } catch {
        /* The backend still authorizes each project operation. An admin may only manage membership. */
    }
}
export async function restore() {
    try {
        const current = await api<{ userId: string }>("/me");
        state.userId = current.userId;
        state.companies = await api<Company[]>("/me/companies");
        state.authenticated = true;
        const saved = sessionStorage.getItem("scheduler.company");
        const id =
            state.companies.find((c) => c.id === saved)?.id ||
            state.companies[0]?.id;
        if (id) await loadCompany(id);
    } catch (e) {
        if (!state.passwordRequired) {
            state.authenticated = false;
            if ((e as { status?: number }).status !== 401)
                state.error = errorMessage(e);
        }
    } finally {
        state.ready = true;
    }
}
export async function login(username: string, password: string) {
    const result = await api<{ userId: string }>("/auth/login", {
        method: "POST",
        body: { username, password },
    });
    state.userId = result.userId;
    sessionStorage.setItem("scheduler.user", result.userId);
    state.authenticated = true;
    state.error = "";
    await restore();
}
export async function logout() {
    await api("/auth/logout", { method: "POST" });
    resetSession();
}
export function resetSession() {
    generation++;
    state.authenticated = false;
    state.passwordRequired = false;
    state.userId = "";
    state.companies = [];
    state.companyId = "";
    state.members = [];
    state.projects = [];
    state.skills = [];
    state.projectMembers = [];
    state.projectId = "";
    setApiCompany("");
    sessionStorage.removeItem("scheduler.user");
}
window.addEventListener("session-expired", resetSession);
window.addEventListener("password-required", () => {
    state.authenticated = true;
    state.passwordRequired = true;
});
