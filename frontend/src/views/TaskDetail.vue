<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
    ArrowLeft,
    Pencil,
    RefreshCw,
    Sparkles,
    CalendarPlus,
    Check,
    ChevronRight,
    Clock3,
    LockKeyhole,
    Plus,
    Trash2,
} from "lucide-vue-next";
import { api, query } from "../api";
import { state, me, canManageProject, errorMessage, notify } from "../state";
import {
    minutes,
    label,
    taskNumber,
    analysisOutput,
    dateString,
    uid,
} from "../utils";
import type { TaskDetail, TaskPage, Analysis } from "../types";
import Badge from "../components/Badge.vue";
import Modal from "../components/Modal.vue";
import Empty from "../components/Empty.vue";
import TaskEditor from "../components/TaskEditor.vue";
const route = useRoute(),
    router = useRouter(),
    task = ref<TaskDetail>(),
    runs = ref<Analysis[]>([]),
    selectedRun = ref(""),
    tab = ref("overview"),
    loading = ref(true),
    busy = ref(false),
    error = ref(""),
    editing = ref(false),
    confirmed = ref(false),
    action = ref(""),
    reason = ref(""),
    amount = ref(60),
    workDate = ref(dateString()),
    lock = ref(true),
    predecessors = ref<string[]>([]),
    candidates = ref<TaskPage>({ items: [], nextCursor: null }),
    modalError = ref("");
let timer: ReturnType<typeof setTimeout> | undefined,
    alive = true,
    loadId = 0,
    operationKey = uid();
const run = computed(
        () =>
            runs.value.find((r) => r.id === selectedRun.value) || runs.value[0],
    ),
    output = computed(() => analysisOutput(run.value));
