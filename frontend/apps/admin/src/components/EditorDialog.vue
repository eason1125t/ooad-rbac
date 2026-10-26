<!-- 功能：管理表单共用原生模态，提供焦点约束、Esc/取消、忙时防关闭；业务字段由各工作区提供。 -->
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
const props = defineProps<{ title: string; busy?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
function close() {
  if (!props.busy) emit("close");
}
onMounted(() => dialog.value?.showModal());
onBeforeUnmount(() => dialog.value?.close());
</script>
<template>
  <dialog
    ref="dialog"
    class="editor-dialog"
    :aria-label="title"
    @cancel.prevent="close"
  >
    <header class="dialog-heading">
      <h2>{{ title }}</h2>
      <button
        type="button"
        aria-label="关闭表单"
        :disabled="busy"
        @click="close"
      >
        ×
      </button>
    </header>
    <div class="dialog-body"><slot /></div>
  </dialog>
</template>
