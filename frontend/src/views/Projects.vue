<script setup lang="ts">
import { ref, computed, onMounted, watch } from "vue";
import {
    Plus,
    FolderKanban,
    Users,
    ArrowUpRight,
    Pencil,
} from "lucide-vue-next";
import { api } from "../api";
import {
    state,
    project,
    isAdmin,
    canManageProject,
    selectProject,
    errorMessage,
    notify,
} from "../state";
import type { Project, ProjectMember } from "../types";
import { label } from "../utils";
import Modal from "../components/Modal.vue";
import Empty from "../components/Empty.vue";
const modal = ref(""),
    name = ref(""),
    memberId = ref(""),
    role = ref("MEMBER"),
    resource = ref(false),
    reason = ref(""),
    detail = ref<Project>(),
    members = ref<ProjectMember[]>([]),
    busy = ref(false),
    error = ref(""),
    pageError = ref("");
const available = computed(() =>
    state.members.filter(
        (m) =>
            m.status === "ACTIVE" &&
            !members.value.some((p) => p.memberId === m.memberId),
    ),
);
async function load() {
    pageError.value = "";
    try {
        if (!state.projectId) {
            detail.value = undefined;
            members.value = [];
            return;
        }
        const [p, m] = await Promise.all([
            api<Project>("/projects/" + state.projectId),
            api<ProjectMember[]>("/projects/" + state.projectId + "/members"),
        ]);
        detail.value = p;
        members.value = m;
        state.projectMembers = m;
    } catch (e) {
        pageError.value = errorMessage(e);
    }
}
function open(type: string, m?: ProjectMember) {
    modal.value = type;
    error.value = "";
    name.value = type === "rename" ? detail.value?.name || "" : "";
    memberId.value = m?.memberId || available.value[0]?.memberId || "";
    role.value = m?.role || "MEMBER";
    resource.value = m?.resourcePermission || false;
    reason.value = "";
}
async function save() {
    busy.value = true;
    error.value = "";
    try {
        if (modal.value === "create") {
            const p = await api<Project>("/projects", {
                method: "POST",
                body: { name: name.value },
            });
            state.projects = await api<Project[]>("/projects");
            await selectProject(p.id);
        } else if (modal.value === "rename") {
            await api("/projects/" + state.projectId, {
                method: "PUT",
                version: detail.value!.version,
                body: { name: name.value },
            });
            state.projects = await api<Project[]>("/projects");
        } else if (modal.value === "remove") {
            await api(
                `/projects/${state.projectId}/members/${memberId.value}?reason=${encodeURIComponent(reason.value)}`,
                { method: "DELETE" },
            );
        } else {
            await api(
                `/projects/${state.projectId}/members` +
                    (modal.value === "edit" ? "/" + memberId.value : ""),
                {
                    method: modal.value === "edit" ? "PUT" : "POST",
                    body: {
                        ...(modal.value === "edit"
                            ? {}
                            : { memberId: memberId.value }),
                        role: role.value,
                        resourcePermission: resource.value,
                        ...(modal.value === "edit"
                            ? { reason: reason.value }
                            : {}),
                    },
                },
            );
        }
        modal.value = "";
        notify("项目设置已保存");
        await load();
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        busy.value = false;
    }
}
onMounted(load);
</script>
<template>
    <div class="page-heading">
        <div>
            <span class="eyebrow">PROJECT SETTINGS</span>
            <h1>项目设置<span class="heading-dot">.</span></h1>
            <p>明确协作边界，让合适的人参与合适的项目。</p>
        </div>
        <button v-if="isAdmin" class="btn primary" @click="open('create')">
            <Plus :size="18" />创建项目
        </button>
    </div>
    <div v-if="pageError" class="alert error" role="alert">
        {{ pageError }}<button class="btn small" @click="load">重试</button>
    </div>
    <Empty
        v-if="!state.projects.length"
        title="创建你的第一个项目"
        description="项目将任务、成员和排期连接在一起。"
        ><button v-if="isAdmin" class="btn" @click="open('create')">
            创建项目
        </button></Empty
    ><template v-else
        ><section class="panel project-banner">
            <span class="project-icon"><FolderKanban :size="32" /></span>
            <div class="grow">
                <span class="eyebrow">CURRENT PROJECT</span>
                <h2>{{ project?.name }}</h2>
                <p>{{ members.length }} 名协作成员 · 任务与排期按项目隔离</p>
            </div>
            <button
                v-if="canManageProject && detail"
                class="btn"
                @click="open('rename')"
            >
                <Pencil :size="16" />重命名</button
            ><RouterLink to="/tasks" class="btn"
                >进入任务<ArrowUpRight :size="16"
            /></RouterLink>
        </section>
        <section class="panel">
            <div class="panel-head">
                <div>
                    <h2>项目成员</h2>
                    <span
                        >负责人可管理项目；资源权限用于明确成员的项目资源授权。</span
                    >
                </div>
                <button
                    v-if="canManageProject"
                    class="btn"
                    :disabled="!available.length"
                    @click="open('add')"
                >
                    <Plus :size="16" />添加成员
                </button>
            </div>
            <div class="table-scroll">
                <table>
                    <thead>
                        <tr>
                            <th>成员</th>
                            <th>项目角色</th>
                            <th>资源权限</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="m in members" :key="m.memberId">
                            <td>
                                <span class="person"
                                    ><span class="avatar tiny">{{
                                        m.username[0]
                                    }}</span
                                    ><strong>{{ m.username }}</strong></span
                                >
                            </td>
                            <td>{{ label(m.role) }}</td>
                            <td>
                                {{
                                    m.resourcePermission
                                        ? "已授权"
                                        : "未额外授权"
                                }}
                            </td>
                            <td>
                                <div v-if="canManageProject" class="row">
                                    <button
                                        class="btn small text"
                                        @click="open('edit', m)"
                                    >
                                        编辑</button
                                    ><button
                                        class="btn small text danger-text"
                                        @click="open('remove', m)"
                                    >
                                        移除
                                    </button>
                                </div>
                                <span v-else class="muted">只读</span>
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
        </section>
        <div class="info-note">
            <Users
                :size="18"
            />添加项目成员前，请先在团队中建立账号、认证技能并设置容量。<RouterLink
                to="/team"
                >前往团队设置 →</RouterLink
            >
        </div></template
    ><Modal
        v-if="modal"
        :title="
            {
                create: '创建项目',
                rename: '重命名项目',
                add: '添加项目成员',
                edit: '编辑项目成员',
                remove: '移除项目成员',
            }[modal] || '项目设置'
        "
        :busy="busy"
        @close="modal = ''"
        ><form @submit.prevent="save">
            <div v-if="error" class="alert error" role="alert">{{ error }}</div>
            <label v-if="['create', 'rename'].includes(modal)"
                >项目名称<input
                    v-model="name"
                    required
                    maxlength="200"
                    placeholder="例如：客户服务平台" /></label
            ><template v-if="['add', 'edit'].includes(modal)"
                ><label v-if="modal === 'add'"
                    >成员<select v-model="memberId" required>
                        <option
                            v-for="m in available"
                            :key="m.memberId"
                            :value="m.memberId"
                        >
                            {{ m.username }}
                        </option>
                    </select></label
                ><label
                    >项目角色<select v-model="role">
                        <option value="MEMBER">项目成员</option>
                        <option value="OWNER">项目负责人</option>
                    </select></label
                ><label class="check-label"
                    ><input
                        v-model="resource"
                        type="checkbox"
                    />授予项目资源权限</label
                ></template
            >
            <p v-if="modal === 'remove'">
                确认移除该项目成员？系统会校验现有任务与负责人约束。
            </p>
            <label v-if="['edit', 'remove'].includes(modal)"
                >变更原因<textarea
                    v-model="reason"
                    required
                    maxlength="500"
                    rows="3"
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