const processing = computed(() =>
    runs.value.some((r) => ["PENDING", "RUNNING"].includes(r.status)),
);
const canExecute = computed(
    () =>
        canManageProject.value || me.value?.memberId === task.value?.assigneeId,
);
const actions = computed(() => {
    if (!task.value || task.value.nodeType === "PARENT") return [];
    const s = task.value.status;
    const results: { id: string; name: string }[] = [];
    if (canManageProject.value && ["SUBMITTED", "NEEDS_INFO"].includes(s))
        results.push({ id: "MARK_READY", name: "确认人工估算" });
    if (canExecute.value) {
        if (s === "READY")
            results.push(
                { id: "START", name: "开始任务" },
                { id: "BLOCK", name: "标记阻塞" },
            );
        if (s === "IN_PROGRESS")
            results.push(
                { id: "SUBMIT_REVIEW", name: "提交验收" },
                { id: "BLOCK", name: "标记阻塞" },
            );
        if (s === "BLOCKED") results.push({ id: "UNBLOCK", name: "解除阻塞" });
    }
    if (canManageProject.value) {
        if (s === "IN_REVIEW")
            results.push(
                { id: "ACCEPT", name: "验收通过" },
                { id: "RETURN", name: "退回修改" },
            );
        if (s === "DONE") results.push({ id: "REOPEN", name: "重新打开" });
    }
    return results;
});
const actionTitle = computed(
    () =>
        ({
            SUBMIT: "提交 AI 分析",
            CONFIRM: "确认 AI 建议",
            CANCEL: "取消任务",
            DELETE: "删除草稿",
            LOG: "登记实际工时",
            REMAINING: "更新剩余工时",
            LOCK: "设置排期锁定",
            DEPENDENCIES: "维护前置依赖",
            REANALYZE: "重新分析",
        })[action.value] ||
        actions.value.find((a) => a.id === action.value)?.name ||
        "确认操作",
);
async function load(silent = false) {
    const id = ++loadId;
    if (!silent) loading.value = true;
    try {
        const [detail, analyses] = await Promise.all([
            api<TaskDetail>("/tasks/" + route.params.id),
            api<Analysis[]>("/tasks/" + route.params.id + "/ai-analysis-runs"),
        ]);
        if (!alive || id !== loadId) return;
        task.value = detail;
        runs.value = analyses;
        if (!analyses.some((r) => r.id === selectedRun.value))
            selectedRun.value = analyses[0]?.id || "";
        if (silent) error.value = "";
    } catch (e) {
        if (alive && id === loadId) error.value = errorMessage(e);
    } finally {
        if (alive && id === loadId) {
            loading.value = false;
            clearTimeout(timer);
            if (processing.value)
                timer = setTimeout(() => {
                    if (!busy.value) load(true);
                    else timer = setTimeout(() => load(true), 3000);
                }, 4000);
        }
    }
}
watch(selectedRun, () => (confirmed.value = false));
watch(
    () => route.params.id,
    () => {
        selectedRun.value = "";
        task.value = undefined;
        runs.value = [];
        error.value = "";
        load();
    },
);
async function openAction(id: string) {
    action.value = id;
    reason.value = "";
    modalError.value = "";
    amount.value = task.value?.remainingMinutes || 60;
    operationKey = uid();
    if (id === "DEPENDENCIES") {
        predecessors.value =
            task.value?.predecessors.map((p) => p.taskId) || [];
        try {
            candidates.value = await api<TaskPage>(
                "/tasks" +
                    query({ projectId: task.value?.projectId, limit: 100 }),
            );
        } catch (e) {
            modalError.value = errorMessage(e);
        }
    }
}
async function perform() {
    if (!task.value) return;
    busy.value = true;
    modalError.value = "";
    const t = task.value;
    try {
        if (action.value === "SUBMIT")
            await api(`/tasks/${t.id}/submit`, {
                method: "POST",
                version: t.version,
            });
        else if (action.value === "REANALYZE")
            await api(`/tasks/${t.id}/ai-analysis-runs`, { method: "POST" });
        else if (action.value === "CONFIRM") {
            const result = await api<{ simulationJobId: string }>(
                `/ai-analysis-runs/${run.value!.id}/confirm`,
                {
                    method: "POST",
                    body: { confirmAllAssumptions: confirmed.value },
                },
            );
            action.value = "";
            await router.push("/schedule?job=" + result.simulationJobId);
            return;
        } else if (action.value === "DELETE") {
            await api("/tasks/" + t.id, { method: "DELETE" });
            await router.push("/tasks");
            return;
        } else if (action.value === "LOG")
            await api(`/tasks/${t.id}/work-logs`, {
                method: "POST",
                body: {
                    workDate: workDate.value,
                    minutes: amount.value,
                    note: reason.value,
                },
                key: operationKey,
            });
        else if (action.value === "REMAINING")
            await api(`/tasks/${t.id}/remaining-estimate`, {
                method: "PUT",
                body: {
                    remainingMinutes: amount.value,
                    effectiveAfterDate: workDate.value,
                },
                version: t.version,
            });
        else if (action.value === "LOCK")
            await api(`/tasks/${t.id}/schedule-lock`, {
                method: "PUT",
                body: { locked: lock.value, reason: reason.value },
            });
        else if (action.value === "DEPENDENCIES")
            await api(`/tasks/${t.id}/dependencies`, {
                method: "PUT",
                body: { predecessorIds: predecessors.value },
                version: t.version,
            });
        else
            await api(`/tasks/${t.id}/transitions`, {
                method: "POST",
                version: t.version,
                body: {
                    action: action.value,
                    reason: reason.value || null,
                    remainingMinutes: ["RETURN", "REOPEN"].includes(
                        action.value,
                    )
                        ? amount.value
                        : null,
                },
            });
        const completed = action.value;
        action.value = "";
        notify("操作已完成");
        await load();
        if (["SUBMIT", "REANALYZE"].includes(completed)) tab.value = "ai";
    } catch (e) {
        modalError.value = errorMessage(e);
    } finally {
        busy.value = false;
    }
}
async function simulate() {
    if (!task.value) return;
    busy.value = true;
    error.value = "";
    try {
        const r = await api<{ jobId: string }>("/schedule-simulations", {
            method: "POST",
            body: { rootTaskIds: [task.value.id] },
        });
        await router.push("/schedule?job=" + r.jobId);
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        busy.value = false;
    }
}
function edited(value: TaskDetail) {
    editing.value = false;
    task.value = value;
    notify("任务已保存");
    load();
}
onMounted(() => load());
onBeforeUnmount(() => {
    alive = false;
    clearTimeout(timer);
});
</script>
<template>
    <RouterLink to="/tasks" class="back-link"
        ><ArrowLeft :size="16" />返回任务中心</RouterLink
    >
    <div v-if="error" class="alert error" role="alert">
        {{ error }}<button class="btn small" @click="load()">刷新</button>
    </div>
    <div v-if="loading && !task" class="loading">正在加载任务…</div>
    <template v-if="task"
        ><div class="page-heading detail-heading">
            <div>
                <div class="row">
                    <span class="eyebrow"
                        >TASK / {{ taskNumber(task.taskNo) }}</span
                    ><Badge :value="task.status" />
                </div>
                <h1>{{ task.title }}</h1>
                <p>
                    {{
                        task.nodeType === "PARENT"
                            ? "任务组 · 状态由子任务汇总"
                            : "独立任务"
                    }}
                    <span>·</span> {{ task.priority }} <span>·</span>
                    <Badge :value="task.scheduleStatus" />
                </p>
            </div>
            <div class="row wrap">
                <button
                    class="icon-btn"
                    aria-label="刷新任务详情"
                    :disabled="loading"
                    @click="load()"
                >
                    <RefreshCw :size="18" /></button
                ><button
                    v-if="!['DONE', 'CANCELED'].includes(task.status)"
                    class="btn"
                    @click="editing = true"
                >
                    <Pencil :size="16" />编辑任务</button
                ><button
                    v-if="task.status === 'DRAFT'"
                    class="btn primary"
                    :disabled="busy"
                    @click="openAction('SUBMIT')"
                >
                    <Sparkles :size="17" />提交 AI 分析</button
                ><button
                    v-if="
                        ['READY', 'IN_PROGRESS', 'BLOCKED'].includes(
                            task.status,
                        )
                    "
                    class="btn primary"
                    :disabled="busy"
                    @click="simulate"
                >
                    <CalendarPlus :size="17" />模拟排期
                </button>
            </div>
        </div>
        <div class="detail-metrics">
            <div>
                <span>负责人</span
                ><strong>{{
                    state.members.find((m) => m.memberId === task?.assigneeId)
                        ?.username || "等待分配"
                }}</strong>
            </div>
            <div>
                <span>预估工时</span
                ><strong>{{ minutes(task.estimatedMinutes) }}</strong>
            </div>
            <div>
                <span>剩余工时</span
                ><strong>{{ minutes(task.remainingMinutes) }}</strong>
            </div>
            <div>
                <span>截止日期</span
                ><strong
                    >{{ task.dueDate || "未设置" }}
                    <small v-if="task.dueType === 'HARD'">硬截止</small></strong
                >
            </div>
        </div>
        <div class="tabs" role="tablist">
            <button
                v-for="item in [
                    { id: 'overview', name: '任务详情' },
                    { id: 'ai', name: 'AI 分析建议' },
                    { id: 'activity', name: '执行记录' },
                ]"
                :key="item.id"
                role="tab"
                :aria-selected="tab === item.id"
                :class="{ active: tab === item.id }"
                @click="tab = item.id"
            >
                {{ item.name
                }}<span
                    v-if="item.id === 'ai' && processing"
                    class="status-dot running"
                ></span>
            </button>
        </div>
        <div v-if="tab === 'overview'" class="detail-grid">
            <section class="panel padded">
                <h2>需求描述</h2>
                <p class="prose">
                    {{ task.description || "尚未填写需求描述。" }}
                </p>
                <h2>验收标准</h2>
                <p class="prose">
                    {{ task.acceptanceCriteria || "尚未填写验收标准。" }}
                </p>
                <h2>技能要求</h2>
                <div class="row wrap">
                    <span
                        v-for="s in task.skills"
                        :key="s.skillCode"
                        class="skill-tag"
                        >{{
                            state.skills.find((k) => k.code === s.skillCode)
                                ?.name || s.skillCode
                        }}
                        · Lv.{{ s.minLevel }}</span
                    ><span v-if="!task.skills.length" class="muted"
                        >暂无技能要求</span
                    >
                </div>
                <template v-if="task.children.length"
                    ><h2>拆分后的子任务</h2>
                    <RouterLink
                        v-for="child in task.children"
                        :key="child.id"
                        :to="'/tasks/' + child.id"
                        class="child-task"
                        ><span>{{ child.title }}</span
                        ><Badge :value="child.status" /><ChevronRight
                            :size="16" /></RouterLink
                ></template>
                <div class="section-label">
                    <h2>前置任务</h2>
                    <button
                        v-if="canManageProject"
                        class="btn small"
                        @click="openAction('DEPENDENCIES')"
                    >
                        设置依赖
                    </button>
                </div>
                <RouterLink
                    v-for="p in task.predecessors"
                    :key="p.taskId"
                    class="child-task"
                    :to="'/tasks/' + p.taskId"
                    >{{ p.title }}<Badge :value="p.status"
                /></RouterLink>
                <p v-if="!task.predecessors.length" class="muted">
                    没有前置依赖，可以独立安排。
                </p>
            </section>
            <aside class="panel padded action-panel">
                <h2>推进任务</h2>
                <p class="muted">按实际进展更新，容量将随任务状态同步。</p>
                <button
                    v-for="a in actions"
                    :key="a.id"
                    class="btn full"
                    :disabled="busy"
                    @click="openAction(a.id)"
                >
                    {{ a.name }}<ChevronRight :size="16" /></button
                ><button
                    v-if="
                        canExecute &&
                        task.nodeType === 'LEAF' &&
                        task.status === 'IN_PROGRESS'
                    "
                    class="btn full"
                    @click="openAction('LOG')"
                >
                    <Clock3 :size="16" />登记工时</button
                ><template v-if="canManageProject"
                    ><button
                        v-if="task.status === 'IN_PROGRESS'"
                        class="btn full"
                        @click="openAction('REMAINING')"
                    >
                        更新剩余工时</button
                    ><button class="btn full" @click="openAction('LOCK')">
                        <LockKeyhole :size="16" />设置排期锁定</button
                    ><button
                        v-if="task.status === 'DRAFT'"
                        class="btn text danger-text full"
                        @click="openAction('DELETE')"
                    >
                        删除草稿</button
                    ><button
                        v-else-if="!['DONE', 'CANCELED'].includes(task.status)"
                        class="btn text danger-text full"
                        @click="openAction('CANCEL')"
                    >
                        取消任务
                    </button></template
                >
                <p
                    v-if="task.blockedReason || task.cancelReason"
                    class="alert warning"
                >
                    {{ task.blockedReason || task.cancelReason }}
                </p>
                <div class="info-note">
                    开工需要有效的当日分配，且所有前置任务均已验收。
                </div>
            </aside>
        </div>
        <section v-if="tab === 'ai'" class="panel padded">
            <div class="panel-head flush">
                <div class="row">
                    <span class="ai-icon"><Sparkles :size="21" /></span>
                    <div>
                        <h2>AI 分析建议</h2>
                        <p class="muted">辅助估算与拆分，最终安排由你确认。</p>
                    </div>
                </div>
                <button
                    v-if="['SUBMITTED', 'NEEDS_INFO'].includes(task.status)"
                    class="btn"
                    :disabled="processing"
                    @click="openAction('REANALYZE')"
                >
                    重新分析
                </button>
            </div>
            <Empty
                v-if="!runs.length"
                title="尚未生成分析建议"
                description="提交任务后，AI 会根据描述与团队技能进行分析。"
            /><template v-else
                ><div class="toolbar compact">
                    <select v-model="selectedRun" aria-label="选择分析记录">
                        <option v-for="r in runs" :key="r.id" :value="r.id">
                            {{
                                new Date(r.createdAt).toLocaleString("zh-CN")
                            }}
                            · {{ label(r.status) }}
                        </option></select
                    ><Badge v-if="run" :value="run.status" /><span
                        class="muted"
                        >{{ run?.model }}</span
                    >
                </div>
                <div
                    v-if="run && ['PENDING', 'RUNNING'].includes(run.status)"
                    class="analysis-wait"
                >
                    <span class="spinner"></span>
                    <h3>正在理解需求、估算工时</h3>
                    <p>结果会自动更新，你也可以先处理其他任务。</p>
                </div>
                <div v-if="run?.errorCode" class="alert error" role="alert">
                    {{
                        run.errorDetail ||
                        "分析未成功，请检查需求或联系管理员。"
                    }}<small>{{ run.errorCode }}</small>
                </div>
                <template v-if="output"
                    ><h3>{{ output.summary }}</h3>
                    <div class="estimate-row">
                        <div>
                            <span>乐观估算</span
                            ><strong>{{
                                minutes(output.estimate.optimisticMinutes)
                            }}</strong>
                        </div>
                        <div>
                            <span>最可能估算</span
                            ><strong>{{
                                minutes(output.estimate.likelyMinutes)
                            }}</strong>
                        </div>
                        <div>
                            <span>悲观估算</span
                            ><strong>{{
                                minutes(output.estimate.pessimisticMinutes)
                            }}</strong>
                        </div>
                    </div>
                    <p class="prose muted">{{ output.estimate.basis }}</p>
                    <h3>验收标准</h3>
                    <ul class="clean-list">
                        <li
                            v-for="text in output.acceptanceCriteria"
                            :key="text"
                        >
                            <Check :size="15" />{{ text }}
                        </li>
                    </ul>
                    <div
                        v-if="output.missingInformation.length"
                        class="alert warning"
                    >
                        <strong>需要补充信息</strong>
                        <ul>
                            <li v-for="q in output.missingInformation" :key="q">
                                {{ q }}
                            </li>
                        </ul>
                        <p>编辑任务补充上述信息后，再重新分析。</p>
                    </div>
                    <h3 v-if="output.subtasks.length">
                        建议拆分 · {{ output.subtasks.length }} 项
                    </h3>
                    <div
                        v-for="s in output.subtasks"
                        :key="s.clientKey"
                        class="subtask-suggestion"
                    >
                        <span class="task-mark">{{ s.clientKey }}</span>
                        <div>
                            <strong>{{ s.title }}</strong>
                            <p>{{ s.scope }}</p>
                            <small
                                >{{ minutes(s.estimate.likelyMinutes) }} ·
                                {{ s.deliverables.join("、") }}</small
                            >
                        </div>
                    </div>
                    <div
                        v-if="output.dependencies.length"
                        class="dependency-flow"
                    >
                        <span v-for="(d, i) in output.dependencies" :key="i"
                            >{{ d.from }} → {{ d.to }}
                            <small>完成后开始</small></span
                        >
                    </div>
                    <div class="assumption-box">
                        <h3>关键假设与人工确认</h3>
                        <ul v-if="output.assumptions.length">
                            <li v-for="a in output.assumptions" :key="a.text">
                                {{ a.text }}
                            </li>
                        </ul>
                        <p v-else>
                            本次分析未提出额外假设。仍请核对工时与交付范围。
                        </p>
                        <label class="check-label"
                            ><input
                                v-model="confirmed"
                                type="checkbox"
                            />我已核对建议，并确认上述关键假设</label
                        ><button
                            v-if="
                                canManageProject &&
                                ['SUBMITTED', 'NEEDS_INFO'].includes(
                                    task.status,
                                )
                            "
                            class="btn primary"
                            :disabled="
                                !confirmed ||
                                output.missingInformation.length > 0 ||
                                busy
                            "
                            @click="openAction('CONFIRM')"
                        >
                            <Check :size="16" />确认建议并模拟排期
                        </button>
                        <p class="helper">
                            确认后会建立任务与依赖，并生成模拟方案；提交方案后才占用成员容量。
                        </p>
                    </div></template
                ></template
            >
        </section>
        <section v-if="tab === 'activity'" class="panel padded">
            <div class="section-label">
                <h2>实际工时 · {{ minutes(task.actualMinutes) }}</h2>
                <button
                    v-if="canExecute && task.status === 'IN_PROGRESS'"
                    class="btn small"
                    @click="openAction('LOG')"
                >
                    <Plus :size="15" />登记工时
                </button>
            </div>
            <div v-for="(log, i) in task.workLogs" :key="i" class="record-row">
                <strong>{{ log.workDate }}</strong
                ><span>{{ minutes(log.minutes) }}</span
                ><span>{{ log.note || "无备注" }}</span>
            </div>
            <p v-if="!task.workLogs.length" class="muted">暂无工时记录。</p>
            <h2>状态变化</h2>
            <div v-for="(e, i) in task.events" :key="i" class="record-row">
                <span class="muted">{{
                    new Date(e.createdAt).toLocaleString("zh-CN")
                }}</span
                ><span
                    >{{ label(e.fromStatus || "DRAFT") }} →
                    {{ label(e.toStatus) }}</span
                ><span>{{ e.reason }}</span>
            </div>
            <p v-if="!task.events.length" class="muted">
                任务尚未发生状态变化。
            </p>
        </section></template
    ><TaskEditor
        v-if="editing && task"
        :task="task"
        @close="editing = false"
        @saved="edited"
    /><Modal
        v-if="action"
        :title="actionTitle"
        :busy="busy"
        @close="action = ''"
        ><form @submit.prevent="perform">
            <div v-if="modalError" class="alert error" role="alert">
                {{ modalError
                }}<span v-if="modalError.includes('版本')"
                    >请关闭弹窗并刷新详情后重试。</span
                >
            </div>
            <p v-if="['SUBMIT', 'REANALYZE'].includes(action)" class="prose">
                任务描述会发送给已配置的模型，并使用 API
                额度。分析结果需要人工确认。
            </p>
            <p v-if="action === 'CONFIRM'" class="prose">
                采用当前建议建立任务及依赖，并生成排期方案。请在方案页面检查具体成员、日期和冲突后再提交。
            </p>
            <p v-if="action === 'DELETE'" class="prose">
                删除草稿“{{ task?.title }}”？此操作不可恢复。
            </p>
            <p v-if="action === 'CANCEL'" class="prose">
                取消后将释放可移动的未来容量；任务组的未完成子任务也将取消。
            </p>
            <p v-if="action === 'MARK_READY'" class="prose">
                按当前人工估算
                {{
                    minutes(task?.estimatedMinutes)
                }}
                将任务标记为待开始；此操作不会自动分配成员。
            </p>
            <template
                v-if="['LOG', 'REMAINING', 'RETURN', 'REOPEN'].includes(action)"
                ><label
                    >{{
                        action === "LOG" ? "实际工时" : "剩余工时"
                    }}（分钟）<input
                        v-model.number="amount"
                        type="number"
                        min="1"
                        step="1"
                        required /></label
                ><label v-if="['LOG', 'REMAINING'].includes(action)"
                    >{{ action === "LOG" ? "工作日期" : "剩余工时生效基准日"
                    }}<input
                        v-model="workDate"
                        type="date"
                        required /></label></template
            ><label v-if="action === 'LOCK'"
                >锁定设置<select v-model="lock">
                    <option :value="true">锁定，保留当前安排</option>
                    <option :value="false">解除锁定，允许重排</option>
                </select></label
            >
            <div v-if="action === 'DEPENDENCIES'">
                <p class="helper">
                    选择必须先验收完成的任务（最多 30 项）。后台会校验环路。
                </p>
                <label
                    v-for="c in candidates.items.filter(
                        (t) => t.id !== task?.id,
                    )"
                    :key="c.id"
                    class="check-label"
                    ><input
                        v-model="predecessors"
                        type="checkbox"
                        :value="c.id"
                    />#{{ c.taskNo }} {{ c.title }}</label
                >
                <p v-if="candidates.nextCursor" class="helper">
                    当前候选显示前 100 项。
                </p>
            </div>
            <label
                v-if="
                    ![
                        'SUBMIT',
                        'REANALYZE',
                        'CONFIRM',
                        'DELETE',
                        'DEPENDENCIES',
                    ].includes(action)
                "
                >操作说明<textarea
                    v-model="reason"
                    rows="3"
                    maxlength="1000"
                    :required="
                        ['CANCEL', 'BLOCK', 'REOPEN', 'LOCK'].includes(action)
                    "
                    placeholder="补充原因或备注"
                ></textarea>
            </label>
            <div class="form-actions">
                <button
                    type="button"
                    class="btn"
                    :disabled="busy"
                    @click="action = ''"
                >
                    返回</button
                ><button
                    class="btn primary"
                    :disabled="busy || (action === 'CONFIRM' && !confirmed)"
                >
                    {{ busy ? "处理中…" : "确认操作" }}
                </button>
            </div>
        </form></Modal
    >
</template>
