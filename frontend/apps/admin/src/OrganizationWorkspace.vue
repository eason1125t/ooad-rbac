<!-- 功能：组织、人员、账号真实CRUD与业务范围人员入口；人员换岗使用独立原子表单，不混入普通资料编辑。 -->
<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import { useAdmin, useTask, useDirty, date } from "./context";
import EditorDialog from "./components/EditorDialog.vue";
import PersonPicker from "./components/PersonPicker.vue";
import Pager from "./components/Pager.vue";
import GrantEditor from "./components/GrantEditor.vue";
import AssignmentEditor from "./components/AssignmentEditor.vue";
const c = useAdmin();
useDirty(c.dirty);
const { busy, error, notice, run } = useTask();
const tab = ref(c.platform.value ? "people" : "managed"),
  orgs = ref<Schema<"Organization">[]>([]),
  selectedOrg = ref(""),
  employees = ref<Schema<"EmployeePage">>(),
  accounts = ref<Schema<"ManagedAccountPage">>(),
  managed = ref<Schema<"AccessPersonPage">>(),
  q = ref(""),
  page = ref(1),
  dialog = ref(""),
  selectedEmployee = ref<Schema<"Employee">>(),
  selectedAccount = ref<Schema<"ManagedAccount">>(),
  credential = ref<Schema<"TemporaryCredential">>(),
  grant = ref<{ userId: string; username: string }>(),
  assignment = ref(false),
  movePreview = ref<Schema<"MovePreview">>();
