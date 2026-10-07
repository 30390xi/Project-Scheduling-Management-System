<script setup lang="ts">
import { onMounted, computed, ref } from "vue";
import {
    ArrowUpRight,
    Plus,
    ArrowRight,
    ListTodo,
    Clock3,
    CircleCheck,
    CalendarDays,
} from "lucide-vue-next";
import { useRouter } from "vue-router";
import { api, query } from "../api";
import { state, project, errorMessage } from "../state";
import type { Task, TaskPage, TaskDetail } from "../types";
import { minutes, taskNumber, dateString } from "../utils";
import Badge from "../components/Badge.vue";
import Empty from "../components/Empty.vue";
import TaskEditor from "../components/TaskEditor.vue";
const tasks = ref<Task[]>([]),
    loading = ref(true),
    error = ref(""),
    create = ref(false),
    more = ref(false),
    router = useRouter();
const stats = computed(() => [
    {
        name: "已加载任务",
        value: tasks.value.length,
        icon: ListTodo,
        note: more.value ? "当前展示最近 100 项" : "当前项目全部任务",
    },
    {
        name: "进行中的任务",
        value: tasks.value.filter((t) => t.status === "IN_PROGRESS").length,
        icon: Clock3,
        note: "持续推进中的工作",
    },
    {
        name: "待安排任务",
        value: tasks.value.filter(
            (t) => t.status === "READY" && t.scheduleStatus !== "PLANNED",
        ).length,
        icon: CalendarDays,
        note: "已就绪，等待有效排期",
    },
    {
        name: "已完成任务",
        value: tasks.value.filter((t) => t.status === "DONE").length,
        icon: CircleCheck,
        note: "已通过验收的成果",
    },
]);
async function load() {
    loading.value = true;
    error.value = "";
    try {
        if (state.projectId) {
            const data = await api<TaskPage>(
                "/tasks" + query({ projectId: state.projectId, limit: 100 }),
            );
            tasks.value = data.items;
            more.value = !!data.nextCursor;
        }
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        loading.value = false;
    }
}
function saved(t: TaskDetail) {
    create.value = false;
    router.push("/tasks/" + t.id);
}
onMounted(load);
</script>
<template>
    <div class="page-heading">
        <div>
            <span class="eyebrow">WORKSPACE OVERVIEW</span>
            <h1>工作概览<span class="heading-dot">.</span></h1>
            <p>每一项任务，每一份投入，都在计划之中。</p>
        </div>
        <button
            class="btn primary"
            :disabled="!state.projectId"
            @click="create = true"
        >
            <Plus :size="18" />创建任务
        </button>
    </div>
    <div v-if="error" class="alert error" role="alert">
        {{ error }}<button class="btn small" @click="load">重试</button>
    </div>
    <div class="overview-banner">
        <div>
            <span class="eyebrow">{{
                new Date().toLocaleDateString("zh-CN", {
                    month: "long",
                    day: "numeric",
                    weekday: "long",
                })
            }}</span>
            <h2>让团队，按合适的节奏前进。</h2>
            <p>从清晰的需求开始，让每一天的安排都可执行。</p>
            <RouterLink to="/schedule" class="btn"
                >查看成员排期<ArrowUpRight :size="16"
            /></RouterLink>
        </div>
        <div class="banner-visual" aria-hidden="true">
            <div class="mini-day"><span>任务分析</span><i></i><i></i></div>
            <div class="mini-day">
                <span>技能匹配</span><i></i><i></i><i></i>
            </div>
            <div class="mini-day"><span>有序执行</span><i></i><i></i></div>
            <div class="visual-stamp">
                <CircleCheck :size="17" />让安排更从容
            </div>
        </div>
    </div>
    <div class="stats">
        <div v-for="(s, i) in stats" :key="s.name" class="stat-card">
            <div class="stat-title">
                {{ s.name }}<component :is="s.icon" :size="18" />
            </div>
            <strong>{{
                loading ? "—" : s.value.toString().padStart(2, "0")
            }}</strong
            ><span>{{ s.note }}</span>
            <div class="stat-rule" :class="'tone-' + i"></div>
        </div>
    </div>
    <div class="dashboard-grid">
        <section class="panel">
            <div class="panel-head">
                <div>
                    <h2>最近更新</h2>
                    <span>{{ project?.name || "尚未选择项目" }}</span>
                </div>
                <RouterLink class="text-link" to="/tasks"
                    >全部任务<ArrowUpRight :size="16"
                /></RouterLink>
            </div>
            <div v-if="loading" class="loading">正在读取任务…</div>
            <Empty
                v-else-if="!tasks.length"
                title="从第一项任务开始"
                description="描述需求、明确交付，接下来交给团队协作。"
                ><button
                    v-if="state.projectId"
                    class="btn"
                    @click="create = true"
                >
                    <Plus :size="16" />创建任务</button
                ><RouterLink v-else class="btn" to="/projects"
                    >创建项目</RouterLink
                ></Empty
            >
            <div v-else class="recent-list">
                <RouterLink
                    v-for="t in tasks.slice(0, 6)"
                    :key="t.id"
                    :to="'/tasks/' + t.id"
                    class="recent-task"
                    ><span class="task-mark">{{ t.priority }}</span>
                    <div class="grow">
                        <strong>{{ t.title }}</strong
                        ><small
                            >{{ taskNumber(t.taskNo) }} <span>·</span>
                            {{ minutes(t.estimatedMinutes) }}</small
                        >
                    </div>
                    <Badge :value="t.status" /><ArrowUpRight :size="17"
                /></RouterLink>
            </div>
        </section>
        <section class="panel guide">
            <span class="eyebrow">GET STARTED</span>
            <h2>把想法变成<br />可执行的计划。</h2>
            <RouterLink to="/team" class="guide-step"
                ><span>01</span>
                <div>
                    <strong>建立团队能力</strong>
                    <p>认证成员技能，设置每日容量。</p>
                </div>
                <ArrowRight :size="17" /></RouterLink
            ><RouterLink to="/tasks" class="guide-step"
                ><span>02</span>
                <div>
                    <strong>让需求清晰起来</strong>
                    <p>AI 分析与拆解，由你确认建议。</p>
                </div>
                <ArrowRight :size="17" /></RouterLink
            ><RouterLink to="/schedule" class="guide-step"
                ><span>03</span>
                <div>
                    <strong>安排合理的节奏</strong>
                    <p>检查冲突，再确认任务排期。</p>
                </div>
                <ArrowRight :size="17"
            /></RouterLink>
            <div class="guide-footer">AI 辅助决策 · 团队掌握主动权</div>
        </section>
    </div>
    <TaskEditor v-if="create" @close="create = false" @saved="saved" />
</template>
