<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
    ChevronLeft,
    ChevronRight,
    RefreshCw,
    CalendarDays,
    Check,
    AlertTriangle,
    ArrowUpRight,
} from "lucide-vue-next";
import { api, query } from "../api";
import {
    state,
    isManager,
    me,
    canManageProject,
    errorMessage,
    notify,
} from "../state";
import { minutes, weekStart, addDays, dateString, uid } from "../utils";
import type { Capacity, Allocation, Plan, TaskDetail } from "../types";
import Badge from "../components/Badge.vue";
import Modal from "../components/Modal.vue";
import Empty from "../components/Empty.vue";
const route = useRoute(),
    router = useRouter(),
    start = ref(weekStart()),
    capacities = ref<Record<string, Capacity[]>>({}),
    allocations = ref<Allocation[]>([]),
    names = ref<Record<string, string>>({}),
    loading = ref(false),
    error = ref(""),
    memberFilter = ref(""),
    plan = ref<Plan>(),
    planError = ref(""),
    planBusy = ref(false),
    confirmCommit = ref(false),
    lookup = ref(""),
    selected = ref<{ memberId: string; date: string } | null>(null);
const members = computed(() =>
    state.members.filter(
        (m) =>
            m.status === "ACTIVE" &&
            (isManager.value || m.memberId === me.value?.memberId) &&
            (!memberFilter.value || m.memberId === memberFilter.value),
    ),
);
const days = computed(() =>
    Array.from({ length: 7 }, (_, i) => addDays(start.value, i)),
);
const total = computed(() =>
    Object.values(capacities.value)
        .flat()
        .reduce(
            (a, c) => ({
                capacity: a.capacity + c.capacityMinutes,
                occupied: a.occupied + c.occupiedMinutes,
            }),
            { capacity: 0, occupied: 0 },
        ),
);
const selectedAllocations = computed(() =>
    allocations.value.filter(
        (a) =>
            a.memberId === selected.value?.memberId &&
            a.date === selected.value?.date,
    ),
);
const expired = computed(() =>
    plan.value ? new Date(plan.value.expiresAt).getTime() < Date.now() : false,
);
let alive = true,
    generation = 0,
    timer: ReturnType<typeof setTimeout> | undefined,
    polls = 0,
    commitKey = uid();
