<script setup lang="ts">
import { label } from "../utils";
defineProps<{ value: string }>();
</script>
<template>
    <span :class="['badge', value.toLowerCase()]"
        ><span class="status-dot"></span>{{ label(value) }}</span
    >
</template>
