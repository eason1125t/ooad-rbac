<!-- 功能：平台真实审计只读分页和差异详情；服务端尚未提供业务管理员范围审计时不扩大读取。 -->
<script setup lang="ts">
import { ref, onMounted } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import { useAdmin, useTask, date } from "./context";
import Pager from "./components/Pager.vue";
const c = useAdmin();
const { busy, error, run } = useTask();
const page = ref(1),
  records = ref<Schema<"AuditRecordPage">>(),
  selected = ref<Schema<"AuditRecord">>();
async function load(p = page.value) {
  page.value = p;
  await run(async () => {
    records.value = await adminApi.audit(p);
    selected.value = undefined;
  });
}
onMounted(() => {
  if (c.platform.value && c.can("admin:audit:view")) void load();
});
const actions: Record<string, string> = {
  ORGANIZATION_CREATE: "新建组织",
  ORGANIZATION_UPDATE: "编辑组织",
  ORGANIZATION_STATUS: "组织启停",
  ORGANIZATION_MOVE: "移动组织",
  EMPLOYEE_CREATE: "新建人员",
  EMPLOYEE_UPDATE: "编辑人员",
  EMPLOYEE_STATUS: "人员启停",
  ACCOUNT_CREATE: "建立账号",
  ACCOUNT_STATUS: "账号启停",
  ACCOUNT_PASSWORD_RESET: "重置密码",
  ROLE_CREATE: "新建角色",
  ROLE_UPDATE: "编辑角色",
  ROLE_STATUS: "角色启停",
  ROLE_PERMISSIONS: "配置角色权限",
  USER_ROLES: "配置人员角色",
  ROLE_MEMBER_ADD: "添加角色成员",
  ROLE_MEMBER_REMOVE: "移除角色成员",
  DELEGATION_SAVE: "配置管理员委派",
  PERSON_RESPONSIBILITY: "交接维护责任",
  ROLE_OWNER_TRANSFER: "交接角色",
  EMPLOYEE_ASSIGNMENT: "人员调岗",
  EMPLOYEE_ROLE_REPLACEMENT: "调岗换角",
  ADMINISTRATOR_RETIRE: "撤回管理任职",
};
</script>
<template>
  <section class="workspace-panel pane">
    <div class="section-heading">
      <h2>管理操作记录</h2>
      <button
        v-if="c.can('admin:audit:view') && c.platform.value"
        :disabled="busy"
        @click="load()"
      >
        刷新审计
      </button>
    </div>
    <p v-if="!c.platform.value || !c.can('admin:audit:view')" class="info-box">
      当前接口仅向有审计权限的平台管理员开放。业务管理员范围审计尚未提供。
    </p>
    <template v-else
      ><p v-if="error" role="alert" class="error-box">{{ error }}</p>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>动作</th>
              <th>对象</th>
              <th>操作人</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in records?.items" :key="r.id">
              <td>{{ date(r.createdAt) }}</td>
              <td>{{ actions[r.action] ?? r.action }}</td>
              <td>{{ r.targetType }} #{{ r.targetId }}</td>
              <td>账号 #{{ r.actorUserId }}</td>
              <td><button @click="selected = r">查看差异</button></td>
            </tr>
            <tr v-if="records && !records.items.length">
              <td colspan="5">暂无管理操作记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager
        v-if="records"
        :page="page"
        :size="20"
        :total="records.total"
        :busy="busy"
        @change="load"
      />
      <section v-if="selected" class="audit-detail">
        <h3>{{ actions[selected.action] ?? selected.action }} · 变更差异</h3>
        <div class="diff-columns">
          <div>
            <h4>变更前</h4>
            <pre>{{ JSON.stringify(selected.beforeState, null, 2) }}</pre>
          </div>
          <div>
            <h4>变更后</h4>
            <pre>{{ JSON.stringify(selected.afterState, null, 2) }}</pre>
          </div>
        </div>
        <small>请求编号 {{ selected.traceId }}</small>
      </section></template
    >
  </section>
</template>
