<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import {
    Plus,
    Search,
    RefreshCw,
    CalendarPlus,
    ArrowUpRight,
} from "lucide-vue-next";
import { api, query } from "../api";
import { state, errorMessage } from "../state";
import { label, taskNumber, minutes } from "../utils";
import type { Task, TaskDetail, TaskPage } from "../types";
import TaskEditor from "../components/TaskEditor.vue";
import Badge from "../components/Badge.vue";
import Empty from "../components/Empty.vue";
const router = useRouter(),
    route = useRoute(),
    tasks = ref<Task[]>([]),
    status = ref(String(route.query.status || "")),
    search = ref(""),
    selected = ref<string[]>([]),
    cursor = ref<string | null>(null),
    loading = ref(false),
    error = ref(""),
    create = ref(false),
    simulating = ref(false);
const filtered = computed(() =>
    tasks.value.filter(
        (t) =>
            t.title.toLowerCase().includes(search.value.toLowerCase()) ||
            String(t.taskNo).includes(search.value),
    ),
);
let generation = 0;
async function load(append = false) {
    const request = ++generation;
    loading.value = true;
    error.value = "";
    try {
        if (!state.projectId) {
            tasks.value = [];
            return;
        }
        const page = await api<TaskPage>(
            "/tasks" +
                query({
                    projectId: state.projectId,
                    status: status.value,
                    limit: 50,
                    cursor: append ? cursor.value : null,
                }),
        );
        if (request !== generation) return;
        tasks.value = append ? [...tasks.value, ...page.items] : page.items;
        cursor.value = page.nextCursor;
        if (!append) selected.value = [];
    } catch (e) {
        if (request === generation) error.value = errorMessage(e);
    } finally {
        if (request === generation) loading.value = false;
    }
}
watch(status, () => {
    router.replace({ query: { status: status.value || undefined } });
    load();
});
function saved(t: TaskDetail) {
    create.value = false;
    router.push("/tasks/" + t.id);
}
async function simulate() {
    simulating.value = true;
    error.value = "";
    try {
        const result = await api<{ jobId: string }>("/schedule-simulations", {
            method: "POST",
            body: { rootTaskIds: selected.value },
        });
        router.push("/schedule?job=" + result.jobId);
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        simulating.value = false;
    }
}
onMounted(() => load());
</script>
<template>
    <div class="page-heading">
        <div>
            <span class="eyebrow">TASK MANAGEMENT</span>
            <h1>任务中心<span class="heading-dot">.</span></h1>
            <p>从需求到交付，每一步都有迹可循。</p>
        </div>
        <button
            class="btn primary"
            :disabled="!state.projectId"
            @click="create = true"
        >
            <Plus :size="18" />创建任务
        </button>
    </div>
    <div class="panel">
        <div class="toolbar">
            <div class="search-box">
                <Search :size="18" /><input
                    v-model="search"
                    aria-label="搜索已加载任务"
                    placeholder="搜索已加载任务名称、编号…"
                />
            </div>
            <select v-model="status" aria-label="按任务状态筛选">
                <option value="">全部状态</option>
                <option
                    v-for="s in [
                        'DRAFT',
                        'SUBMITTED',
                        'NEEDS_INFO',
                        'READY',
                        'IN_PROGRESS',
                        'BLOCKED',
                        'IN_REVIEW',
                        'DONE',
                        'CANCELED',
                    ]"
                    :key="s"
                    :value="s"
                >
                    {{ label(s) }}
                </option></select
            ><button
                class="icon-btn"
                aria-label="刷新任务"
                :disabled="loading"
                @click="load()"
            >
                <RefreshCw :size="18" :class="{ spin: loading }" />
            </button>
        </div>
        <div v-if="selected.length" class="selection-bar">
            <span>已选择 {{ selected.length }} 项任务</span
            ><button
                class="btn small primary"
                :disabled="simulating"
                @click="simulate"
            >
                <CalendarPlus :size="16" />{{
                    simulating ? "请求中…" : "生成排期方案"
                }}</button
            ><button class="btn small text" @click="selected = []">
                取消选择
            </button>
        </div>
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <div v-if="loading && !tasks.length" class="loading">正在读取任务…</div>
        <Empty
            v-else-if="!filtered.length"
            :title="search || status ? '没有符合条件的任务' : '还没有任务'"
            description="创建任务并提交分析，或手动填写估算后安排工作。"
            ><button
                class="btn"
                :disabled="!state.projectId"
                @click="create = true"
            >
                创建第一项任务
            </button></Empty
        >
        <div v-else class="table-scroll">
            <table>
                <thead>
                    <tr>
                        <th class="check-cell">选择</th>
                        <th>任务</th>
                        <th>状态</th>
                        <th>负责人</th>
                        <th>预估工时</th>
                        <th>截止日期</th>
                        <th>排期</th>
                        <th></th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="t in filtered" :key="t.id">
                        <td class="check-cell">
                            <input
                                v-model="selected"
                                type="checkbox"
                                :value="t.id"
                                :disabled="
                                    ![
                                        'READY',
                                        'IN_PROGRESS',
                                        'BLOCKED',
                                    ].includes(t.status)
                                "
                                :aria-label="'选择任务 ' + t.title"
                            />
                        </td>
                        <td>
                            <RouterLink
                                :to="'/tasks/' + t.id"
                                class="task-title-link"
                                ><span class="task-meta"
                                    >{{ taskNumber(t.taskNo) }}
                                    <i :class="'priority ' + t.priority">{{
                                        t.priority
                                    }}</i
                                    ><small v-if="t.nodeType === 'PARENT'"
                                        >任务组</small
                                    ></span
                                ><strong>{{ t.title }}</strong></RouterLink
                            >
                        </td>
                        <td><Badge :value="t.status" /></td>
                        <td>
                            <span v-if="t.assigneeId" class="person"
                                ><span class="avatar tiny">{{
                                    (state.members.find(
                                        (m) => m.memberId === t.assigneeId,
                                    )?.username || "?")[0]
                                }}</span
                                >{{
                                    state.members.find(
                                        (m) => m.memberId === t.assigneeId,
                                    )?.username || "成员"
                                }}</span
                            ><span v-else class="muted">未分配</span>
                        </td>
                        <td>{{ minutes(t.estimatedMinutes) }}</td>
                        <td>
                            {{ t.dueDate || "未设置"
                            }}<small
                                v-if="t.dueType === 'HARD'"
                                class="danger-text"
                                >硬截止</small
                            >
                        </td>
                        <td><Badge :value="t.scheduleStatus" /></td>
                        <td>
                            <RouterLink
                                :to="'/tasks/' + t.id"
                                class="icon-btn"
                                :aria-label="'查看 ' + t.title"
                                ><ArrowUpRight :size="17"
                            /></RouterLink>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="table-footer">
            <span
                >已加载 {{ tasks.length }} 项 · 勾选就绪任务可批量模拟排期</span
            ><button
                v-if="cursor"
                class="btn small"
                :disabled="loading"
                @click="load(true)"
            >
                {{ loading ? "加载中…" : "加载更多" }}
            </button>
        </div>
    </div>
    <TaskEditor v-if="create" @close="create = false" @saved="saved" />
</template>
