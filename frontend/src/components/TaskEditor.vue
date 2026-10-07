<script setup lang="ts">
import { reactive, ref } from "vue";
import Modal from "./Modal.vue";
import { Plus, Trash2 } from "lucide-vue-next";
import { api } from "../api";
import { state, errorMessage } from "../state";
import { uid } from "../utils";
import type { TaskDetail, TaskInput } from "../types";
const props = defineProps<{ task?: TaskDetail }>(),
    emit = defineEmits<{ close: []; saved: [task: TaskDetail] }>();
const t = props.task;
const form = reactive<TaskInput>({
    title: t?.title || "",
    description: t?.description || "",
    taskType: t?.taskType || "BACKEND_DEVELOPMENT",
    priority: t?.priority || "P2",
    estimatedMinutes: t?.estimatedMinutes ?? null,
    dueDate: t?.dueDate || null,
    dueType: t?.dueType || "SOFT",
    earliestStartDate: t?.earliestStartDate || null,
    fixedAssigneeId: t?.fixedAssigneeId || null,
    preferredMemberId: t?.preferredMemberId || null,
    acceptanceCriteria: t?.acceptanceCriteria || "",
    skills: t?.skills.map((s) => ({ ...s })) || [],
});
const busy = ref(false),
    error = ref(""),
    key = uid();
async function save() {
    busy.value = true;
    error.value = "";
    try {
        const body = {
            ...form,
            estimatedMinutes:
                form.estimatedMinutes == null ||
                String(form.estimatedMinutes) === ""
                    ? null
                    : Number(form.estimatedMinutes),
            dueDate: form.dueDate || null,
            earliestStartDate: form.earliestStartDate || null,
            ...(!t ? { projectId: state.projectId } : {}),
        };
        const result = await api<TaskDetail>(t ? `/tasks/${t.id}` : "/tasks", {
            method: t ? "PATCH" : "POST",
            body,
            version: t?.version,
            key: !t ? key : undefined,
        });
        emit("saved", result);
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        busy.value = false;
    }
}
</script>
<template>
    <Modal
        :title="t ? '编辑任务' : '创建任务'"
        wide
        :busy="busy"
        @close="emit('close')"
        ><form @submit.prevent="save">
            <div v-if="error" class="alert error" role="alert">{{ error }}</div>
            <label
                >任务名称 <span class="required">*</span
                ><input
                    v-model="form.title"
                    required
                    maxlength="300"
                    placeholder="例如：完成客户管理模块的查询接口" /></label
            ><label
                >需求描述<textarea
                    v-model="form.description"
                    rows="4"
                    maxlength="20000"
                    placeholder="说明业务目标、范围和交付要求，AI 将据此分析与拆解。"
                ></textarea>
            </label>
            <div class="form-grid">
                <label
                    >任务类型<select v-model="form.taskType">
                        <option value="BACKEND_DEVELOPMENT">后端开发</option>
                        <option value="FRONTEND_DEVELOPMENT">前端开发</option>
                        <option value="DESIGN">设计</option>
                        <option value="TESTING">测试</option>
                        <option value="DOCUMENTATION">文档</option>
                        <option value="OTHER">其他</option>
                    </select></label
                ><label
                    >优先级<select v-model="form.priority">
                        <option value="P0">P0 紧急</option>
                        <option value="P1">P1 高</option>
                        <option value="P2">P2 普通</option>
                        <option value="P3">P3 低</option>
                    </select></label
                ><label
                    >人工预估工时（分钟）<input
                        v-model.number="form.estimatedMinutes"
                        type="number"
                        min="1"
                        step="1"
                        placeholder="可留空，交由 AI 分析" /></label
                ><label
                    >最早开始日期<input
                        v-model="form.earliestStartDate"
                        type="date" /></label
                ><label
                    >截止日期<input v-model="form.dueDate" type="date" /></label
                ><label
                    >截止约束<select v-model="form.dueType">
                        <option value="SOFT">期望截止，可提示风险</option>
                        <option value="HARD">硬截止，必须满足</option>
                    </select></label
                ><label
                    >优先分配给<select v-model="form.preferredMemberId">
                        <option :value="null">自动匹配</option>
                        <option
                            v-for="m in state.projectMembers"
                            :key="m.memberId"
                            :value="m.memberId"
                        >
                            {{ m.username }}
                        </option>
                    </select></label
                ><label
                    >固定负责人<select v-model="form.fixedAssigneeId">
                        <option :value="null">不固定，允许改派</option>
                        <option
                            v-for="m in state.projectMembers"
                            :key="m.memberId"
                            :value="m.memberId"
                        >
                            {{ m.username }}
                        </option>
                    </select></label
                >
            </div>
            <label
                >验收标准<textarea
                    v-model="form.acceptanceCriteria"
                    rows="2"
                    maxlength="5000"
                    placeholder="完成后如何验证交付成果？"
                ></textarea>
            </label>
            <div class="section-label">
                技能要求<button
                    type="button"
                    class="btn small"
                    :disabled="!state.skills.some((s) => s.active)"
                    @click="
                        form.skills.push({
                            skillCode: state.skills.find((s) => s.active)!.code,
                            minLevel: 1,
                            required: true,
                        })
                    "
                >
                    <Plus :size="14" />添加技能
                </button>
            </div>
            <div
                v-for="(s, index) in form.skills"
                :key="index"
                class="skill-row"
            >
                <select v-model="s.skillCode" aria-label="要求的技能">
                    <option
                        v-for="skill in state.skills.filter((s) => s.active)"
                        :key="skill.code"
                        :value="skill.code"
                    >
                        {{ skill.name }}
                    </option></select
                ><select v-model.number="s.minLevel" aria-label="最低等级">
                    <option v-for="level in 5" :value="level" :key="level">
                        等级 {{ level }}
                    </option></select
                ><button
                    type="button"
                    class="icon-btn"
                    aria-label="移除技能要求"
                    @click="form.skills.splice(index, 1)"
                >
                    <Trash2 :size="17" />
                </button>
            </div>
            <p class="helper">
                保存后进入草稿。提交 AI
                分析会使用后端配置的模型额度；固定负责人无容量时不会自动改派。
            </p>
            <div class="form-actions">
                <button
                    class="btn"
                    type="button"
                    :disabled="busy"
                    @click="emit('close')"
                >
                    取消</button
                ><button
                    class="btn primary"
                    :disabled="busy || !state.projectId"
                >
                    {{ busy ? "保存中…" : "保存任务" }}
                </button>
            </div>
        </form></Modal
    >
</template>
