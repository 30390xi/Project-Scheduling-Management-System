<script setup lang="ts">
import { Layers } from "lucide-vue-next";
defineProps<{ title: string; description?: string }>();
</script>
<template>
    <div class="empty">
        <div class="empty-symbol"><Layers :size="28" /></div>
        <h3>{{ title }}</h3>
        <p v-if="description">{{ description }}</p>
        <slot />
    </div>
</template>
