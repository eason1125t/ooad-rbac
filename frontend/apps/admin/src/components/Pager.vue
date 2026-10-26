<!-- 功能：统一服务端分页控件；不对当前页过滤后冒充全量列表。 -->
<script setup lang="ts">
defineProps<{ page: number; size: number; total: number; busy?: boolean }>();
defineEmits<{ change: [page: number] }>();
</script>
<template>
  <div class="pager">
    <span>共 {{ total }} 条 · 第 {{ page }} 页</span
    ><button :disabled="busy || page <= 1" @click="$emit('change', page - 1)">
      上一页</button
    ><button
      :disabled="busy || page * size >= total"
      @click="$emit('change', page + 1)"
    >
      下一页
    </button>
  </div>
</template>
