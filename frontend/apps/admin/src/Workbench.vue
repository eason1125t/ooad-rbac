<!-- 功能：真实工作台概览及入口；没有接口支持的统计不虚构为零，不复制审批/导入原型数据。 -->
<script setup lang="ts">
import { ref, watch } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import { useAdmin, useTask, date } from "./context";
const c = useAdmin();
const { busy, error, run } = useTask();
const people = ref<number>(),
  accounts = ref<number>(),
  audit = ref<Schema<"AuditRecord">[]>([]);
async function load() {
  await run(async () => {
    const tasks: Promise<void>[] = [];
    if (c.platform.value && c.can("admin:employee:view"))
      tasks.push(
        adminApi.employees({ page: 1, size: 1 }).then((p) => {
          people.value = p.total;
        }),
      );
    if (c.platform.value && c.can("admin:account:view"))
      tasks.push(
        adminApi.accounts({ page: 1, size: 1 }).then((p) => {
          accounts.value = p.total;
        }),
      );
    if (c.platform.value && c.can("admin:audit:view"))
      tasks.push(
        adminApi.audit(1).then((p) => {
          audit.value = p.items.slice(0, 4);
        }),
      );
    await Promise.all(tasks);
  });
}
watch(() => c.session.user?.id, load, { immediate: true });
</script>
<template>
  <p v-if="error" role="alert" class="error-box">{{ error }}</p>
  <section class="workspace-panel pane">
    <div class="section-heading">
      <h2>今天从这里开始</h2>
      <button :disabled="busy" @click="load">刷新概览</button>
    </div>
    <div class="facts-strip">
      <div>
        <span>可管理应用</span><strong>{{ c.apps.value.length }}</strong>
      </div>
      <div v-if="people !== undefined">
        <span>人员档案</span><strong>{{ people }}</strong>
      </div>
      <div v-if="accounts !== undefined">
        <span>登录账号</span><strong>{{ accounts }}</strong>
      </div>
      <div>
        <span>当前账号</span
        ><strong class="fact-name">{{ c.session.user?.username }}</strong>
      </div>
    </div>
    <div class="workbench-layout">
      <div class="task-list">
        <RouterLink to="/organizations"
          >组织与用户<small>{{
            c.platform.value
              ? "建立组织、人员与账号，处理人员变动"
              : "查看本应用由你负责的人员及其授权"
          }}</small></RouterLink
        ><RouterLink to="/roles"
          >角色与授权<small
            >维护角色权限、成员及有效期，预览后确认授权</small
          ></RouterLink
        ><RouterLink to="/applications"
          >应用与资源<small
            >查看权限目录，配置管理员范围与可委派上限</small
          ></RouterLink
        ><RouterLink to="/audit"
          >操作审计<small>平台管理变更的真实记录与前后差异</small></RouterLink
        >
      </div>
      <div class="scope-summary">
        <h3>当前管理范围</h3>
        <p v-if="c.platform.value">
          平台管理动作按账号实际权限开放。给业务管理员任命时，需要明确应用、组织范围和授权上限。
        </p>
        <p v-else-if="c.current.value?.delegation">
          {{ c.current.value.name }}：{{
            c.current.value.delegation.peopleScope.allCompany
              ? "全公司人员"
              : c.current.value.delegation.peopleScope.organizations
                  .map(
                    (n) =>
                      c.orgs.value.find((o) => o.id === n.organizationId)
                        ?.name ?? "指定组织",
                  )
                  .join("、")
          }}
        </p>
        <p v-else>尚无生效的应用管理委派。</p>
        <p class="info-box">
          管理员失权或交接只改变以后如何维护，不会自动收回员工已持有的角色。
        </p>
        <h3 v-if="audit.length">最近管理操作</h3>
        <ul class="read-list">
          <li v-for="a in audit" :key="a.id">
            {{ a.action }}<small>{{ date(a.createdAt) }}</small>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>
