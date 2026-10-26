<!-- 功能：预览确认的共同上下文；不显示服务器令牌，业务差异由调用工作区以名称展示。 -->
<script setup lang="ts">
import type { Schema } from "@municipal/api";
import { date } from "../context";
defineProps<{ preview: Schema<"AccessPreview">; busy?: boolean }>();
defineEmits<{ confirm: []; back: [] }>();
</script>
<template>
  <section class="preview-box" aria-label="变更预览">
    <h3>确认本次变更</h3>
    <p>
      影响 {{ preview.affectedUsers }} 名人员；预览有效至
      {{ date(preview.expiresAt) }}。
    </p>
    <slot />
    <p class="muted">
      请核对变更对象和范围。员工已授角色不随原管理员的个人权限变化而被撤回。
    </p>
    <div class="form-actions">
      <button type="button" :disabled="busy" @click="$emit('back')">
        返回编辑</button
      ><button
        type="button"
        class="primary"
        :disabled="busy"
        @click="$emit('confirm')"
      >
        {{ busy ? "正在保存…" : "确认保存" }}
      </button>
    </div>
  </section>
</template>
