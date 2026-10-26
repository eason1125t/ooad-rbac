<!-- 功能：数据范围/人员适用范围的结构化选择器；分别使用当前权限上限和人员目录标记，不将二者混同。 -->
<script setup lang="ts">
import { computed } from "vue";
import type { Schema } from "@municipal/api";
import { useAdmin } from "../context";
const props = defineProps<{
  modelValue: Schema<"AccessScope">;
  people?: boolean;
  disabled?: boolean;
  capabilities?: Schema<"AccessCapabilities">;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: Schema<"AccessScope">];
}>();
const c = useAdmin();
// 目录包含必要祖先；可见不等于可授权，按当前权限点上限筛选可选节点。
function covered(id: string) {
  const cap = props.capabilities;
  if (!cap || cap.all) return true;
  if (props.modelValue.kind === "ORG" && cap.organizationIds.includes(id))
    return true;
  const visited = new Set<string>();
  let current: string | null = id;
  while (current && !visited.has(current)) {
    if (cap.subtreeRootIds.includes(current)) return true;
    visited.add(current);
    current = c.orgs.value.find((o) => o.id === current)?.parentId ?? null;
  }
  return false;
}
const choices = computed(() =>
  c.orgs.value.filter(
    (o) =>
      props.modelValue.organizationIds.includes(o.id) ||
      (o.enabled && (!props.people || o.peopleSelectable) && covered(o.id)),
  ),
);
const hiddenSelections = computed(() =>
  props.modelValue.organizationIds.filter(
    (id) => !choices.value.some((o) => o.id === id),
  ),
);
function change(kind: Schema<"AccessScope">["kind"]) {
  emit("update:modelValue", {
    kind,
    organizationIds: kind.startsWith("ORG")
      ? props.modelValue.organizationIds
      : [],
  });
}
function set(id: string, checked: boolean) {
  emit("update:modelValue", {
    ...props.modelValue,
    organizationIds: checked
      ? [...props.modelValue.organizationIds, id]
      : props.modelValue.organizationIds.filter((n) => n !== id),
  });
}
const modeAllowed = (kind: string) =>
  !props.capabilities ||
  props.capabilities.all ||
  (kind === "SELF"
    ? props.capabilities.self
    : kind === "ORG"
      ? props.capabilities.organizationIds.length > 0 ||
        props.capabilities.subtreeRootIds.length > 0
      : kind === "ORG_SUBTREE"
        ? props.capabilities.subtreeRootIds.length > 0
        : false);
</script>
<template>
  <div class="scope-editor">
    <select
      aria-label="范围类型"
      :disabled="disabled"
      :value="modelValue.kind"
      @change="
        change(
          ($event.target as HTMLSelectElement)
            .value as Schema<'AccessScope'>['kind'],
        )
      "
    >
      <option value="ALL" :disabled="!modeAllowed('ALL')">
        {{ people ? "全公司人员" : "全公司数据" }}
      </option>
      <option v-if="!people" value="SELF" :disabled="!modeAllowed('SELF')">
        本人数据
      </option>
      <option value="ORG" :disabled="!modeAllowed('ORG')">指定组织</option>
      <option value="ORG_SUBTREE" :disabled="!modeAllowed('ORG_SUBTREE')">
        组织及下级
      </option>
    </select>
    <div v-if="modelValue.kind.startsWith('ORG')" class="check-list">
      <label v-for="o in choices" :key="o.id"
        ><input
          type="checkbox"
          :disabled="
            disabled ||
            (people &&
              modelValue.kind === 'ORG_SUBTREE' &&
              !o.peopleSubtreeAllowed)
          "
          :checked="modelValue.organizationIds.includes(o.id)"
          @change="set(o.id, ($event.target as HTMLInputElement).checked)"
        />{{ o.name }}</label
      >
      <p v-if="!choices.length" class="muted">当前范围没有可选组织。</p>
      <label v-for="id in hiddenSelections" :key="id"
        ><input
          type="checkbox"
          checked
          :disabled="disabled"
          @change="set(id, false)"
        />原有不可见组织范围（可撤回）</label
      >
    </div>
  </div>
</template>
