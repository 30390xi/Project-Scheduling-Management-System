<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { X } from "lucide-vue-next";
defineProps<{ title: string; wide?: boolean; busy?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
onMounted(() => dialog.value?.showModal());
onBeforeUnmount(() => dialog.value?.close());
</script>
<template>
    <dialog
        ref="dialog"
        :class="['modal', { wide }]"
        aria-labelledby="modal-title"
        @cancel.prevent="!busy && emit('close')"
    >
        <header class="modal-head">
            <h2 id="modal-title">{{ title }}</h2>
            <button
                class="icon-btn"
                aria-label="关闭弹窗"
                :disabled="busy"
                @click="emit('close')"
            >
                <X :size="20" />
            </button>
        </header>
        <div class="modal-body"><slot /></div>
    </dialog>
</template>
