<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import {
    Plus,
    Users,
    ShieldCheck,
    Settings2,
    RefreshCw,
} from "lucide-vue-next";
import { api, query } from "../api";
import { state, isAdmin, isManager, me, errorMessage, notify } from "../state";
import { label, minutes, dateString } from "../utils";
import type { Member, Skill, MemberSkill, Capacity } from "../types";
import Modal from "../components/Modal.vue";
import Badge from "../components/Badge.vue";
import Empty from "../components/Empty.vue";
const mode = ref("members"),
    modal = ref(""),
    selected = ref<Member>(),
    memberSkills = ref<MemberSkill[]>([]),
    levels = ref<Record<string, number>>({}),
    busy = ref(false),
    error = ref(""),
    pageError = ref(""),
    username = ref(""),
    role = ref("MEMBER"),
    code = ref(""),
    skillName = ref(""),
    description = ref(""),
    standard = ref(480),
    limit = ref(360),
    date = ref(dateString()),
    leave = ref(0),
    workday = ref(true),
    reason = ref(""),
    temp = ref(""),
    createdName = ref(""),
    capacity = ref<Record<string, Capacity>>({});
const canReadSelected = computed(
    () => isManager.value || selected.value?.memberId === me.value?.memberId,
);
async function refresh() {
    pageError.value = "";
    try {
        const [members, skills] = await Promise.all([
            api<Member[]>("/members"),
            api<Skill[]>("/skills"),
        ]);
        state.members = members;
        state.skills = skills;
        const today = dateString();
        const rows = await Promise.all(
            members
                .filter(
                    (m) => isManager.value || m.memberId === me.value?.memberId,
                )
                .map(async (m) => {
                    const days = await api<Capacity[]>(
                        `/members/${m.memberId}/capacity` +
                            query({ from: today, to: today }),
                    );
                    return [m.memberId, days[0]] as const;
                }),
        );
        capacity.value = Object.fromEntries(rows);
    } catch (e) {
        pageError.value = errorMessage(e);
    }
}
async function open(name: string, member?: Member) {
    modal.value = name;
    selected.value = member;
    error.value = "";
    reason.value = "";
    temp.value = "";
    memberSkills.value = [];
    levels.value = {};
    username.value = "";
    role.value = member?.role || "MEMBER";
    if (name === "skill") {
        code.value = "";
        skillName.value = "";
        description.value = "";
    }
    if (member && name === "settings") {
        busy.value = true;
        try {
            const result = await api<MemberSkill[]>(
                `/members/${member.memberId}/skills`,
            );
            memberSkills.value = result;
            levels.value = Object.fromEntries(
                result
                    .filter((s) => !s.selfReported)
                    .map((s) => [s.skillId, s.level]),
            );
            standard.value =
                capacity.value[member.memberId]?.standardMinutes ?? 480;
            limit.value =
                capacity.value[member.memberId]?.dailyLimitMinutes ?? 360;
        } catch (e) {
            error.value = errorMessage(e);
        } finally {
            busy.value = false;
        }
    }
}
async function save(kind = modal.value) {
    busy.value = true;
    error.value = "";
    try {
        if (kind === "member") {
            const result = await api<{
                username: string;
                temporaryPassword: string;
            }>("/members", {
                method: "POST",
                body: { username: username.value, role: role.value },
            });
            temp.value = result.temporaryPassword;
            createdName.value = result.username;
            modal.value = "credentials";
        } else if (kind === "skill") {
            await api("/skills", {
                method: "POST",
                body: {
                    code: code.value,
                    name: skillName.value,
                    description: description.value,
                },
            });
            modal.value = "";
        } else if (kind === "capacity") {
            await api(`/members/${selected.value!.memberId}/capacity-policy`, {
                method: "PUT",
                body: {
                    standardMinutes: standard.value,
                    dailyLimitMinutes: limit.value,
                },
            });
            notify("每日容量已更新");
        } else if (kind === "skills") {
            await api(`/members/${selected.value!.memberId}/skills`, {
                method: "PUT",
                body: {
                    items: Object.entries(levels.value)
                        .filter(([, v]) => v > 0)
                        .map(([skillId, level]) => ({
                            skillId,
                            level: Number(level),
                        })),
                },
            });
            notify("认证技能已更新");
        } else if (kind === "calendar") {
            await api(`/members/${selected.value!.memberId}/calendar`, {
                method: "PUT",
                body: {
                    overrides: [
                        {
                            workDate: date.value,
                            workdayFlag: workday.value,
                            leaveMinutes: leave.value,
                            extraUnavailableMinutes: 0,
                            reason: reason.value,
                        },
                    ],
                },
            });
            modal.value = "";
            notify("日历例外已保存");
        } else if (kind === "role") {
            await api(`/members/${selected.value!.memberId}/role`, {
                method: "PUT",
                version: selected.value!.version,
                body: { role: role.value, reason: reason.value },
            });
            modal.value = "";
        } else if (kind === "disable") {
            await api(
                `/members/${selected.value!.memberId}/${selected.value!.status === "ACTIVE" ? "disable" : "enable"}`,
                {
                    method: "POST",
                    ...(selected.value!.status === "ACTIVE"
                        ? { body: { reason: reason.value } }
                        : {}),
                },
            );
            modal.value = "";
        }
        await refresh();
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        busy.value = false;
    }
}
onMounted(refresh);
</script>
<template>
    <div class="page-heading">
        <div>
            <span class="eyebrow">PEOPLE & CAPABILITIES</span>
            <h1>团队与技能<span class="heading-dot">.</span></h1>
            <p>了解团队的能力，也为每个人保留合理的工作空间。</p>
        </div>
        <button
            v-if="isAdmin"
            class="btn primary"
            @click="open(mode === 'members' ? 'member' : 'skill')"
        >
            <Plus :size="18" />{{
                mode === "members" ? "添加成员" : "新增技能"
            }}
        </button>
    </div>
    <div class="tabs">
        <button
            :class="{ active: mode === 'members' }"
            @click="mode = 'members'"
        >
            团队成员
            <span class="count">{{ state.members.length }}</span></button
        ><button
            :class="{ active: mode === 'skills' }"
            @click="mode = 'skills'"
        >
            技能目录 <span class="count">{{ state.skills.length }}</span>
        </button>
    </div>
    <div v-if="pageError" class="alert error" role="alert">
        {{ pageError }}<button class="btn small" @click="refresh">重试</button>
    </div>
    <div v-if="mode === 'members'" class="team-grid">
        <article
            v-for="m in state.members"
            :key="m.memberId"
            class="panel member-card"
        >
            <div class="member-card-head">
                <span class="avatar large">{{
                    m.username.slice(0, 1).toUpperCase()
                }}</span
                ><Badge :value="m.status" />
            </div>
            <h2>
                {{ m.username
                }}<small v-if="m.memberId === me?.memberId">你</small>
            </h2>
            <p class="muted">{{ label(m.role) }}</p>
            <div class="member-capacity">
                <span
                    >每日任务上限<strong>{{
                        capacity[m.memberId]
                            ? minutes(capacity[m.memberId]!.dailyLimitMinutes)
                            : "仅本人 / 管理人员可见"
                    }}</strong></span
                ><span
                    >今日剩余<strong>{{
                        capacity[m.memberId]
                            ? minutes(capacity[m.memberId]!.remainingMinutes)
                            : "—"
                    }}</strong></span
                >
            </div>
            <div class="row wrap">
                <button
                    v-if="isManager || m.memberId === me?.memberId"
                    class="btn small"
                    @click="open('settings', m)"
                >
                    <Settings2 :size="15" />技能与容量</button
                ><button
                    v-if="isAdmin"
                    class="btn small"
                    @click="open('calendar', m)"
                >
                    日历例外</button
                ><button
                    v-if="isAdmin"
                    class="btn small text"
                    @click="open('role', m)"
                >
                    角色</button
                ><button
                    v-if="isAdmin && m.memberId !== me?.memberId"
                    class="btn small text"
                    @click="open('disable', m)"
                >
                    {{ m.status === "ACTIVE" ? "停用" : "启用" }}
                </button>
            </div>
        </article>
    </div>
    <section v-else class="panel">
        <div class="panel-head">
            <div>
                <h2>团队能力目录</h2>
                <span>成员经认证的技能才会用于自动匹配，等级范围为 1–5。</span>
            </div>
            <ShieldCheck :size="22" />
        </div>
        <Empty
            v-if="!state.skills.length"
            title="建立团队的第一项技能"
            description="例如 Java 开发、产品设计、软件测试。技能代码将作为 AI 分析的候选范围。"
            ><button v-if="isAdmin" class="btn" @click="open('skill')">
                新增技能
            </button></Empty
        >
        <div v-else class="table-scroll">
            <table>
                <thead>
                    <tr>
                        <th>技能名称</th>
                        <th>代码</th>
                        <th>说明</th>
                        <th>状态</th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="s in state.skills" :key="s.skillId">
                        <td>
                            <strong>{{ s.name }}</strong>
                        </td>
                        <td>
                            <code>{{ s.code }}</code>
                        </td>
                        <td>{{ s.description || "—" }}</td>
                        <td>
                            <Badge :value="s.active ? 'ACTIVE' : 'INACTIVE'" />
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
    </section>
    <div class="info-note">
        <Users
            :size="18"
        />公司成员还需加入对应项目，才能参与该项目的任务分配。<RouterLink
            to="/projects"
            >配置项目成员 →</RouterLink
        >
    </div>
    <Modal
        v-if="modal"
        :title="
            {
                member: '添加团队成员',
                skill: '新增技能',
                settings: (selected?.username || '成员') + ' · 技能与容量',
                calendar: '设置日历例外',
                credentials: '成员账号已创建',
                role: '调整公司角色',
                disable:
                    selected?.status === 'ACTIVE' ? '停用成员' : '启用成员',
            }[modal] || '成员设置'
        "
        :busy="busy"
        @close="
            modal = '';
            temp = '';
        "
        :wide="modal === 'settings'"
        ><div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <template v-if="modal === 'credentials'"
            ><p>
                账号
                <strong>{{ createdName }}</strong>
                已创建。临时密码仅在这里展示，请通过安全方式交给该成员；首次登录需要修改密码。
            </p>
            <div class="credential">
                <code>{{ temp }}</code>
            </div>
            <button
                class="btn primary full"
                @click="
                    modal = '';
                    temp = '';
                "
            >
                已记录，关闭
            </button></template
        ><template v-else-if="modal === 'settings'"
            ><div v-if="busy" class="loading">正在处理…</div>
            <form @submit.prevent="save('capacity')">
                <h3>每日容量</h3>
                <div class="form-grid">
                    <label
                        >标准工作时长（分钟）<input
                            v-model.number="standard"
                            type="number"
                            min="0"
                            max="1440"
                            required
                            :disabled="!isAdmin || busy" /></label
                    ><label
                        >每日任务上限（分钟）<input
                            v-model.number="limit"
                            type="number"
                            min="0"
                            max="1440"
                            required
                            :disabled="!isAdmin || busy"
                    /></label>
                </div>
                <p class="helper">
                    实际容量还会扣除请假等不可用时间。降低容量不能突破已存在的占用约束。
                </p>
                <button v-if="isAdmin" class="btn" :disabled="busy">
                    保存容量
                </button>
            </form>
            <hr />
            <form @submit.prevent="save('skills')">
                <h3>认证技能</h3>
                <p
                    v-if="!state.skills.filter((s) => s.active).length"
                    class="muted"
                >
                    请先在技能目录中创建技能。
                </p>
                <label
                    v-for="s in state.skills.filter((s) => s.active)"
                    :key="s.skillId"
                    class="skill-setting"
                    ><span
                        >{{ s.name }}<small>{{ s.code }}</small></span
                    ><select
                        :value="levels[s.skillId] || 0"
                        :disabled="!isAdmin || busy"
                        @change="
                            levels[s.skillId] = Number(
                                ($event.target as HTMLSelectElement).value,
                            )
                        "
                    >
                        <option :value="0">未认证</option>
                        <option v-for="n in 5" :key="n" :value="n">
                            Lv.{{ n }}
                        </option>
                    </select></label
                >
                <p class="helper">
                    保存会替换整套认证技能，至少保留一项。自报技能不会自动成为认证技能。
                </p>
                <button
                    v-if="isAdmin"
                    class="btn primary"
                    :disabled="
                        busy || !Object.values(levels).some((n) => n > 0)
                    "
                >
                    保存认证技能
                </button>
                <div
                    v-if="memberSkills.some((s) => s.selfReported)"
                    class="info-note"
                >
                    待认证的自报技能：{{
                        memberSkills
                            .filter((s) => s.selfReported)
                            .map((s) => s.name + " Lv." + s.level)
                            .join("、")
                    }}
                </div>
            </form></template
        >
        <form v-else @submit.prevent="save()">
            <template v-if="modal === 'member'"
                ><label
                    >账号名称<input
                        v-model="username"
                        required
                        maxlength="100"
                        autocomplete="off"
                        placeholder="例如 zhang.san"
                /></label>
                <p class="helper">
                    创建独立新账号；已有账号的跨公司邀请流程暂未提供。
                </p></template
            ><template v-if="['member', 'role'].includes(modal)"
                ><label
                    >公司角色<select v-model="role">
                        <option value="MEMBER">成员</option>
                        <option value="MANAGER">经理</option>
                        <option value="ADMIN">管理员</option>
                    </select></label
                ></template
            ><template v-if="modal === 'skill'"
                ><label
                    >技能名称<input
                        v-model="skillName"
                        required
                        maxlength="200"
                        placeholder="例如 Java 开发" /></label
                ><label
                    >技能代码<input
                        v-model="code"
                        required
                        pattern="[A-Z][A-Z0-9_]{1,63}"
                        placeholder="例如 JAVA" /></label
                ><label
                    >技能说明<textarea
                        v-model="description"
                        maxlength="2000"
                        rows="3"
                    ></textarea></label></template
            ><template v-if="modal === 'calendar'"
                ><label>日期<input v-model="date" type="date" required /></label
                ><label
                    >是否工作日<select v-model="workday">
                        <option :value="true">工作日</option>
                        <option :value="false">休息日</option>
                    </select></label
                ><label
                    >请假时间（分钟）<input
                        v-model.number="leave"
                        type="number"
                        min="0"
                        max="1440"
                        required /></label
            ></template>
            <p v-if="modal === 'disable'" class="prose">
                确认{{ selected?.status === "ACTIVE" ? "停用" : "启用" }}
                {{ selected?.username }}？系统会校验未完成任务等约束。
            </p>
            <label v-if="['role', 'calendar', 'disable'].includes(modal)"
                >变更原因<textarea
                    v-model="reason"
                    rows="3"
                    maxlength="500"
                    required
                ></textarea>
            </label>
            <div class="form-actions">
                <button
                    class="btn"
                    type="button"
                    :disabled="busy"
                    @click="modal = ''"
                >
                    取消</button
                ><button class="btn primary" :disabled="busy">
                    {{ busy ? "保存中…" : "确认保存" }}
                </button>
            </div>
        </form></Modal
    >
</template>
