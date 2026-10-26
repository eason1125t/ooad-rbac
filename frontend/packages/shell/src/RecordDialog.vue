<!-- 功能：两业务共用的可聚焦模态外壳；业务字段留在各自应用，忙时阻止关闭和重复提交。 -->
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
const props = defineProps<{ title: string; busy: boolean }>();
const emit = defineEmits<{ close: [] }>();
const element = ref<HTMLDialogElement>();
function close() {
  if (!props.busy) emit("close");
}
onMounted(() => element.value?.showModal());
onBeforeUnmount(() => element.value?.close());
</script>
<template>
  <dialog
    ref="element"
    class="record-dialog"
    :aria-label="title"
    @cancel.prevent="close"
  >
    <header>
      <h2>{{ title }}</h2>
      <button
        type="button"
        aria-label="关闭业务表单"
        :disabled="busy"
        @click="close"
      >
        ×
      </button>
    </header>
    <div class="record-dialog-body"><slot /></div>
  </dialog>
</template>