function bucket(memberId: string, day: string) {
    return capacities.value[memberId]?.find((c) => c.workDate === day);
}
function entries(memberId: string, day: string) {
    return allocations.value.filter(
        (a) => a.memberId === memberId && a.date === day,
    );
}
async function load() {
    const id = ++generation;
    loading.value = true;
    error.value = "";
    capacities.value = {};
    allocations.value = [];
    try {
        const result = await Promise.all(
            members.value.map(async (m) => {
                const range = query({
                    from: start.value,
                    to: addDays(start.value, 6),
                });
                const [c, a] = await Promise.all([
                    api<Capacity[]>(`/members/${m.memberId}/capacity` + range),
                    api<Allocation[]>(
                        `/schedules/members/${m.memberId}` + range,
                    ),
                ]);
                return { id: m.memberId, c, a };
            }),
        );
        if (!alive || id !== generation) return;
        capacities.value = Object.fromEntries(result.map((r) => [r.id, r.c]));
        allocations.value = result.flatMap((r) => r.a);
        const ids = [...new Set(allocations.value.map((a) => a.taskId))];
        const details = await Promise.all(
            ids.map(async (taskId) => {
                try {
                    return await api<TaskDetail>("/tasks/" + taskId);
                } catch {
                    return null;
                }
            }),
        );
        if (alive && id === generation)
            details.forEach((t) => {
                if (t) names.value[t.id] = t.title;
            });
    } catch (e) {
        if (alive && id === generation) error.value = errorMessage(e);
    } finally {
        if (alive && id === generation) loading.value = false;
    }
}
async function loadPlan() {
    if (!route.query.job && !route.query.plan) return;
    const context = String(route.query.job || route.query.plan);
    planBusy.value = true;
    planError.value = "";
    try {
        const result = await api<Plan>(
            route.query.job
                ? "/schedule-simulations/" +
                      encodeURIComponent(String(route.query.job))
                : "/schedule-plans/" +
                      encodeURIComponent(String(route.query.plan)),
        );
        if (!alive || context !== String(route.query.job || route.query.plan))
            return;
        plan.value = result;
        result.output?.tasks.forEach((t) => (names.value[t.id] = t.title));
        clearTimeout(timer);
        if (result.status === "GENERATING") {
            if (++polls < 45) timer = setTimeout(loadPlan, 3000);
            else
                planError.value =
                    "方案仍在生成，请稍后刷新查看。暂未占用容量。";
        }
    } catch (e) {
        planError.value = errorMessage(e);
    } finally {
        if (alive) planBusy.value = false;
    }
}
async function commit() {
    if (!plan.value) return;
    planBusy.value = true;
    planError.value = "";
    try {
        await api(`/schedule-plans/${plan.value.id}/commit`, {
            method: "POST",
            key: commitKey,
            body: { version: plan.value.version },
        });
        confirmCommit.value = false;
        notify("排期已提交，成员容量已更新");
        await loadPlan();
        await load();
    } catch (e) {
        planError.value = errorMessage(e);
        confirmCommit.value = false;
    } finally {
        planBusy.value = false;
    }
}
function openLookup() {
    if (lookup.value.trim())
        router.push(
            "/schedule?plan=" + encodeURIComponent(lookup.value.trim()),
        );
}
watch([start, memberFilter], load);
watch(
    () => [route.query.job, route.query.plan],
    () => {
        plan.value = undefined;
        polls = 0;
        commitKey = uid();
        clearTimeout(timer);
        loadPlan();
    },
);
onMounted(() => {
    load();
    loadPlan();
});
onBeforeUnmount(() => {
    alive = false;
    clearTimeout(timer);
});
</script>
<template>
    <div class="page-heading">
        <div>
            <span class="eyebrow">TEAM SCHEDULE</span>
            <h1>成员排期<span class="heading-dot">.</span></h1>
            <p>看见每个人的工作节奏，让安排始终有余地。</p>
        </div>
        <RouterLink to="/tasks" class="btn primary"
            ><CalendarDays :size="17" />选择任务并排期</RouterLink
        >
    </div>
    <section
        v-if="route.query.job || route.query.plan"
        class="panel plan-panel"
    >
        <div class="panel-head">
            <div>
                <div class="row">
                    <h2>排期方案</h2>
                    <Badge v-if="plan" :value="plan.status" />
                </div>
                <span>模拟阶段不占用容量，提交后才会分配任务。</span>
            </div>
            <div class="row">
                <button
                    class="btn small"
                    :disabled="planBusy"
                    @click="
                        polls = 0;
                        loadPlan();
                    "
                >
                    <RefreshCw :size="15" />刷新方案</button
                ><button
                    v-if="plan?.status === 'READY' && canManageProject"
                    class="btn primary"
                    :disabled="planBusy || expired"
                    @click="confirmCommit = true"
                >
                    <Check :size="17" />提交排期
                </button>
            </div>
        </div>
        <div v-if="planError" class="alert error" role="alert">
            {{ planError }}
        </div>
        <div v-if="expired && plan?.status === 'READY'" class="alert warning">
            方案已过期，请从任务中心重新发起模拟。
        </div>
        <div v-if="!plan || plan.status === 'GENERATING'" class="loading">
            <span class="spinner"></span>正在校验技能、容量与任务依赖…
        </div>
        <template v-if="plan?.output"
            ><div v-if="plan.output.conflicts.length" class="conflict-list">
                <div
                    v-for="(c, i) in plan.output.conflicts"
                    :key="i"
                    class="conflict-item"
                >
                    <AlertTriangle :size="20" />
                    <div>
                        <strong>{{ names[c.taskId] || "排期约束冲突" }}</strong>
                        <p>{{ c.message }}</p>
                        <small>{{ c.code }}</small>
                    </div>
                    <RouterLink
                        v-if="c.taskId"
                        :to="'/tasks/' + c.taskId"
                        class="btn small"
                        >查看任务</RouterLink
                    >
                </div>
            </div>
            <div v-if="plan.output.fragments.length" class="table-scroll">
                <table>
                    <thead>
                        <tr>
                            <th>任务</th>
                            <th>分配成员</th>
                            <th>日期</th>
                            <th>安排工时</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="(f, i) in plan.output.fragments" :key="i">
                            <td>
                                <RouterLink :to="'/tasks/' + f.taskId">{{
                                    names[f.taskId] || "查看任务"
                                }}</RouterLink>
                            </td>
                            <td>
                                {{
                                    state.members.find(
                                        (m) => m.memberId === f.memberId,
                                    )?.username || "成员"
                                }}
                            </td>
                            <td>{{ f.date }}</td>
                            <td>{{ minutes(f.minutes) }}</td>
                        </tr>
                    </tbody>
                </table>
            </div>
            <div class="table-footer">
                <span
                    >方案版本 {{ plan.version }} ·
                    {{
                        plan.status === "COMMITTED"
                            ? "已生效"
                            : "有效期至 " +
                              new Date(plan.expiresAt).toLocaleTimeString(
                                  "zh-CN",
                              )
                    }}</span
                ><RouterLink to="/schedule" class="text-link"
                    >收起方案</RouterLink
                >
            </div></template
        >
    </section>
    <div class="schedule-summary">
        <div>
            <CalendarDays :size="23" /><span
                >本周总容量<strong>{{
                    loading ? "—" : minutes(total.capacity)
                }}</strong></span
            >
        </div>
        <div>
            <span class="summary-dot"></span
            ><span
                >已安排<strong>{{
                    loading ? "—" : minutes(total.occupied)
                }}</strong></span
            >
        </div>
        <div>
            <span class="summary-dot pale"></span
            ><span
                >可用容量<strong>{{
                    loading
                        ? "—"
                        : minutes(Math.max(0, total.capacity - total.occupied))
                }}</strong></span
            >
        </div>
        <div class="muted">
            {{ isManager ? "所选成员的全公司容量" : "我的容量"
            }}<br />仅展示有权访问的任务
        </div>
    </div>
    <section class="panel">
        <div class="toolbar schedule-toolbar">
            <div class="row">
                <button
                    class="icon-btn"
                    aria-label="上一周"
                    @click="start = addDays(start, -7)"
                >
                    <ChevronLeft :size="18" /></button
                ><strong>{{ start }} — {{ addDays(start, 6).slice(5) }}</strong
                ><button
                    class="icon-btn"
                    aria-label="下一周"
                    @click="start = addDays(start, 7)"
                >
                    <ChevronRight :size="18" /></button
                ><button class="btn small" @click="start = weekStart()">
                    本周
                </button>
            </div>
            <div class="row">
                <select v-model="memberFilter" aria-label="筛选成员">
                    <option value="">
                        {{ isManager ? "全部在职成员" : "我的排期" }}
                    </option>
                    <option
                        v-for="m in state.members.filter(
                            (m) =>
                                m.status === 'ACTIVE' &&
                                (isManager || m.memberId === me?.memberId),
                        )"
                        :key="m.memberId"
                        :value="m.memberId"
                    >
                        {{ m.username }}
                    </option></select
                ><button
                    class="icon-btn"
                    aria-label="刷新排期"
                    :disabled="loading"
                    @click="load"
                >
                    <RefreshCw :size="18" :class="{ spin: loading }" />
                </button>
            </div>
        </div>
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <div v-if="loading" class="loading">正在读取成员容量与任务安排…</div>
        <Empty
            v-else-if="!members.length"
            title="暂无可查看的成员"
            description="先在团队与技能中创建成员，并配置每日容量。"
        />
        <div v-else class="calendar-scroll">
            <table class="calendar-table">
                <thead>
                    <tr>
                        <th>成员 / 每日负载</th>
                        <th
                            v-for="day in days"
                            :key="day"
                            :class="{ today: day === dateString() }"
                        >
                            <span>{{
                                [
                                    "周日",
                                    "周一",
                                    "周二",
                                    "周三",
                                    "周四",
                                    "周五",
                                    "周六",
                                ][new Date(day + "T12:00:00").getDay()]
                            }}</span
                            ><strong>{{ day.slice(8) }}</strong
                            ><small v-if="day === dateString()">今天</small>
                        </th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="m in members" :key="m.memberId">
                        <td>
                            <span class="avatar">{{
                                m.username.slice(0, 1).toUpperCase()
                            }}</span
                            ><strong>{{ m.username }}</strong
                            ><small>{{
                                isManager ? "团队成员" : "我的安排"
                            }}</small>
                        </td>
                        <td
                            v-for="day in days"
                            :key="day"
                            :class="{
                                'non-workday': !bucket(m.memberId, day)
                                    ?.workday,
                                today: day === dateString(),
                            }"
                        >
                            <button
                                class="day-cell"
                                :aria-label="m.username + ' ' + day + ' 的排期'"
                                @click="
                                    selected = {
                                        memberId: m.memberId,
                                        date: day,
                                    }
                                "
                            >
                                <template v-if="bucket(m.memberId, day)"
                                    ><div class="day-load">
                                        <strong
                                            :class="{
                                                'danger-text':
                                                    bucket(m.memberId, day)!
                                                        .remainingMinutes ===
                                                        0 &&
                                                    bucket(m.memberId, day)!
                                                        .capacityMinutes > 0,
                                            }"
                                            >{{
                                                minutes(
                                                    bucket(m.memberId, day)!
                                                        .occupiedMinutes,
                                                )
                                            }}</strong
                                        ><span
                                            >/
                                            {{
                                                minutes(
                                                    bucket(m.memberId, day)!
                                                        .capacityMinutes,
                                                )
                                            }}</span
                                        >
                                    </div>
                                    <div class="meter">
                                        <i
                                            :class="{
                                                full:
                                                    bucket(m.memberId, day)!
                                                        .remainingMinutes === 0,
                                            }"
                                            :style="{
                                                width:
                                                    Math.min(
                                                        100,
                                                        bucket(m.memberId, day)!
                                                            .capacityMinutes
                                                            ? (bucket(
                                                                  m.memberId,
                                                                  day,
                                                              )!
                                                                  .occupiedMinutes /
                                                                  bucket(
                                                                      m.memberId,
                                                                      day,
                                                                  )!
                                                                      .capacityMinutes) *
                                                                  100
                                                            : 0,
                                                    ) + '%',
                                            }"
                                        ></i>
                                    </div>
                                    <span
                                        v-for="a in entries(
                                            m.memberId,
                                            day,
                                        ).slice(0, 2)"
                                        :key="a.taskId + a.occupancyState"
                                        class="allocation-chip"
                                        >{{ names[a.taskId] || "任务"
                                        }}<small>{{
                                            minutes(a.minutes)
                                        }}</small></span
                                    ><span
                                        v-if="
                                            entries(m.memberId, day).length > 2
                                        "
                                        class="muted"
                                        >另
                                        {{
                                            entries(m.memberId, day).length - 2
                                        }}
                                        项</span
                                    ><span
                                        v-if="!entries(m.memberId, day).length"
                                        class="day-empty"
                                        >{{
                                            bucket(m.memberId, day)!.workday
                                                ? bucket(m.memberId, day)!
                                                      .capacityMinutes
                                                    ? "等待安排"
                                                    : "无可用容量"
                                                : "休息日"
                                        }}</span
                                    ></template
                                ><span v-else>暂无数据</span>
                            </button>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="table-footer">
            <span
                ><i class="legend teal"></i>已安排
                <i class="legend amber"></i>容量已满
                <i class="legend grey"></i>非工作日</span
            ><span>点击日期格查看任务明细</span>
        </div>
    </section>
    <form class="plan-lookup" @submit.prevent="openLookup">
        <label for="plan-id">打开已有方案</label
        ><input
            id="plan-id"
            v-model="lookup"
            placeholder="粘贴方案 ID"
        /><button class="btn small" :disabled="!lookup.trim()">查看</button>
    </form>
    <Modal
        v-if="confirmCommit"
        title="确认提交排期"
        :busy="planBusy"
        @close="confirmCommit = false"
        ><p class="prose">
            将按上方方案分配成员并占用每日容量。系统会再次检查权限、任务版本和容量；如输入已变化，本次提交将被拒绝。
        </p>
        <div class="form-actions">
            <button
                class="btn"
                :disabled="planBusy"
                @click="confirmCommit = false"
            >
                返回检查</button
            ><button class="btn primary" :disabled="planBusy" @click="commit">
                {{ planBusy ? "提交中…" : "确认提交" }}
            </button>
        </div></Modal
    ><Modal
        v-if="selected"
        :title="
            (state.members.find((m) => m.memberId === selected?.memberId)
                ?.username || '成员') +
            ' · ' +
            selected.date
        "
        @close="selected = null"
        ><div class="capacity-detail">
            <span
                >容量
                {{
                    minutes(
                        bucket(selected.memberId, selected.date)
                            ?.capacityMinutes,
                    )
                }}</span
            ><span
                >剩余
                {{
                    minutes(
                        bucket(selected.memberId, selected.date)
                            ?.remainingMinutes,
                    )
                }}</span
            >
        </div>
        <RouterLink
            v-for="a in selectedAllocations"
            :key="a.taskId + a.occupancyState"
            class="child-task"
            :to="'/tasks/' + a.taskId"
            ><div>
                <strong>{{ names[a.taskId] || "查看任务" }}</strong
                ><small
                    >{{ minutes(a.minutes) }} ·
                    {{
                        a.occupancyState === "RETAINED"
                            ? "历史保留"
                            : a.executionStarted
                              ? "已开始"
                              : "已分配"
                    }}</small
                >
            </div>
            <ArrowUpRight :size="17"
        /></RouterLink>
        <p v-if="!selectedAllocations.length" class="muted">
            当天没有可查看的任务。容量统计可能包含其他项目的安排。
        </p></Modal
    >
</template>
