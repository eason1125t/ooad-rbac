<!-- 功能：平台选择现有应用委派，真实分页并显示账号名；用于责任指定、专用角色维护人与交接。 -->
<script setup lang="ts">
import { ref, onMounted } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import { useAdmin, useTask } from "../context";
import Pager from "./Pager.vue";
const c = useAdmin();
const props = defineProps<{
  modelValue: string;
  label: string;
  applicationId?: string;
  userValue?: boolean;
  disabled?: boolean;
}>();
defineEmits<{ "update:modelValue": [value: string] }>();
const { busy, error, run } = useTask(),
  page = ref(1),
  q = ref(""),
  list = ref<Schema<"AccessDelegationPage">>();
async function load(p = page.value) {
  page.value = p;
  await run(async () => {
    list.value = await adminApi.delegations(
      props.applicationId ?? c.appId.value,
      { page: p, size: 20, q: q.value },
    );
    await Promise.all(
      list.value.items.map((d) => c.resolveAccountName(d.administratorUserId)),
    );
  });
}
onMounted(() => load());
</script>
<template>
  <fieldset :disabled="disabled" class="person-picker">
    <legend>{{ label }}</legend>
    <div class="search-line">
      <input
        v-model="q"
        :aria-label="`搜索${label}`"
        maxlength="100"
        placeholder="搜索管理员账号"
        @keydown.enter.prevent="load(1)"
      /><button :disabled="busy" type="button" @click="load(1)">
        查找管理员
      </button>
    </div>
    <select
      :aria-label="label"
      :value="modelValue"
      @change="
        $emit('update:modelValue', ($event.target as HTMLSelectElement).value)
      "
    >
      <option value="">请选择 / 暂不指定</option>
      <option
        v-if="
          modelValue &&
          !list?.items.some(
            (d) => (userValue ? d.administratorUserId : d.id) === modelValue,
          )
        "
        :value="modelValue"
      >
        当前所选管理员（不在本页）
      </option>
      <option
        v-for="d in list?.items"
        :key="d.id"
        :value="userValue ? d.administratorUserId : d.id"
      >
        {{ c.accountName(d.administratorUserId)
        }}{{ d.enabled ? "" : "（委派停用）" }}
      </option></select
    ><Pager
      v-if="list"
      :page="page"
      :size="20"
      :total="list.total"
      :busy="busy"
      @change="load"
    />
    <p v-if="error" role="alert" class="error-box">{{ error }}</p>
  </fieldset>
</template>
