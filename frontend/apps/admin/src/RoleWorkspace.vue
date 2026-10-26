<!-- 功能：真实角色目录、权限定义预览、成员添加/撤回及维护人交接；严格使用服务端角色与授权版本。 -->
<script setup lang="ts">
import { ref, reactive, onMounted, computed } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import {
  clone,
  useAdmin,
  useTask,
  useDirty,
  kinds,
  scopes,
  date,
  inputTime,
  utc,
} from "./context";
import EditorDialog from "./components/EditorDialog.vue";
import PeopleScopeEditor from "./components/PeopleScopeEditor.vue";
import ScopeEditor from "./components/ScopeEditor.vue";
import PersonPicker from "./components/PersonPicker.vue";
import DelegationPicker from "./components/DelegationPicker.vue";
import Pager from "./components/Pager.vue";
import PreviewSummary from "./components/PreviewSummary.vue";
import GrantEditor from "./components/GrantEditor.vue";
const c = useAdmin();
useDirty(c.dirty);
const { busy, error, notice, run } = useTask();
const roles = ref<Schema<"AccessRolePage">>(),
  page = ref(1),
  q = ref(""),
  role = ref<Schema<"AccessRole">>(),
  tab = ref("basic"),
  catalog = ref<Schema<"AccessPermission">[]>([]),
  definition = ref<Schema<"AccessRolePermissions">>(),
  permissions = ref<Schema<"AccessPermissionGrant">[]>([]),
  members = ref<Schema<"AccessRoleMemberPage">>(),
  memberPage = ref(1),
  memberQ = ref(""),
  dialog = ref(""),
  preview = ref<Schema<"AccessPreview">>(),
  grant = ref<{ userId: string; username: string }>(),
  memberUser = ref(""),
  memberFrom = ref(""),
  memberTo = ref(""),
  newOwner = ref("");
