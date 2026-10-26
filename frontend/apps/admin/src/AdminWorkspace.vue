<!-- 功能：admin真实五工作区组合根；负责会话/应用上下文，业务表单留在各功能组件。 -->
<script setup lang="ts">
import { provide, watch, ref, computed } from "vue";
import { api, adminApi } from "@municipal/api";
import { createContext, contextKey, useTask } from "./context";
import OrganizationWorkspace from "./OrganizationWorkspace.vue";
import RoleWorkspace from "./RoleWorkspace.vue";
import ApplicationWorkspace from "./ApplicationWorkspace.vue";
import AuditWorkspace from "./AuditWorkspace.vue";
import Workbench from "./Workbench.vue";
const props = defineProps<{ page: string }>();
const c = createContext();
provide(contextKey, c);
const { busy, error, run } = useTask();
const password = ref(""),
  replacement = ref(""),
  changed = ref(false);
const title = computed(
  () =>
    ({
      workspace: "工作台",
      organizations: "组织与用户",
      roles: "角色与授权",
      applications: "应用与资源",
      audit: "操作审计",
    })[props.page] ?? "工作台",
);
watch(
  () => c.session.user?.id,
  () => {
    if (c.session.user && !c.session.user.forcePasswordChange)
      void run(c.refresh);
  },
  { immediate: true },
);
async function changeApp(event: Event) {
  const select = event.target as HTMLSelectElement;
  const next = select.value;
  if (!c.discard()) {
    select.value = c.appId.value;
    return;
  }
  c.appId.value = next;
  await run(c.refreshOrgs);
}
async function changePassword() {
  await run(async () => {
    await api.changePassword({
      currentPassword: password.value,
      newPassword: replacement.value,
    });
    password.value = "";
    replacement.value = "";
    c.session.user = undefined;
    changed.value = true;
  });
}
</script>
<template>
  <div class="live-admin">
    <div class="page-heading">
      <div>
        <h1>{{ title }}</h1>
        <p class="muted">在已授权范围内管理人员、角色与业务应用。</p>
      </div>
      <span class="stage-label live-label">真实数据</span>
    </div>
    <section
      v-if="c.session.user?.forcePasswordChange || changed"
      class="workspace-panel pane"
    >
      <h2>{{ changed ? "密码已修改" : "请先修改临时密码" }}</h2>
      <template v-if="changed"
        ><p>旧会话已失效，请使用新密码重新登录。</p>
        <RouterLink class="button-link" to="/login"
          >重新登录</RouterLink
        ></template
      >
      <form
        v-else
        class="form-grid"
        :inert="busy"
        @submit.prevent="changePassword"
      >
        <label
          >当前密码<input
            v-model="password"
            type="password"
            required
            autocomplete="current-password" /></label
        ><label
          >新密码<input
            v-model="replacement"
            type="password"
            required
            minlength="12"
            maxlength="72"
            autocomplete="new-password"
        /></label>
        <p>新密码至少12个字符，不得与旧密码相同。</p>
        <button class="primary" :disabled="busy">保存新密码并重新登录</button>
      </form>
    </section>
    <template v-else
      ><div class="context-bar">
        <span
          >当前身份：{{
            c.platform.value ? "平台管理员" : "业务应用管理员"
          }}</span
        ><label v-if="c.apps.value.length"
          >管理应用<select
            :value="c.appId.value"
            aria-label="管理应用"
            :disabled="busy"
            @change="changeApp"
          >
            <option v-for="app in c.apps.value" :key="app.id" :value="app.id">
              {{ app.name }}
            </option>
          </select></label
        ><button :disabled="busy" @click="run(c.refresh)">
          {{ busy ? "正在刷新…" : "刷新上下文" }}
        </button>
      </div>
      <p v-if="error" role="alert" class="error-box">{{ error }}</p>
      <p v-if="!busy && !c.apps.value.length" class="info-box">
        当前没有可管理的应用。业务账号需要平台先配置管理员委派、组织范围与人员维护责任。
      </p>
      <Workbench v-if="page === 'workspace'" /><OrganizationWorkspace
        v-else-if="page === 'organizations'"
        :key="`people-${c.appId.value}`" /><RoleWorkspace
        v-else-if="page === 'roles' && c.current.value"
        :key="`roles-${c.appId.value}`" /><ApplicationWorkspace
        v-else-if="page === 'applications' && c.current.value"
        :key="`apps-${c.appId.value}`" /><AuditWorkspace
        v-else-if="page === 'audit'"
    /></template>
    <p
      v-if="(c.session.user?.forcePasswordChange || changed) && error"
      role="alert"
      class="error-box"
    >
      {{ error }}
    </p>
  </div>
</template>
