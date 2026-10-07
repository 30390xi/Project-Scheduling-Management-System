<script setup lang="ts">
import { ref } from "vue";
import {
    ArrowRight,
    Layers,
    ShieldCheck,
    Sparkles,
    CalendarDays,
} from "lucide-vue-next";
import { login, state, errorMessage, restore, logout } from "../state";
import { api } from "../api";
const username = ref(""),
    password = ref(""),
    nextPassword = ref(""),
    repeat = ref(""),
    busy = ref(false),
    error = ref("");
async function submit() {
    busy.value = true;
    error.value = "";
    try {
        if (state.passwordRequired) {
            if (nextPassword.value !== repeat.value)
                throw new Error("两次输入的新密码不一致");
            await api("/me/password", {
                method: "POST",
                body: {
                    currentPassword: password.value,
                    newPassword: nextPassword.value,
                },
            });
            state.passwordRequired = false;
            await restore();
        } else await login(username.value, password.value);
        password.value = "";
        nextPassword.value = "";
        repeat.value = "";
    } catch (e) {
        error.value = errorMessage(e);
    } finally {
        busy.value = false;
    }
}
</script>
<template>
    <div class="login-page">
        <section class="login-story">
            <div class="brand">
                <span class="brand-mark"><Layers :size="25" /></span
                ><strong>序程<span>AI SCHEDULER</span></strong>
            </div>
            <div class="login-copy">
                <span class="eyebrow light">让每一份投入，都有清晰的安排</span>
                <h1>把复杂的任务，<br />排成有序的每一天。</h1>
                <p>
                    从需求到执行，让 AI 协助拆解，<br />让团队在合理的节奏中持续向前。
                </p>
                <div class="story-lines">
                    <div><Sparkles :size="20" />理解需求，辅助拆解</div>
                    <div><CalendarDays :size="20" />匹配技能，平衡负载</div>
                    <div><ShieldCheck :size="20" />明确约束，由你确认</div>
                </div>
            </div>
            <div class="login-footer">为小团队而设计 · 项目协作与智能排期</div>
        </section>
        <section class="login-form-side">
            <form class="login-form" @submit.prevent="submit">
                <span class="eyebrow">YOUR WORKSPACE</span>
                <h2>
                    {{ state.passwordRequired ? "设置你的新密码" : "欢迎回来" }}
                </h2>
                <p>
                    {{
                        state.passwordRequired
                            ? "首次登录或密码已重置，请先完成改密。"
                            : "登录工作空间，开始安排今天的工作。"
                    }}
                </p>
                <div
                    v-if="error || state.error"
                    class="alert error"
                    role="alert"
                >
                    {{ error || state.error }}
                </div>
                <label v-if="!state.passwordRequired"
                    >账号<input
                        v-model="username"
                        autocomplete="username"
                        required
                        placeholder="请输入账号"
                        maxlength="100" /></label
                ><label
                    >{{ state.passwordRequired ? "当前 / 临时密码" : "密码"
                    }}<input
                        v-model="password"
                        type="password"
                        autocomplete="current-password"
                        required
                        placeholder="请输入密码"
                        maxlength="72" /></label
                ><template v-if="state.passwordRequired"
                    ><label
                        >新密码<input
                            v-model="nextPassword"
                            type="password"
                            autocomplete="new-password"
                            required
                            minlength="12"
                            maxlength="72"
                            placeholder="至少 12 个字符" /></label
                    ><label
                        >确认新密码<input
                            v-model="repeat"
                            type="password"
                            autocomplete="new-password"
                            required
                            minlength="12"
                            maxlength="72" /></label></template
                ><button class="btn primary full" :disabled="busy">
                    {{
                        busy
                            ? "正在处理…"
                            : state.passwordRequired
                              ? "保存新密码"
                              : "登录工作空间"
                    }}<ArrowRight :size="18" /></button
                ><button
                    v-if="state.passwordRequired"
                    type="button"
                    class="btn text full"
                    @click="logout"
                >
                    返回登录
                </button>
                <div class="login-help">
                    <ShieldCheck
                        :size="16"
                    />账号由公司管理员分配。如忘记密码，请联系管理员。
                </div>
            </form>
        </section>
    </div>
</template>