const form = reactive<Schema<"AccessRoleCreate">>({
  code: "",
  name: "",
  kind: "COMMON",
  ownerUserId: null,
  personnelScope: { allCompany: true, organizations: [] },
});
const editingPermissions = computed(
  () => !!role.value?.canEdit && c.can("admin:role:permissions"),
);
async function load(p = page.value) {
  page.value = p;
  await run(async () => {
    roles.value = await adminApi.roles(c.appId.value, {
      page: p,
      size: 20,
      q: q.value,
    });
    catalog.value = (await adminApi.permissions(c.appId.value)).items;
  });
}
async function select(r: Schema<"AccessRole">) {
  if (!c.discard()) return;
  await run(async () => {
    role.value = await adminApi.role(c.appId.value, r.id);
    definition.value = await adminApi.rolePermissions(c.appId.value, r.id);
    permissions.value = definition.value.permissions.map((p) => ({
      permissionId: p.permission.id,
      scope: clone(p.scope),
      validFrom: p.validFrom,
      validTo: p.validTo,
    }));
    members.value = await adminApi.members(c.appId.value, r.id, {
      page: 1,
      size: 20,
      q: "",
    });
    memberPage.value = 1;
    memberQ.value = "";
    preview.value = undefined;
    tab.value = "basic";
    if (role.value.ownerUserId && c.platform.value)
      void c.resolveAccountName(role.value.ownerUserId);
  });
}
async function refreshRole() {
  if (!role.value) return;
  const id = role.value.id;
  await run(async () => {
    role.value = await adminApi.role(c.appId.value, id);
    definition.value = await adminApi.rolePermissions(c.appId.value, id);
    permissions.value = definition.value.permissions.map((p) => ({
      permissionId: p.permission.id,
      scope: clone(p.scope),
      validFrom: p.validFrom,
      validTo: p.validTo,
    }));
    members.value = await adminApi.members(c.appId.value, id, {
      page: memberPage.value,
      size: 20,
      q: memberQ.value,
    });
  });
}
async function refreshAll() {
  if (!c.discard()) return;
  await load();
  if (!error.value) await refreshRole();
}
function changed() {
  preview.value = undefined;
  c.dirty.value = true;
}
function newRole() {
  if (!c.discard()) return;
  Object.assign(form, {
    code: "",
    name: "",
    kind:
      c.appId.value === "1"
        ? "PLATFORM"
        : c.platform.value
          ? "COMMON"
          : "SCOPED",
    ownerUserId: c.platform.value ? null : c.session.user!.id,
    personnelScope: clone(
      c.current.value?.delegation?.peopleScope ?? {
        allCompany: true,
        organizations: [],
      },
    ),
  });
  dialog.value = "create";
  error.value = "";
}
function editRole() {
  if (!role.value || !c.discard()) return;
  Object.assign(form, {
    code: role.value.code,
    name: role.value.name,
    kind: role.value.kind,
    ownerUserId: role.value.ownerUserId,
    personnelScope: clone(role.value.personnelScope),
  });
  dialog.value = "edit";
  error.value = "";
}
function close() {
  if (!busy.value && c.discard()) {
    dialog.value = "";
    preview.value = undefined;
  }
}
async function saveRole() {
  let saved: Schema<"AccessRole"> | undefined;
  await run(async () => {
    saved =
      dialog.value === "create"
        ? await adminApi.createRole(c.appId.value, {
            ...form,
            ownerUserId: form.kind === "SCOPED" ? form.ownerUserId : null,
            personnelScope:
              form.kind === "SCOPED"
                ? form.personnelScope
                : { allCompany: true, organizations: [] },
          })
        : await adminApi.updateRole(c.appId.value, role.value!.id, {
            name: form.name,
            personnelScope: form.personnelScope,
            expectedVersion: role.value!.version,
          });
    c.dirty.value = false;
    dialog.value = "";
  });
  if (saved) {
    await load();
    await select(saved);
    notice.value = "角色已保存。";
  }
}
async function status() {
  if (
    !role.value ||
    !window.confirm(
      `确定${role.value.enabled ? "停用" : "启用"}角色“${role.value.name}”？停用该角色会影响其全部成员。`,
    )
  )
    return;
  let ok = false;
  await run(async () => {
    role.value = await adminApi.roleStatus(c.appId.value, role.value!.id, {
      enabled: !role.value!.enabled,
      expectedVersion: role.value!.version,
    });
    ok = true;
  });
  if (ok) {
    await load();
    await refreshRole();
    notice.value = "角色状态已更新。";
  }
}
function toggle(p: Schema<"AccessPermission">, checked: boolean) {
  if (checked)
    permissions.value.push({
      permissionId: p.id,
      scope: {
        kind: c.appId.value === "1" ? "ALL" : "SELF",
        organizationIds: [],
      },
      validFrom: null,
      validTo: null,
    });
  else
    permissions.value = permissions.value.filter(
      (n) => n.permissionId !== p.id,
    );
  changed();
}
async function preparePermissions() {
  await run(async () => {
    preview.value = await adminApi.previewPermissions(
      c.appId.value,
      role.value!.id,
      {
        expectedVersion: definition.value!.version,
        permissions: permissions.value,
      },
    );
  });
}
async function commitPermissions() {
  let ok = false;
  await run(async () => {
    await adminApi.commitPermissions(
      c.appId.value,
      role.value!.id,
      preview.value!.token,
    );
    preview.value = undefined;
    c.dirty.value = false;
    ok = true;
  });
  if (ok) {
    await refreshRole();
    await load();
    notice.value = "角色权限已保存。";
  }
}
async function loadMembers(p = memberPage.value) {
  memberPage.value = p;
  await run(async () => {
    members.value = await adminApi.members(c.appId.value, role.value!.id, {
      page: p,
      size: 20,
      q: memberQ.value,
    });
  });
}
async function addMember() {
  let ok = false;
  await run(async () => {
    const target = await adminApi.userRoles(c.appId.value, memberUser.value);
    await adminApi.addMembers(c.appId.value, role.value!.id, {
      expectedVersion: role.value!.version,
      members: [
        {
          userId: memberUser.value,
          expectedVersion: target.version,
          validFrom: utc(memberFrom.value),
          validTo: utc(memberTo.value),
        },
      ],
    });
    dialog.value = "";
    c.dirty.value = false;
    ok = true;
  });
  if (ok) {
    await refreshRole();
    await load();
    notice.value = "成员已加入角色。";
  }
}
async function remove(m: Schema<"AccessRoleMember">) {
  if (
    !window.confirm(
      `从“${role.value?.name}”移除 ${m.username}？其他角色来源不会被删除。`,
    )
  )
    return;
  let ok = false;
  await run(async () => {
    await adminApi.removeMember(c.appId.value, role.value!.id, {
      userId: m.userId,
      expectedVersion: role.value!.version,
      expectedUserVersion: m.grantVersion,
    });
    ok = true;
  });
  if (ok) {
    await refreshRole();
    await load();
    notice.value = "角色成员关系已移除。";
  }
}
async function prepareOwner() {
  await run(async () => {
    preview.value = await adminApi.previewOwner(c.appId.value, role.value!.id, {
      expectedVersion: role.value!.version,
      newAdministratorUserId: newOwner.value,
    });
  });
}
async function commitOwner() {
  let ok = false;
  await run(async () => {
    await adminApi.commitOwner(
      c.appId.value,
      role.value!.id,
      preview.value!.token,
    );
    c.dirty.value = false;
    dialog.value = "";
    preview.value = undefined;
    ok = true;
  });
  if (ok) {
    await refreshRole();
    await load();
    notice.value = "角色维护责任已交接，员工已有角色未改变。";
  }
}
function scopeText(s: Schema<"AccessScope">) {
  return (
    scopes[s.kind] +
    (s.organizationIds.length
      ? "：" +
        s.organizationIds
          .map(
            (id) =>
              c.orgs.value.find((o) => o.id === id)?.name ?? "原有组织范围",
          )
          .join("、")
      : "")
  );
}
onMounted(() => load());
</script>
<template>
  <section class="workspace-panel">
    <div class="pane">
      <div class="section-heading">
        <div>
          <h2>{{ c.current.value?.name }} · 角色目录</h2>
          <p class="muted">权限、成员与有效期都在角色详情中维护。</p>
        </div>
        <button
          class="primary"
          :disabled="busy || !c.can('admin:role:create')"
          @click="newRole"
        >
          新建角色
        </button>
      </div>
      <p v-if="error" role="alert" class="error-box">{{ error }}</p>
      <p v-if="notice" role="status" class="success-box">{{ notice }}</p>
      <form class="search-line" @submit.prevent="load(1)">
        <input
          v-model="q"
          maxlength="100"
          aria-label="搜索角色"
          placeholder="角色名称或编码"
        /><button :disabled="busy">搜索</button
        ><button type="button" :disabled="busy" @click="refreshAll">
          刷新列表
        </button>
      </form>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>角色</th>
              <th>类型 / 维护人</th>
              <th>状态</th>
              <th>成员</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in roles?.items"
              :key="r.id"
              :class="{ selected: role?.id === r.id }"
            >
              <td>
                <strong>{{ r.name }}</strong
                ><small>{{ r.code }}</small>
              </td>
              <td>
                {{ kinds[r.kind]
                }}<small v-if="r.ownerUserId">{{
                  r.ownerUserId === c.session.user?.id
                    ? "由你维护"
                    : c.accountName(r.ownerUserId)
                }}</small>
              </td>
              <td>
                <span class="status-tag" :class="{ inactive: !r.enabled }">{{
                  r.enabled ? "启用" : "停用"
                }}</span>
              </td>
              <td>{{ r.memberCount }}</td>
              <td>
                <button :disabled="busy" @click="select(r)">管理角色</button>
              </td>
            </tr>
            <tr v-if="roles && !roles.items.length">
              <td colspan="5">
                当前范围没有角色。可新建角色，或调整搜索条件。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager
        v-if="roles"
        :page="page"
        :size="20"
        :total="roles.total"
        :busy="busy"
        @change="load"
      />
    </div>
    <section v-if="role" class="object-detail">
      <div class="section-heading pane">
        <div>
          <h2>{{ role.name }}</h2>
          <span
            >{{ kinds[role.kind] }} · {{ role.enabled ? "启用" : "停用" }}</span
          >
        </div>
        <button :disabled="busy" @click="c.discard() && refreshRole()">
          重新加载详情
        </button>
      </div>
      <div class="tab-bar" role="tablist">
        <button
          v-for="t in [
            { id: 'basic', name: '基本信息' },
            { id: 'permissions', name: '权限配置' },
            { id: 'members', name: '角色成员' },
          ]"
          :key="t.id"
          role="tab"
          :aria-selected="tab === t.id"
          @click="tab = t.id"
        >
          {{ t.name }}
        </button>
      </div>
      <div class="pane" v-if="tab === 'basic'">
        <dl class="facts-list">
          <dt>角色编码</dt>
          <dd>{{ role.code }}</dd>
          <dt>人员适用范围</dt>
          <dd>
            {{
              role.personnelScope.allCompany
                ? "全公司"
                : role.personnelScope.organizations
                    .map(
                      (n) =>
                        (c.orgs.value.find((o) => o.id === n.organizationId)
                          ?.name ?? "指定组织") +
                        (n.includeDescendants ? "及下级" : ""),
                    )
                    .join("、")
            }}
          </dd>
          <dt>维护方式</dt>
          <dd>
            {{
              role.kind === "COMMON"
                ? "由平台维护，业务管理员按白名单分配"
                : role.kind === "SCOPED"
                  ? "由指定业务管理员在完整适用范围内维护"
                  : "由平台维护"
            }}
          </dd>
        </dl>
        <div class="inline-actions">
          <button
            :disabled="busy || !role.canEdit || !c.can('admin:role:update')"
            @click="editRole"
          >
            编辑角色</button
          ><button
            :disabled="busy || !role.canEdit || !c.can('admin:role:status')"
            @click="status"
          >
            {{ role.enabled ? "停用角色" : "启用角色" }}</button
          ><button
            v-if="c.platform.value && role.kind === 'SCOPED'"
            :disabled="busy || !c.can('admin:role:transfer')"
            @click="
              dialog = 'owner';
              newOwner = '';
              preview = undefined;
            "
          >
            交接维护人
          </button>
        </div>
        <p class="muted">
          管理员个人失权不影响员工已授角色；主动停用这个角色则会影响它的所有成员。
        </p>
      </div>
      <div class="pane" v-else-if="tab === 'permissions'">
        <p v-if="!editingPermissions" class="info-box">
          此角色权限当前只读。通用角色由平台维护；专用角色还须满足完整人员范围与维护责任。
        </p>
        <p class="muted">
          时间按北京时间显示，结束时刻不含。未勾选的权限不进入新集合；旧权限不会被前端静默过滤。
        </p>
        <template v-if="!preview"
          ><div class="permission-catalog" :inert="busy">
            <div v-for="p in catalog" :key="p.id" class="permission-item">
              <label class="permission-title"
                ><input
                  type="checkbox"
                  :checked="permissions.some((n) => n.permissionId === p.id)"
                  :disabled="
                    busy ||
                    !editingPermissions ||
                    (!c.platform.value &&
                      !c.current.value?.delegablePermissions.some(
                        (n) => n.permissionId === p.id,
                      ) &&
                      !permissions.some((n) => n.permissionId === p.id)) ||
                    ((!p.enabled || p.implementationStatus !== 'IMPLEMENTED') &&
                      !permissions.some((n) => n.permissionId === p.id))
                  "
                  @change="
                    toggle(p, ($event.target as HTMLInputElement).checked)
                  "
                /><strong>{{ p.name }}</strong
                ><span>{{ p.code }}</span
                ><small
                  v-if="!p.enabled || p.implementationStatus !== 'IMPLEMENTED'"
                  >不可新授予，可移除旧配置</small
                ></label
              ><template
                v-for="g in permissions.filter((n) => n.permissionId === p.id)"
                :key="g.permissionId"
                ><ScopeEditor
                  v-model="g.scope"
                  :disabled="
                    !editingPermissions || busy || c.appId.value === '1'
                  "
                  :capabilities="
                    c.platform.value
                      ? undefined
                      : (c.current.value?.delegablePermissions.find(
                          (n) => n.permissionId === p.id,
                        )?.capabilities ?? {
                          all: false,
                          self: false,
                          organizationIds: [],
                          subtreeRootIds: [],
                        })
                  "
                  @update:model-value="changed" />
                <div class="time-pair">
                  <label
                    >权限生效时间<input
                      type="datetime-local"
                      :value="inputTime(g.validFrom)"
                      :disabled="!editingPermissions || busy"
                      @change="
                        g.validFrom = utc(
                          ($event.target as HTMLInputElement).value,
                        );
                        changed();
                      " /></label
                  ><label
                    >权限结束时间（不含）<input
                      type="datetime-local"
                      :value="inputTime(g.validTo)"
                      :disabled="!editingPermissions || busy"
                      @change="
                        g.validTo = utc(
                          ($event.target as HTMLInputElement).value,
                        );
                        changed();
                      "
                  /></label></div
              ></template>
            </div>
          </div>
          <button
            class="primary"
            :disabled="busy || !editingPermissions"
            @click="preparePermissions"
          >
            预览权限变更
          </button></template
        ><PreviewSummary
          v-else
          :preview="preview"
          :busy="busy"
          @back="preview = undefined"
          @confirm="commitPermissions"
          ><p>角色：{{ role.name }}</p>
          <ul>
            <li v-for="p in permissions" :key="p.permissionId">
              {{ catalog.find((n) => n.id === p.permissionId)?.name }} ·
              {{ scopeText(p.scope) }} · {{ date(p.validFrom) }} 至
              {{ date(p.validTo) }}
            </li>
            <li
              v-for="p in definition?.permissions.filter(
                (old) =>
                  !permissions.some(
                    (n) => n.permissionId === old.permission.id,
                  ),
              )"
              :key="p.permission.id"
            >
              撤回 {{ p.permission.name }}
            </li>
          </ul>
          <strong v-if="!permissions.length"
            >新权限集合为空。</strong
          ></PreviewSummary
        >
      </div>
      <div class="pane" v-else>
        <div class="section-heading">
          <h3>角色成员</h3>
          <button
            :disabled="busy || !role.canAssign || !c.can('admin:role:members')"
            @click="
              dialog = 'member';
              memberUser = '';
              memberFrom = '';
              memberTo = '';
            "
          >
            添加成员
          </button>
        </div>
        <form class="search-line" @submit.prevent="loadMembers(1)">
          <input
            v-model="memberQ"
            aria-label="搜索角色成员"
            maxlength="100"
            placeholder="搜索账号或姓名"
          /><button :disabled="busy">搜索成员</button>
        </form>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>账号</th>
                <th>有效期（结束不含）</th>
                <th>授予时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in members?.items" :key="m.userId">
                <td>{{ m.username }}</td>
                <td>{{ date(m.validFrom) }} 至 {{ date(m.validTo) }}</td>
                <td>{{ date(m.grantedAt) }}</td>
                <td>
                  <div class="row-actions">
                    <button
                      :disabled="busy"
                      @click="
                        grant = { userId: m.userId, username: m.username }
                      "
                    >
                      人员完整授权</button
                    ><button
                      :disabled="busy || !c.can('admin:role:members')"
                      @click="remove(m)"
                    >
                      移除成员
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="members && !members.items.length">
                <td colspan="4">当前范围没有此角色成员。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <Pager
          v-if="members"
          :page="memberPage"
          :size="20"
          :total="members.total"
          :busy="busy"
          @change="loadMembers"
        />
      </div>
    </section>
  </section>
  <EditorDialog
    v-if="dialog"
    :title="
      dialog === 'create'
        ? '新建角色'
        : dialog === 'edit'
          ? '编辑角色'
          : dialog === 'member'
            ? '添加角色成员'
            : '交接角色维护人'
    "
    :busy="busy"
    @close="close"
    ><p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <form
      v-if="dialog === 'create' || dialog === 'edit'"
      class="form-grid"
      :inert="busy"
      @submit.prevent="saveRole"
      @input="changed"
    >
      <label v-if="dialog === 'create'"
        >角色编码<input
          v-model="form.code"
          required
          minlength="3"
          maxlength="64"
          pattern="[a-z0-9][a-z0-9._-]{2,63}" /></label
      ><label
        >角色名称<input v-model="form.name" required maxlength="100" /></label
      ><label
        >角色类型<select
          v-model="form.kind"
          :disabled="
            dialog === 'edit' || !c.platform.value || c.appId.value === '1'
          "
        >
          <option value="PLATFORM" v-if="c.appId.value === '1'">
            平台角色
          </option>
          <template v-else
            ><option value="COMMON">通用角色（平台维护）</option>
            <option value="SCOPED">专用角色（指定维护人）</option></template
          >
        </select></label
      ><DelegationPicker
        v-if="form.kind === 'SCOPED' && c.platform.value"
        :model-value="form.ownerUserId ?? ''"
        user-value
        label="角色维护管理员"
        :disabled="dialog === 'edit'"
        @update:model-value="
          form.ownerUserId = $event || null;
          changed();
        "
      />
      <p v-if="form.kind === 'SCOPED' && !c.platform.value">
        新专用角色由你维护。
      </p>
      <PeopleScopeEditor
        v-if="form.kind === 'SCOPED'"
        v-model="form.personnelScope"
        @update:model-value="changed"
      />
      <p v-else>
        通用/平台角色的人员适用范围为全公司，具体数据范围在权限配置中设置。
      </p>
      <button
        class="primary"
        :disabled="busy || (form.kind === 'SCOPED' && !form.ownerUserId)"
      >
        保存角色
      </button>
    </form>
    <form
      v-else-if="dialog === 'member'"
      class="form-grid"
      :inert="busy"
      @submit.prevent="addMember"
      @input="c.dirty.value = true"
    >
      <p>
        将人员加入
        {{ role?.name }}；业务管理员只能选择本应用由自己维护的普通人员。
      </p>
      <PersonPicker v-model="memberUser" label="选择成员账号" /><label
        >成员生效时间<input v-model="memberFrom" type="datetime-local" /></label
      ><label
        >成员结束时间（不含）<input
          v-model="memberTo"
          type="datetime-local" /></label
      ><button class="primary" :disabled="busy || !memberUser">
        确认添加成员
      </button>
    </form>
    <template v-else
      ><form
        v-if="!preview"
        class="form-grid"
        :inert="busy"
        @submit.prevent="prepareOwner"
      >
        <DelegationPicker v-model="newOwner" user-value label="接任管理员" />
        <p>
          只交接角色与维护责任，不裁剪员工旧权限。员工持多个专用角色时可逐步交接，中间责任待接管。
        </p>
        <button class="primary" :disabled="busy || !newOwner">预览交接</button>
      </form>
      <PreviewSummary
        v-else
        :preview="preview"
        :busy="busy"
        @back="preview = undefined"
        @confirm="commitOwner"
        ><p>
          将 {{ role?.name }} 交给
          {{
            c.accountName(newOwner)
          }}。员工现有成员关系、有效期与原授予记录保持不变。
        </p>
        <p v-if="preview.afterState.awaitingUsers">
          其中
          {{ preview.afterState.awaitingUsers }}
          名人员仍有其他维护人的专用角色，暂记待接管。
        </p></PreviewSummary
      ></template
    ></EditorDialog
  ><GrantEditor
    v-if="grant"
    :user-id="grant.userId"
    :username="grant.username"
    @close="grant = undefined"
    @saved="refreshRole"
  />
</template>