const orgForm = reactive({
  code: "",
  name: "",
  nodeType: "DEPARTMENT" as Schema<"OrganizationCreate">["nodeType"],
  parentId: "",
  managerEmployeeId: "",
  expectedVersion: 0,
});
const personForm = reactive({
  employeeCode: "",
  displayName: "",
  organizationId: "",
  positionName: "",
  phone: "",
  expectedVersion: 0,
});
const accountForm = reactive({ username: "", employeeId: "" });
const moveParent = ref("");
const currentOrg = computed(() =>
  orgs.value.find((o) => o.id === selectedOrg.value),
);
async function load() {
  await run(async () => {
    if (c.platform.value && c.can("admin:organization:view"))
      orgs.value = (await adminApi.organizations()).items;
    if (tab.value === "people" && c.can("admin:employee:view"))
      employees.value = await adminApi.employees({
        q: q.value,
        page: page.value,
        size: 20,
        organizationId: selectedOrg.value || undefined,
        includeDescendants: true,
      });
    else if (tab.value === "accounts" && c.can("admin:account:view"))
      accounts.value = await adminApi.accounts({
        q: q.value,
        page: page.value,
        size: 20,
      });
    else if (!c.platform.value && c.current.value)
      managed.value = await adminApi.people(c.appId.value, {
        q: q.value,
        page: page.value,
        size: 20,
      });
  });
}
watch(tab, () => {
  page.value = 1;
  q.value = "";
  void load();
});
onMounted(load);
function close() {
  if (busy.value) return;
  if (credential.value) {
    credential.value = undefined;
    dialog.value = "";
    void load();
    return;
  }
  if (c.discard()) {
    dialog.value = "";
    movePreview.value = undefined;
  }
}
function editOrg(create: boolean) {
  const o = currentOrg.value;
  Object.assign(orgForm, {
    code: "",
    name: create ? "" : (o?.name ?? ""),
    nodeType: !orgs.value.length ? "HEADQUARTERS" : "DEPARTMENT",
    parentId: selectedOrg.value,
    managerEmployeeId: create ? "" : (o?.managerEmployeeId ?? ""),
    expectedVersion: o?.version ?? 0,
  });
  dialog.value = create ? "new-org" : "edit-org";
  error.value = "";
}
function editPerson(e?: Schema<"Employee">) {
  selectedEmployee.value = e;
  Object.assign(personForm, {
    employeeCode: e?.employeeCode ?? "",
    displayName: e?.displayName ?? "",
    organizationId: e?.organizationId ?? selectedOrg.value,
    positionName: e?.positionName ?? "",
    phone: e?.phone ?? "",
    expectedVersion: e?.version ?? 0,
  });
  dialog.value = e ? "edit-person" : "new-person";
  error.value = "";
}
function newAccount(employee?: Schema<"Employee">) {
  Object.assign(accountForm, { username: "", employeeId: employee?.id ?? "" });
  dialog.value = "new-account";
  error.value = "";
}
async function save() {
  let saved = false;
  await run(async () => {
    switch (dialog.value) {
      case "new-org":
        await adminApi.createOrganization({
          code: orgForm.code,
          name: orgForm.name,
          nodeType: orgForm.nodeType,
          parentId:
            orgForm.nodeType === "HEADQUARTERS"
              ? null
              : orgForm.parentId || null,
        });
        break;
      case "edit-org":
        await adminApi.updateOrganization(selectedOrg.value, {
          name: orgForm.name,
          managerEmployeeId: orgForm.managerEmployeeId || null,
          expectedVersion: orgForm.expectedVersion,
        });
        break;
      case "new-person":
        await adminApi.createEmployee({
          employeeCode: personForm.employeeCode,
          displayName: personForm.displayName,
          organizationId: personForm.organizationId,
          positionName: personForm.positionName || null,
          phone: personForm.phone || null,
        });
        break;
      case "edit-person":
        await adminApi.updateEmployee(selectedEmployee.value!.id, {
          displayName: personForm.displayName,
          phone: personForm.phone || null,
          expectedVersion: personForm.expectedVersion,
        });
        break;
      case "new-account":
        credential.value = await adminApi.createAccount({
          username: accountForm.username,
          employeeId: accountForm.employeeId,
        });
        dialog.value = "credential";
        c.dirty.value = false;
        return;
    }
    saved = true;
    c.dirty.value = false;
    dialog.value = "";
  });
  if (saved) {
    await load();
    await c.refreshOrgs();
    notice.value = "记录已保存。";
  }
}
async function status(
  kind: "organization" | "employee" | "account",
  row: Schema<"Organization"> | Schema<"Employee"> | Schema<"ManagedAccount">,
) {
  if (
    !window.confirm(
      `确定${row.enabled ? "停用" : "启用"}这条${kind === "organization" ? "组织" : kind === "employee" ? "人员" : "账号"}记录？${kind === "employee" && row.enabled ? "离职会停用本人账号并撤回本人角色，其他员工权限不受影响。" : ""}`,
    )
  )
    return;
  let ok = false;
  await run(async () => {
    const request = { enabled: !row.enabled, expectedVersion: row.version };
    if (kind === "organization")
      await adminApi.organizationStatus(row.id, request);
    else if (kind === "employee")
      await adminApi.employeeStatus(row.id, request);
    else await adminApi.accountStatus(row.id, request);
    ok = true;
  });
  if (ok) {
    await load();
    if (kind === "organization") await c.refreshOrgs();
    notice.value = "状态已更新。";
  }
}
async function openPersonAccount(e: Schema<"Employee">) {
  selectedEmployee.value = e;
  await run(async () => {
    const account = await adminApi.employeeAccount(e.id);
    if (account) {
      selectedAccount.value = account;
      grant.value = { userId: account.id, username: account.username };
    } else newAccount(e);
  });
}
async function resetPassword(account: Schema<"ManagedAccount">) {
  if (
    !window.confirm(`重置 ${account.username} 的密码？该账号的旧会话会失效。`)
  )
    return;
  await run(async () => {
    credential.value = await adminApi.resetPassword(
      account.id,
      account.version,
    );
    dialog.value = "credential";
  });
}
async function prepareMove() {
  await run(async () => {
    movePreview.value = await adminApi.previewMove(selectedOrg.value, {
      parentId: moveParent.value,
      expectedVersion: currentOrg.value!.version,
    });
  });
}
async function commitMove() {
  let ok = false;
  await run(async () => {
    await adminApi.moveOrganization(
      selectedOrg.value,
      movePreview.value!.token,
    );
    ok = true;
    dialog.value = "";
    movePreview.value = undefined;
    c.dirty.value = false;
  });
  if (ok) {
    await load();
    await c.refreshOrgs();
    notice.value = "组织已移动。";
  }
}
async function transfer(e: Schema<"Employee">) {
  selectedEmployee.value = e;
  await run(async () => {
    selectedAccount.value = (await adminApi.employeeAccount(e.id)) ?? undefined;
    assignment.value = true;
  });
}
function depth(id: string) {
  let n = orgs.value.find((o) => o.id === id),
    depth = 0;
  while (n?.parentId && depth < 7) {
    depth++;
    n = orgs.value.find((o) => o.id === n!.parentId);
  }
  return depth;
}
function orgName(id: string | null) {
  return (
    orgs.value.find((o) => o.id === id)?.name ??
    c.orgs.value.find((o) => o.id === id)?.name ??
    "未关联组织"
  );
}
</script>
<template>
  <section class="workspace-panel">
    <div class="tab-bar" role="tablist">
      <template v-if="c.platform.value"
        ><button
          v-for="t in [
            { id: 'people', name: '人员档案' },
            { id: 'accounts', name: '登录账号' },
            { id: 'organizations', name: '组织设置' },
          ]"
          :key="t.id"
          role="tab"
          :disabled="busy"
          :aria-selected="tab === t.id"
          @click="tab = t.id"
        >
          {{ t.name }}
        </button></template
      ><button v-else role="tab" aria-selected="true">本应用负责人员</button>
    </div>
    <div class="pane">
      <p v-if="error" class="error-box" role="alert">{{ error }}</p>
      <p v-if="notice" class="success-box" role="status">{{ notice }}</p>
      <div class="section-heading">
        <h2>
          {{
            tab === "people"
              ? "人员档案"
              : tab === "accounts"
                ? "登录账号"
                : tab === "organizations"
                  ? "组织目录"
                  : "由你维护的人员"
          }}
        </h2>
        <div class="inline-actions">
          <button :disabled="busy" @click="load">刷新列表</button
          ><button
            v-if="tab === 'people' && c.can('admin:employee:create')"
            class="primary"
            :disabled="busy"
            @click="editPerson()"
          >
            新建人员</button
          ><button
            v-if="tab === 'accounts' && c.can('admin:account:create')"
            class="primary"
            :disabled="busy"
            @click="newAccount()"
          >
            新建账号</button
          ><button
            v-if="tab === 'organizations' && c.can('admin:organization:create')"
            class="primary"
            :disabled="busy"
            @click="editOrg(true)"
          >
            新建组织
          </button>
        </div>
      </div>
      <form
        v-if="tab !== 'organizations'"
        class="search-line"
        @submit.prevent="
          page = 1;
          load();
        "
      >
        <input
          v-model="q"
          maxlength="100"
          aria-label="搜索人员或账号"
          placeholder="搜索姓名、账号或人员编码"
        /><select
          v-if="tab === 'people'"
          v-model="selectedOrg"
          aria-label="按组织筛选"
        >
          <option value="">全部组织</option>
          <option v-for="o in orgs" :key="o.id" :value="o.id">
            {{ o.name }}
          </option></select
        ><button :disabled="busy">搜索</button>
      </form>
      <p v-if="busy" role="status">正在处理，请稍候…</p>
      <template v-if="tab === 'organizations'"
        ><div class="directory-layout">
          <aside class="directory-tree" aria-label="组织目录">
            <button
              v-for="o in orgs"
              :key="o.id"
              :class="{ selected: selectedOrg === o.id }"
              :style="{ paddingLeft: `${12 + depth(o.id) * 16}px` }"
              @click="selectedOrg = o.id"
            >
              {{ o.name }} <small v-if="!o.enabled">停用</small>
            </button>
            <p v-if="!orgs.length" class="muted">
              尚未建立组织，请先新建总公司。
            </p>
          </aside>
          <div class="pane" v-if="currentOrg">
            <h3>{{ currentOrg.name }}</h3>
            <dl class="facts-list">
              <dt>组织编码</dt>
              <dd>{{ currentOrg.code }}</dd>
              <dt>组织类型</dt>
              <dd>
                {{
                  {
                    HEADQUARTERS: "总公司",
                    BRANCH: "分公司",
                    DEPARTMENT: "部门",
                  }[currentOrg.nodeType]
                }}
              </dd>
              <dt>上级组织</dt>
              <dd>
                {{
                  currentOrg.parentId
                    ? orgName(currentOrg.parentId)
                    : "行政根组织"
                }}
              </dd>
              <dt>状态</dt>
              <dd>{{ currentOrg.enabled ? "启用" : "停用" }}</dd>
            </dl>
            <div class="inline-actions">
              <button
                :disabled="busy || !c.can('admin:organization:update')"
                @click="editOrg(false)"
              >
                编辑组织</button
              ><button
                :disabled="busy || !c.can('admin:organization:status')"
                @click="status('organization', currentOrg)"
              >
                {{ currentOrg.enabled ? "停用组织" : "启用组织" }}</button
              ><button
                v-if="currentOrg.parentId"
                :disabled="busy || !c.can('admin:organization:move')"
                @click="
                  dialog = 'move-org';
                  moveParent = '';
                  movePreview = undefined;
                "
              >
                移动组织
              </button>
            </div>
            <p class="muted">
              已有授权或组织范围引用时，系统会保护性拒绝移动；请勿用直接修改数据库绕过检查。
            </p>
          </div>
        </div></template
      >
      <div v-else class="table-scroll">
        <table v-if="tab === 'people'">
          <thead>
            <tr>
              <th>姓名 / 编码</th>
              <th>主要组织 / 岗位</th>
              <th>联系电话</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in employees?.items" :key="e.id">
              <td>
                <strong>{{ e.displayName }}</strong
                ><small>{{ e.employeeCode }}</small>
              </td>
              <td>
                {{ orgName(e.organizationId)
                }}<small>{{ e.positionName ?? "未填写岗位" }}</small>
              </td>
              <td>{{ e.phone ?? "未填写" }}</td>
              <td>
                <span class="status-tag" :class="{ inactive: !e.enabled }">{{
                  e.enabled ? "在职启用" : "停用"
                }}</span>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    :disabled="busy || !c.can('admin:employee:update')"
                    @click="editPerson(e)"
                  >
                    编辑资料</button
                  ><button
                    :disabled="busy || !c.can('admin:account:view')"
                    @click="openPersonAccount(e)"
                  >
                    账号与授权</button
                  ><button
                    :disabled="busy || !c.can('admin:employee:transfer')"
                    @click="transfer(e)"
                  >
                    调岗换角</button
                  ><button
                    :disabled="busy || !c.can('admin:employee:status')"
                    @click="status('employee', e)"
                  >
                    {{ e.enabled ? "停用人员" : "启用人员" }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="employees && !employees.items.length">
              <td colspan="5">
                没有匹配人员。可新建人员，或切换到“登录账号”查看独立演示账号。
              </td>
            </tr>
          </tbody>
        </table>
        <table v-else-if="tab === 'accounts'">
          <thead>
            <tr>
              <th>账号</th>
              <th>人员关联</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in accounts?.items" :key="a.id">
              <td>
                <strong>{{ a.username }}</strong
                ><small v-if="a.forcePasswordChange">首次登录需改密</small>
              </td>
              <td>
                {{ a.employeeId ? "已关联人员档案" : "独立演示 / 引导账号" }}
              </td>
              <td>
                <span class="status-tag" :class="{ inactive: !a.enabled }">{{
                  a.enabled ? "启用" : "停用"
                }}</span>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    :disabled="
                      busy || !c.current.value || !c.can('admin:grant:view')
                    "
                    @click="grant = { userId: a.id, username: a.username }"
                  >
                    角色与权限</button
                  ><button
                    :disabled="busy || !c.can('admin:account:reset-password')"
                    @click="resetPassword(a)"
                  >
                    重置密码</button
                  ><button
                    :disabled="busy || !c.can('admin:account:status')"
                    @click="status('account', a)"
                  >
                    {{ a.enabled ? "停用账号" : "启用账号" }}
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="accounts && !accounts.items.length">
              <td colspan="4">没有匹配账号。</td>
            </tr>
          </tbody>
        </table>
        <table v-else>
          <thead>
            <tr>
              <th>账号 / 人员</th>
              <th>主要组织</th>
              <th>状态</th>
              <th>授权</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in managed?.items" :key="p.userId">
              <td>
                <strong>{{ p.username }}</strong
                ><small>{{ p.displayName }}</small>
              </td>
              <td>{{ orgName(p.organizationId) }}</td>
              <td>{{ p.enabled ? "启用" : "停用" }}</td>
              <td>
                <button
                  :disabled="busy"
                  @click="grant = { userId: p.userId, username: p.username }"
                >
                  角色与权限
                </button>
              </td>
            </tr>
            <tr v-if="managed && !managed.items.length">
              <td colspan="4">
                当前应用没有由你负责的人员，请联系平台指定维护责任。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager
        v-if="tab !== 'organizations' && (employees || accounts || managed)"
        :page="page"
        :size="20"
        :total="
          tab === 'people'
            ? (employees?.total ?? 0)
            : tab === 'accounts'
              ? (accounts?.total ?? 0)
              : (managed?.total ?? 0)
        "
        :busy="busy"
        @change="
          (p) => {
            page = p;
            load();
          }
        "
      />
      <p class="muted">
        人员、账号与角色分别管理。启停保留历史，授权关系可在详情中撤回。
      </p>
    </div>
  </section>
  <EditorDialog
    v-if="dialog"
    :title="
      {
        'new-org': '新建组织',
        'edit-org': '编辑组织',
        'move-org': '移动组织',
        'new-person': '新建人员',
        'edit-person': '编辑人员资料',
        'new-account': '新建登录账号',
        credential: '账号临时密码',
      }[dialog] ?? '管理记录'
    "
    :busy="busy"
    @close="close"
    ><p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <div v-if="dialog === 'credential' && credential">
      <p>
        账号
        {{ credential.account.username }}
        已更新。临时密码仅在这里显示，关闭后不再保留。
      </p>
      <label
        >临时密码<input
          :value="credential.temporaryPassword"
          type="password"
          readonly
          autocomplete="off"
          aria-label="临时密码"
      /></label>
      <p>
        {{
          credential.account.forcePasswordChange
            ? "首次登录必须修改密码。"
            : "当前为演示账号策略。"
        }}
      </p>
      <button
        @click="
          credential = undefined;
          dialog = '';
          load();
        "
      >
        我已妥善记录，关闭
      </button>
    </div>
    <form
      v-else-if="dialog.includes('org') && dialog !== 'move-org'"
      class="form-grid"
      :inert="busy"
      @submit.prevent="save"
      @input="c.dirty.value = true"
    >
      <label v-if="dialog === 'new-org'"
        >组织编码<input
          v-model="orgForm.code"
          required
          minlength="3"
          maxlength="64"
          pattern="[a-z0-9][a-z0-9._-]{2,63}"
          placeholder="如 department-a" /></label
      ><label
        >组织名称<input
          v-model="orgForm.name"
          required
          maxlength="160" /></label
      ><label v-if="dialog === 'new-org'"
        >组织类型<select v-model="orgForm.nodeType">
          <option value="HEADQUARTERS">总公司</option>
          <option value="BRANCH">分公司</option>
          <option value="DEPARTMENT">部门</option>
        </select></label
      ><label v-if="dialog === 'new-org' && orgForm.nodeType !== 'HEADQUARTERS'"
        >上级组织<select v-model="orgForm.parentId" required>
          <option value="">请选择上级</option>
          <option
            v-for="o in orgs.filter((n) => n.enabled)"
            :key="o.id"
            :value="o.id"
          >
            {{ o.name }}
          </option>
        </select></label
      ><PersonPicker
        v-if="dialog === 'edit-org'"
        v-model="orgForm.managerEmployeeId"
        mode="employee"
        label="组织负责人"
      />
      <p class="muted">负责人可留空；同一上级下组织名称不得重复。</p>
      <button class="primary" :disabled="busy">保存组织</button>
    </form>
    <form
      v-else-if="dialog === 'new-person' || dialog === 'edit-person'"
      class="form-grid"
      :inert="busy"
      @submit.prevent="save"
      @input="c.dirty.value = true"
    >
      <label v-if="dialog === 'new-person'"
        >人员编码<input
          v-model="personForm.employeeCode"
          required
          minlength="3"
          maxlength="64"
          pattern="[a-z0-9][a-z0-9._-]{2,63}" /></label
      ><label
        >姓名<input
          v-model="personForm.displayName"
          required
          maxlength="100" /></label
      ><template v-if="dialog === 'new-person'"
        ><label
          >主要组织<select v-model="personForm.organizationId" required>
            <option value="">请选择组织</option>
            <option
              v-for="o in orgs.filter((n) => n.enabled)"
              :key="o.id"
              :value="o.id"
            >
              {{ o.name }}
            </option>
          </select></label
        ><label
          >岗位<input
            v-model="personForm.positionName"
            maxlength="100" /></label></template
      ><label
        >联系电话<input v-model="personForm.phone" type="tel" maxlength="32"
      /></label>
      <p class="muted">
        变更组织或岗位请使用“调岗换角”；新建人员不会自动建号或授予角色。
      </p>
      <button class="primary" :disabled="busy">保存人员</button>
    </form>
    <form
      v-else-if="dialog === 'new-account'"
      class="form-grid"
      :inert="busy"
      @submit.prevent="save"
      @input="c.dirty.value = true"
    >
      <label
        >登录账号<input
          v-model="accountForm.username"
          required
          minlength="3"
          maxlength="64"
          pattern="[a-z0-9][a-z0-9._-]{2,63}"
          placeholder="如 employee-2" /></label
      ><PersonPicker
        v-model="accountForm.employeeId"
        mode="employee"
        label="关联人员"
      />
      <p class="muted">
        每名人员最多一个账号。系统按当前环境生成临时密码，不会自动授予业务角色。
      </p>
      <button class="primary" :disabled="busy || !accountForm.employeeId">
        创建账号
      </button>
    </form>
    <form
      v-else-if="dialog === 'move-org'"
      class="form-grid"
      :inert="busy"
      @submit.prevent="movePreview ? commitMove() : prepareMove()"
    >
      <label
        >新上级组织<select
          v-model="moveParent"
          required
          :disabled="!!movePreview"
          @change="c.dirty.value = true"
        >
          <option value="">请选择新上级</option>
          <option
            v-for="o in orgs.filter((n) => n.id !== selectedOrg && n.enabled)"
            :key="o.id"
            :value="o.id"
          >
            {{ o.name }}
          </option>
        </select></label
      >
      <p v-if="movePreview">
        移动 {{ currentOrg?.name }} 到 {{ orgName(moveParent) }}；影响
        {{ movePreview.affectedEmployees }} 名人员。有效至
        {{ date(movePreview.expiresAt) }}。
      </p>
      <div class="form-actions">
        <button
          v-if="movePreview"
          type="button"
          @click="movePreview = undefined"
        >
          返回编辑</button
        ><button class="primary" :disabled="busy">
          {{ movePreview ? "确认移动" : "预览移动" }}
        </button>
      </div>
    </form></EditorDialog
  >
  <GrantEditor
    v-if="grant"
    :user-id="grant.userId"
    :username="grant.username"
    @close="grant = undefined"
    @saved="load"
  /><AssignmentEditor
    v-if="assignment && selectedEmployee"
    :employee="selectedEmployee"
    :account="selectedAccount ?? null"
    @close="assignment = false"
    @saved="
      assignment = false;
      load();
    "
  />
</template>
