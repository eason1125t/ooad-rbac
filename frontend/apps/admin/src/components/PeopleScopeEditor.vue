<!-- 功能：人员适用范围编辑；每个组织可独立选择包含下级，生成契约AccessPeopleScope。 -->
<script setup lang="ts">
import { computed } from "vue";
import type { Schema } from "@municipal/api";
import { useAdmin } from "../context";
const c = useAdmin();
const props = defineProps<{
  modelValue: Schema<"AccessPeopleScope">;
  disabled?: boolean;
  unrestricted?: boolean;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: Schema<"AccessPeopleScope">];
}>();
const visible = computed(() =>
  c.orgs.value.filter(
    (n) => n.enabled && (props.unrestricted || n.peopleSelectable),
  ),
);
const retained = computed(() =>
  props.modelValue.organizations.filter(
    (n) => !visible.value.some((o) => o.id === n.organizationId),
  ),
);
function toggle(id: string, checked: boolean) {
  emit("update:modelValue", {
    allCompany: false,
    organizations: checked
      ? [
          ...props.modelValue.organizations,
          { organizationId: id, includeDescendants: false },
        ]
      : props.modelValue.organizations.filter((n) => n.organizationId !== id),
  });
}
function subtree(id: string, checked: boolean) {
  emit("update:modelValue", {
    ...props.modelValue,
    organizations: props.modelValue.organizations.map((n) =>
      n.organizationId === id ? { ...n, includeDescendants: checked } : n,
    ),
  });
}
</script>
<template>
  <fieldset class="scope-editor" :disabled="disabled">
    <legend>人员适用范围</legend>
    <label
      ><input
        type="radio"
        :checked="modelValue.allCompany"
        :disabled="
          !c.platform.value &&
          !c.current.value?.delegation?.peopleScope.allCompany
        "
        @change="
          $emit('update:modelValue', { allCompany: true, organizations: [] })
        "
      />全公司</label
    ><label
      ><input
        type="radio"
        :checked="!modelValue.allCompany"
        @change="
          $emit('update:modelValue', { allCompany: false, organizations: [] })
        "
      />指定组织</label
    >
    <div v-if="!modelValue.allCompany" class="check-list">
      <div v-for="o in visible" :key="o.id" class="scope-node">
        <label
          ><input
            type="checkbox"
            :checked="
              modelValue.organizations.some((n) => n.organizationId === o.id)
            "
            @change="toggle(o.id, ($event.target as HTMLInputElement).checked)"
          />{{ o.name }}</label
        ><label
          v-if="modelValue.organizations.some((n) => n.organizationId === o.id)"
          ><input
            type="checkbox"
            :disabled="!unrestricted && !o.peopleSubtreeAllowed"
            :checked="
              modelValue.organizations.find((n) => n.organizationId === o.id)
                ?.includeDescendants
            "
            @change="subtree(o.id, ($event.target as HTMLInputElement).checked)"
          />包含下级</label
        >
      </div>
      <label v-for="n in retained" :key="n.organizationId"
        ><input
          type="checkbox"
          checked
          @change="toggle(n.organizationId, false)"
        />{{
          c.orgs.value.find((o) => o.id === n.organizationId)?.name ??
          "原有不可见组织"
        }}{{ n.includeDescendants ? "及下级" : "" }}（可撤回）</label
      >
    </div>
  </fieldset>
</template>
