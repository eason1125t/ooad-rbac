<!-- 功能：共用真实访问验证/状态预览；管理区交由AdminPanels，OA/PMIS保留诚实业务骨架。 -->
<script setup lang="ts">
import { computed, inject, ref } from "vue";
import { api, ApiError } from "@municipal/api";
import { useSession } from "./session";
import AdminPanels from "./AdminPanels.vue";
const props = defineProps<{ page: string }>();
const application = inject<"admin" | "oa" | "pmis">("application")!;
const session = useSession();
const preview = ref(false);
const state = ref("normal");
const status = ref("");
const busy = ref(false);
const titles: Record<string, string> = {
  organizations: "组织与用户",
  roles: "角色与授权",
  applications: "应用与资源",
  audit: "操作审计",
  workspace:
    application === "admin"
      ? "工作台"
      : application === "oa"
        ? "我的公文"
        : "我的工程项目",
};
const heading = computed(() => titles[props.page]);
const states: Record<string, string> = {
  loading: "正在加载，请稍候。",
  empty: "当前范围没有记录。",
  failed: "加载失败，请重试。",
  "401": "登录已过期，请重新登录。",
  "403": "当前账号无访问权限，请联系管理员。",
  syncing: "配置已保存，正在同步；尚未全局生效。",
};
async function probe() {
  busy.value = true;
  status.value = "";
  try {
    status.value = (await api.demo(application)).message;
  } catch (failure) {
    if (failure instanceof ApiError) {
      status.value = `${failure.status} · ${failure.message}（请求编号 ${failure.traceId}）`;
      if (failure.status === 401) session.user = undefined;
    } else status.value = "连接失败，请检查后端是否启动。";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ heading }}</h1>
      <p class="muted">
        {{
          application === "admin"
            ? "围绕人员、角色和应用完成管理任务。"
            : "业务页面骨架，尚未建立业务记录。"
        }}
      </p>
    </div>
    <span class="stage-label">第一轮骨架</span>
  </div>
  <section class="connection-strip" aria-label="真实链路">
    <div>
      <strong>访问验证</strong>
      <p>
        {{
          session.user
            ? `当前账号 ${session.user.username}`
            : "尚未登录，可验证未登录拒绝行为。"
        }}
      </p>
    </div>
    <button :disabled="busy" @click="probe">
      {{ busy ? "验证中…" : "验证访问" }}
    </button>
    <p v-if="status" role="status" class="result">{{ status }}</p>
  </section>
  <section class="prototype-controls">
    <label
      ><input id="prototype-preview" v-model="preview" type="checkbox" />
      显示合成原型数据</label
    ><label
      >状态预览<select v-model="state" aria-label="状态预览">
        <option value="normal">正常</option>
        <option value="loading">加载中</option>
        <option value="empty">空数据</option>
        <option value="failed">失败</option>
        <option value="401">401</option>
        <option value="403">403</option>
        <option value="syncing">同步中</option>
      </select></label
    >
  </section>
  <div
    v-if="state !== 'normal'"
    class="workspace-panel empty-state"
    role="status"
  >
    <h2>{{ heading }}</h2>
    <p>{{ states[state] }}</p>
    <button @click="state = 'normal'">返回正常预览</button>
  </div>
  <div
    v-else-if="!preview && !(application === 'admin' && page === 'workspace')"
    class="workspace-panel empty-state"
  >
    <h2>业务尚未实现</h2>
    <p>
      页面已保留信息结构。选择“显示合成原型数据”审阅流程；管理写入尚不可用。
    </p>
  </div>
  <div v-else class="workspace-panel">
    <p v-if="preview" class="prototype-notice">
      以下为合成原型数据，未从数据库加载，也不会保存。
    </p>
    <AdminPanels
      v-if="application === 'admin'"
      :page="page"
      :preview="preview"
    />
    <section v-else class="detail-panel">
      <h2>{{ application === "oa" ? "公文办理流程" : "项目立项流程" }}</h2>
      <p>本人创建草稿 / 编辑 / 提交 / 具备审批权限者处理</p>
      <p class="muted">
        当前没有真实业务记录。后续同时检查操作权限、本人归属与状态。
      </p>
      <button disabled>
        {{ application === "oa" ? "新建公文（未实现）" : "新建项目（未实现）" }}
      </button>
    </section>
  </div>
</template>
