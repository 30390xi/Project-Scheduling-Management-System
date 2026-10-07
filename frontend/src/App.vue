<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
    Layers,
    LayoutDashboard,
    ListTodo,
    CalendarDays,
    Users,
    FolderKanban,
    LogOut,
    Menu,
    X,
    ArrowUpRight,
    ChevronDown,
} from "lucide-vue-next";
import Login from "./views/Login.vue";
import {
    state,
    company,
    project,
    me,
    restore,
    loadCompany,
    selectProject,
    logout,
    errorMessage,
} from "./state";
import { label } from "./utils";
const route = useRoute(),
    router = useRouter(),
    menu = ref(false);
const nav = [
    { path: "/", title: "工作概览", icon: LayoutDashboard },
    { path: "/tasks", title: "任务中心", icon: ListTodo },
    { path: "/schedule", title: "成员排期", icon: CalendarDays },
    { path: "/team", title: "团队与技能", icon: Users },
    { path: "/projects", title: "项目设置", icon: FolderKanban },
];
async function changeCompany(event: Event) {
    await router.push("/");
    await loadCompany((event.target as HTMLSelectElement).value);
}
async function changeProject(event: Event) {
    await selectProject((event.target as HTMLSelectElement).value);
    await router.push("/");
}
async function signOut() {
    try {
        await logout();
        await router.push("/");
    } catch (e) {
        state.error = errorMessage(e);
    }
}
onMounted(restore);
</script>
<template>
    <div v-if="!state.ready" class="boot">
        <span class="spinner"></span>正在连接工作空间…
    </div>
    <Login v-else-if="!state.authenticated || state.passwordRequired" />
    <div v-else class="app-shell">
        <a class="skip-link" href="#main">跳到主要内容</a>
        <div v-if="menu" class="nav-scrim" @click="menu = false"></div>
        <aside :class="['sidebar', { open: menu }]">
            <div class="brand">
                <span class="brand-mark"><Layers :size="23" /></span
                ><strong>序程<span>AI SCHEDULER</span></strong
                ><button
                    class="icon-btn mobile-close"
                    aria-label="关闭导航"
                    @click="menu = false"
                >
                    <X />
                </button>
            </div>
            <label class="workspace-label"
                >工作空间
                <div class="company-select">
                    <select
                        :value="state.companyId"
                        aria-label="选择公司"
                        @change="changeCompany"
                    >
                        <option
                            v-for="c in state.companies"
                            :key="c.id"
                            :value="c.id"
                        >
                            {{ c.name }}
                        </option></select
                    ><ChevronDown :size="16" /></div
            ></label>
            <div class="nav-label">工作管理</div>
            <nav>
                <RouterLink
                    v-for="item in nav"
                    :key="item.path"
                    :to="item.path"
                    :class="{
                        selected:
                            item.path === '/'
                                ? route.path === '/'
                                : route.path.startsWith(item.path),
                    }"
                    @click="menu = false"
                    ><component :is="item.icon" :size="19" /><span>{{
                        item.title
                    }}</span></RouterLink
                >
            </nav>
            <div class="sidebar-note">
                <span class="small-light">更合理的工作节奏</span>
                <p>AI 给出建议，<br />你掌握每一次安排。</p>
                <RouterLink to="/schedule"
                    >查看团队排期 <ArrowUpRight :size="15"
                /></RouterLink>
            </div>
            <div class="sidebar-user">
                <span class="avatar">{{
                    (me?.username || "我").slice(0, 1).toUpperCase()
                }}</span>
                <div>
                    <strong>{{ me?.username || "已登录" }}</strong
                    ><small>{{ label(company?.role || "MEMBER") }}</small>
                </div>
                <button
                    class="icon-btn"
                    aria-label="退出登录"
                    title="退出登录"
                    @click="signOut"
                >
                    <LogOut :size="18" />
                </button>
            </div>
        </aside>
        <div class="workspace">
            <header class="topbar">
                <div class="row">
                    <button
                        class="icon-btn menu-button"
                        aria-label="打开导航"
                        @click="menu = true"
                    >
                        <Menu /></button
                    ><span class="breadcrumb"
                        >工作空间 <span>/</span>
                        {{
                            nav.find((n) =>
                                n.path === "/"
                                    ? route.path === "/"
                                    : route.path.startsWith(n.path),
                            )?.title
                        }}</span
                    >
                </div>
                <div class="row">
                    <span class="project-caption">当前项目</span
                    ><select
                        class="project-select"
                        :value="state.projectId"
                        aria-label="选择项目"
                        @change="changeProject"
                    >
                        <option v-if="!state.projects.length" value="">
                            暂无项目
                        </option>
                        <option
                            v-for="p in state.projects"
                            :key="p.id"
                            :value="p.id"
                        >
                            {{ p.name }}
                        </option>
                    </select>
                </div>
            </header>
            <main id="main" tabindex="-1">
                <div v-if="state.error" class="alert error" role="alert">
                    {{ state.error
                    }}<button class="btn small" @click="restore">
                        重新加载</button
                    ><button
                        class="icon-btn"
                        aria-label="关闭提示"
                        @click="state.error = ''"
                    >
                        <X :size="16" />
                    </button>
                </div>
                <div v-if="!state.companies.length" class="empty">
                    <h2>尚未加入工作空间</h2>
                    <p>请联系管理员建立你的公司成员关系。</p>
                </div>
                <div v-else-if="state.busy" class="loading">
                    <span class="spinner"></span>正在加载 {{ company?.name }}…
                </div>
                <RouterView
                    v-else
                    :key="state.companyId + ':' + state.projectId"
                />
            </main>
            <footer class="app-footer">
                <span>序程 · 让工作有序发生</span
                ><span
                    >{{ project?.name || "工作空间" }} ·
                    {{ company?.timezone }}</span
                >
            </footer>
        </div>
        <div v-if="state.notice" class="toast" role="status">
            {{ state.notice
            }}<button
                class="icon-btn"
                aria-label="关闭提示"
                @click="state.notice = ''"
            >
                <X :size="16" />
            </button>
        </div>
    </div>
</template>
