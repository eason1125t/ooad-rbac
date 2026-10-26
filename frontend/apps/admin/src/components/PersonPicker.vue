<!-- 功能：真实分页人员/账号选择；平台查账号/人员，业务管理员只查本人本应用可维护人员。 -->
<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { adminApi } from "@municipal/api";
import { useAdmin, useTask } from "../context";
const props = defineProps<{
  modelValue: string;
  label: string;
  mode?: "account" | "employee";
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [id: string] }>();
const c = useAdmin();
const { busy, error, run } = useTask();
const q = ref(""),
  page = ref(1),
  total = ref(0),
  options = ref<{ id: string; label: string }[]>([]),
  selectedLabel = ref("已选人员");
async function load() {
  await run(async () => {
    if (props.mode === "employee") {
      const data = await adminApi.employees({
        q: q.value,
        page: page.value,
        size: 20,
      });
      options.value = data.items.map((e) => ({
        id: e.id,
        label: `${e.displayName}（${e.employeeCode}）`,
      }));
      total.value = data.total;
    } else if (c.platform.value) {
      const data = await adminApi.accounts({
        q: q.value,
        page: page.value,
        size: 20,
      });
      options.value = data.items.map((a) => ({
        id: a.id,
        label: a.username + (a.enabled ? "" : "（停用）"),
      }));
      total.value = data.total;
    } else {
      const data = await adminApi.people(c.appId.value, {
        q: q.value,
        page: page.value,
        size: 20,
      });
      options.value = data.items.map((a) => ({
        id: a.userId,
        label: `${a.username} · ${a.displayName}`,
      }));
      total.value = data.total;
    }
  });
}
function choose(id: string) {
  selectedLabel.value =
    options.value.find((o) => o.id === id)?.label ?? "已选人员";
  emit("update:modelValue", id);
}
onMounted(async () => {
  await load();
  if (
    props.modelValue &&
    c.platform.value &&
    !options.value.some((o) => o.id === props.modelValue)
  )
    await run(async () => {
      selectedLabel.value =
        props.mode === "employee"
          ? (await adminApi.employee(props.modelValue)).displayName
          : (await adminApi.account(props.modelValue)).username;
    });
});
watch(
  () => props.mode,
  () => {
    page.value = 1;
    void load();
  },
);
</script>
<template>
  <fieldset class="person-picker" :disabled="disabled">
    <legend>{{ label }}</legend>
    <div class="search-line">
      <input
        v-model="q"
        :aria-label="`搜索${label}`"
        maxlength="100"
        placeholder="输入姓名、账号或编码"
        @keydown.enter.prevent="
          page = 1;
          load();
        "
      /><button
        type="button"
        :disabled="busy"
        @click="
          page = 1;
          load();
        "
      >
        查找
      </button>
    </div>
    <select
      :aria-label="label"
      :value="modelValue"
      @change="choose(($event.target as HTMLSelectElement).value)"
    >
      <option value="">请选择</option>
      <option
        v-if="modelValue && !options.some((o) => o.id === modelValue)"
        :value="modelValue"
      >
        {{ selectedLabel }}
      </option>
      <option v-for="o in options" :key="o.id" :value="o.id">
        {{ o.label }}
      </option>
    </select>
    <div class="picker-pages">
      <button
        type="button"
        :disabled="busy || page <= 1"
        @click="
          page--;
          load();
        "
      >
        上一组</button
      ><span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span
      ><button
        type="button"
        :disabled="busy || page * 20 >= total"
        @click="
          page++;
          load();
        "
      >
        下一组
      </button>
    </div>
    <p v-if="error" role="alert" class="error-box">{{ error }}</p>
  </fieldset>
</template>
