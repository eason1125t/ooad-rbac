<!-- 功能：五个管理工作区的可交互原型；由Workspace承接真实链路和状态，管理写入始终禁用。 -->
<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useSession } from "./session";
const props = defineProps<{ page: string; preview: boolean }>();
const route = useRoute();
const router = useRouter();
const session = useSession();
const selectedUser = ref("");
const application = ref("oa");
const definitions: Record<string, { id: string; label: string }[]> = {
  organizations: [
    { id: "users", label: "人员与账号" },
    { id: "organization", label: "组织设置" },
    { id: "imports", label: "导入记录" },
  ],
  roles: [
    { id: "basic", label: "基本信息" },
    { id: "permissions", label: "权限配置" },
    { id: "members", label: "角色成员" },
  ],
  applications: [
    { id: "basic", label: "基本信息" },
    { id: "resources", label: "资源权限" },
    { id: "administrators", label: "管理员与范围" },
  ],
};
const tabs = computed(() => definitions[props.page] ?? []);
const activeTab = computed(() =>
  tabs.value.some((tab) => tab.id === route.query.tab)
    ? String(route.query.tab)
    : tabs.value[0]?.id,
);
function selectTab(tab: string) {
  selectedUser.value = "";
  void router.replace({ query: { ...route.query, tab } });
}
const appTitle = computed(() =>
  application.value === "oa" ? "OA公文管理" : "工程项目管理",
);
</script>
<template>
  <section v-if="page === 'workspace'" class="detail-panel">
    <h2>从日常管理任务开始</h2>
    <div class="workbench-layout">
      <div class="task-list">
        <RouterLink to="/organizations"
          >查找人员与分配应用角色
          <small>组织树、账号与授权详情</small></RouterLink
        >
        <RouterLink to="/roles?tab=permissions"
          >配置角色能做什么
          <small>角色权限与成员在同一工作区</small></RouterLink
        >
        <RouterLink to="/applications?tab=administrators"
          >管理应用与管理员范围
          <small>应用、资源权限、委派设置</small></RouterLink
        >
        <RouterLink to="/audit"
          >追踪管理操作 <small>授权记录与变更差异</small></RouterLink
        >
      </div>
      <aside class="scope-summary">
        <h2>当前状态</h2>
        <p>登录账号：{{ session.user?.username ?? "尚未登录" }}</p>
        <p>真实登录与权限演示已接通。</p>
        <p class="muted">
          管理应用、人员范围、到期提醒和审计数据接口待实现，当前不展示虚构统计。
        </p>
      </aside>
    </div>
  </section>
  <template v-else>
    <div v-if="preview" class="preview-context">
      <label
        >原型应用
        <select v-model="application" aria-label="原型应用">
          <option value="oa">OA公文管理</option>
          <option value="pmis">工程项目管理</option>
        </select></label
      >
      <span>示例范围：工程分公司及下级</span>
      <span class="muted">仅切换合成预览，真实委派范围待接入</span>
    </div>
    <nav
      v-if="tabs.length"
      class="workspace-tabs"
      role="tablist"
      aria-label="工作区详情"
    >
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        role="tab"
        :aria-selected="activeTab === tab.id"
        @click="selectTab(tab.id)"
      >
        {{ tab.label }}
      </button>
    </nav>
    <div v-if="page === 'organizations'" class="split-panel">
      <aside class="org-tree">
        <strong>示例市政集团</strong>
        <p>工程分公司</p>
        <button class="tree-selected">工程部（合成）</button>
        <p>综合管理部</p>
        <p class="muted">组织用于选人，不自动授予角色。</p>
      </aside>
      <section class="detail-panel">
        <template v-if="activeTab === 'users'">
          <div class="section-heading">
            <h2>工程部 · 人员与账号</h2>
            <button disabled>新建账号（未实现）</button>
          </div>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>账号</th>
                  <th>人员</th>
                  <th>组织</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>oa-user-1</td>
                  <td>合成员工1</td>
                  <td>工程部</td>
                  <td>
                    <button @click="selectedUser = 'oa-user-1'">
                      查看授权原型
                    </button>
                  </td>
                </tr>
                <tr>
                  <td>pmis-user-1</td>
                  <td>合成员工2</td>
                  <td>工程部</td>
                  <td>
                    <button @click="selectedUser = 'pmis-user-1'">
                      查看授权原型
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <section
            v-if="selectedUser"
            class="inline-detail"
            aria-label="用户授权原型"
          >
            <div class="section-heading">
              <h2>{{ selectedUser }} · 应用授权</h2>
              <button @click="selectedUser = ''">关闭详情</button>
            </div>
            <p>
              {{ appTitle }} / 示例业务角色。这里的人员关联与授权仅为合成预览。
            </p>
            <label class="field"
              >开始时间（含）<input type="datetime-local" /></label
            ><label class="field"
              >结束时间（不含）<input type="datetime-local"
            /></label>
            <button disabled>确认分配角色（未实现）</button>
          </section>
        </template>
        <template v-else-if="activeTab === 'organization'"
          ><h2>组织设置</h2>
          <p>类型：部门　上级：工程分公司</p>
          <p class="muted">维护行政隶属和成员归属，授权由角色单独配置。</p>
          <button disabled>保存组织（未实现）</button></template
        >
        <template v-else
          ><h2>人员导入 · 校验预览</h2>
          <p class="workflow">选择文件 / 解析映射 / 修正错误 / 确认整批提交</p>
          <p>示例第2行：负责人同名，需要唯一账号映射。</p>
          <p>示例第3行：人员直接挂分公司，可接受。</p>
          <button disabled>执行导入（未实现）</button></template
        >
      </section>
    </div>
    <div v-else-if="page === 'roles'" class="split-panel">
      <aside class="org-tree">
        <button class="tree-selected">
          {{ application === "oa" ? "OA文档阅览者" : "PMIS项目成员" }}（示例）
        </button>
        <p>{{ appTitle }}</p>
        <p class="muted">每个业务角色按所属应用管理。</p>
      </aside>
      <section class="detail-panel">
        <template v-if="activeTab === 'basic'"
          ><h2>角色信息</h2>
          <p>所属应用：{{ appTitle }}</p>
          <p>示例说明：允许访问本应用约定功能。</p>
          <button disabled>保存角色（未实现）</button></template
        >
        <template v-else-if="activeTab === 'permissions'"
          ><h2>权限配置</h2>
          <label class="permission-row"
            ><input type="checkbox" checked disabled /> {{ appTitle }} /
            {{ application === "oa" ? "公文查看" : "项目查看" }}</label
          >
          <p class="muted">目录只组织资源。可委派权限和保存接口尚未接入。</p>
          <button disabled>保存角色权限（未实现）</button></template
        >
        <template v-else
          ><h2>角色成员与有效期</h2>
          <p>
            {{
              application === "oa" ? "oa-user-1" : "pmis-user-1"
            }}（合成成员关联）
          </p>
          <label class="field"
            >开始时间（含）<input type="datetime-local" /></label
          ><label class="field"
            >结束时间（不含）<input type="datetime-local"
          /></label>
          <p class="muted">空边界表示无对应限制；结束必须晚于开始。</p>
          <button disabled>保存成员授权（未实现）</button></template
        >
      </section>
    </div>
    <section v-else-if="page === 'applications'" class="detail-panel">
      <template v-if="activeTab === 'basic'"
        ><h2>{{ appTitle }}</h2>
        <p>应用代码：{{ application }}</p>
        <p class="muted">
          业务演示已接入统一身份和权限检查，应用管理写入尚未实现。
        </p>
        <button disabled>保存应用（未实现）</button></template
      >
      <div v-else-if="activeTab === 'resources'" class="resource-layout">
        <aside class="org-tree">
          <strong>{{ appTitle }}</strong>
          <p>功能资源 / 查看</p>
        </aside>
        <section>
          <h2>资源权限</h2>
          <p>
            权限码：{{
              application === "oa" ? "oa:document:view" : "pmis:project:view"
            }}
          </p>
          <p class="muted">
            目录与动作就近展示，新增权限点须匹配真实后端检查。
          </p>
          <button disabled>维护权限点（未实现）</button>
        </section>
      </div>
      <template v-else
        ><h2>管理员与范围</h2>
        <p>示例管理员：{{ application }}-admin</p>
        <p>示例人员范围：工程分公司（含下级）</p>
        <p class="muted">
          平台可显式选择全公司或组织范围。本区仅展示方案，演示账号尚无真实委派管理能力。
        </p>
        <button disabled>保存委派范围（未实现）</button></template
      >
    </section>
    <section v-else-if="page === 'audit'" class="detail-panel">
      <h2>授权操作记录</h2>
      <p class="muted">完整审计接口尚未实现；以下仅为格式示例。</p>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>操作者</th>
              <th>应用</th>
              <th>目标</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>{{ application }}-admin</td>
              <td>{{ appTitle }}</td>
              <td>{{ application }}-user-1</td>
              <td>分配角色（合成记录）</td>
            </tr>
          </tbody>
        </table>
      </div>
      <button disabled>查询审计（未实现）</button>
    </section>
  </template>
</template>
