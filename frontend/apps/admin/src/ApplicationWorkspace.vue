<!-- 功能：应用信息、注册权限只读目录、平台委派配置；业务管理员只读自身范围与可授予上限。 -->
<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import {
  clone,
  useAdmin,
  useTask,
  useDirty,
  scopes,
  date,
  inputTime,
  utc,
} from "./context";
import Pager from "./components/Pager.vue";
import EditorDialog from "./components/EditorDialog.vue";
import PeopleScopeEditor from "./components/PeopleScopeEditor.vue";
import ScopeEditor from "./components/ScopeEditor.vue";
import PersonPicker from "./components/PersonPicker.vue";
const c = useAdmin();
useDirty(c.dirty);
const { busy, error, notice, run } = useTask();
const tab = ref("information"),
  permissions = ref<Schema<"AccessPermission">[]>([]),
  delegations = ref<Schema<"AccessDelegationPage">>(),
  page = ref(1),
  q = ref(""),
  dialog = ref(false),
  editing = ref<Schema<"AccessDelegation">>(),
  rolePage = ref(1),
  roleQ = ref(""),
  roles = ref<Schema<"AccessRolePage">>(),
  roleNames = reactive<Record<string, string>>({}),
  from = ref(""),
  to = ref("");
const form = reactive<Schema<"AccessDelegationSet">>({
  administratorUserId: "",
  enabled: true,
  validFrom: null,
  validTo: null,
  peopleScope: { allCompany: true, organizations: [] },
  commonRoleIds: [],
  permissionCeilings: [],
  expectedVersion: null,
});
async function load() {
  await run(async () => {
    permissions.value = (await adminApi.permissions(c.appId.value)).items;
    if (c.platform.value && c.can("admin:delegation:view")) {
      delegations.value = await adminApi.delegations(c.appId.value, {
        page: page.value,
        size: 20,
        q: q.value,
      });
      await Promise.all(
        delegations.value.items.map((d) =>
          c.resolveAccountName(d.administratorUserId),
        ),
      );
    }
  });
}
async function loadRoles(p = 1) {
  rolePage.value = p;
  await run(async () => {
    roles.value = await adminApi.roles(c.appId.value, {
      page: p,
      size: 20,
      q: roleQ.value,
    });
    for (const r of roles.value.items) roleNames[r.id] = r.name;
  });
}
async function edit(d?: Schema<"AccessDelegation">) {
  if (!c.discard()) return;
  editing.value = d;
  Object.assign(
    form,
    d
      ? {
          administratorUserId: d.administratorUserId,
          enabled: d.enabled,
          validFrom: d.validFrom,
          validTo: d.validTo,
          peopleScope: clone(d.peopleScope),
          commonRoleIds: [...d.commonRoleIds],
          permissionCeilings: clone(d.permissionCeilings),
          expectedVersion: d.version,
        }
      : {
          administratorUserId: "",
          enabled: true,
          validFrom: null,
          validTo: null,
          peopleScope: { allCompany: true, organizations: [] },
          commonRoleIds: [],
          permissionCeilings: [],
          expectedVersion: null,
        },
  );
  from.value = inputTime(d?.validFrom);
  to.value = inputTime(d?.validTo);
  dialog.value = true;
  await loadRoles();
  if (d)
    await Promise.all(
      d.commonRoleIds.slice(0, 20).map(async (id) => {
        if (!roleNames[id])
          try {
            roleNames[id] = (await adminApi.role(c.appId.value, id)).name;
          } catch {}
      }),
    );
}
function changed() {
  c.dirty.value = true;
}
function toggleRole(id: string, checked: boolean) {
  form.commonRoleIds = checked
    ? [...form.commonRoleIds, id]
    : form.commonRoleIds.filter((n) => n !== id);
  changed();
}
function togglePermission(p: Schema<"AccessPermission">, checked: boolean) {
  form.permissionCeilings = checked
    ? [
        ...form.permissionCeilings,
        { permissionId: p.id, scope: { kind: "SELF", organizationIds: [] } },
      ]
    : form.permissionCeilings.filter((n) => n.permissionId !== p.id);
  changed();
}
async function save() {
  let ok = false;
  await run(async () => {
    await adminApi.saveDelegation(c.appId.value, editing.value?.id ?? null, {
      ...form,
      validFrom: utc(from.value),
      validTo: utc(to.value),
    });
    dialog.value = false;
    c.dirty.value = false;
    ok = true;
  });
  if (ok) {
    await load();
    await c.refresh();
    notice.value = "管理员委派已保存，员工已有角色保持不变。";
  }
}
async function status(d: Schema<"AccessDelegation">) {
  if (
    !window.confirm(
      `确定${d.enabled ? "停用" : "启用"} ${c.accountName(d.administratorUserId)} 的应用管理委派？停用只使维护责任待接管，不撤回员工角色。`,
    )
  )
    return;
  let ok = false;
  await run(async () => {
    await adminApi.delegationStatus(c.appId.value, d.id, {
      enabled: !d.enabled,
      expectedVersion: d.version,
    });
    ok = true;
  });
  if (ok) {
    await load();
    await c.refresh();
    notice.value = "委派状态已更新。";
  }
}
function close() {
  if (!busy.value && c.discard()) dialog.value = false;
}
function scopeText(scope: Schema<"AccessScope">) {
  return (
    scopes[scope.kind] +
    (scope.organizationIds.length
      ? "：" +
        scope.organizationIds
          .map(
            (id) =>
              c.orgs.value.find((o) => o.id === id)?.name ?? "原有组织范围",
          )
          .join("、")
      : "")
  );
}
onMounted(load);
</script>
<template>
  <section class="workspace-panel">
    <div class="tab-bar" role="tablist">
      <button
        v-for="t in [
          { id: 'information', name: '应用信息' },
          { id: 'resources', name: '资源权限目录' },
          { id: 'delegations', name: '管理员与范围' },
        ]"
        :key="t.id"
        role="tab"
        :aria-selected="tab === t.id"
        @click="tab = t.id"
      >
        {{ t.name }}
      </button>
    </div>
    <div class="pane">
      <p v-if="error" role="alert" class="error-box">{{ error }}</p>
      <p v-if="notice" role="status" class="success-box">{{ notice }}</p>
      <div v-if="tab === 'information'">
        <h2>{{ c.current.value?.name }}</h2>
        <dl class="facts-list">
          <dt>应用编码</dt>
          <dd>{{ c.current.value?.code }}</dd>
          <dt>你的管理资格</dt>
          <dd>
            {{
              c.platform.value ? "平台管理（按实际动作开放）" : "应用管理员委派"
            }}
          </dd>
          <dt>人员管理范围</dt>
          <dd>
            {{
              c.platform.value
                ? "由平台配置各应用管理员范围"
                : c.current.value?.delegation?.peopleScope.allCompany
                  ? "全公司"
                  : c.current.value?.delegation?.peopleScope.organizations
                      .map(
                        (n) =>
                          (c.orgs.value.find((o) => o.id === n.organizationId)
                            ?.name ?? "指定组织") +
                          (n.includeDescendants ? "及下级" : ""),
                      )
                      .join("、")
            }}
          </dd>
        </dl>
        <p class="info-box">
          管理任职与员工业务角色分别生效。人员岗位不会自动授予角色；维护责任变化不裁剪历史授权。
        </p>
        <button :disabled="busy" @click="load">刷新应用信息</button>
      </div>
      <div v-else-if="tab === 'resources'">
        <div class="section-heading">
          <h2>已登记权限</h2>
          <button :disabled="busy" @click="load">刷新权限目录</button>
        </div>
        <p class="muted">
          当前目录由已登记接口提供，未启用或未实现的权限不能新授予。本轮目录只读。
        </p>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>权限名称</th>
                <th>权限编码</th>
                <th>状态</th>
                <th>实现状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in permissions" :key="p.id">
                <td>{{ p.name }}</td>
                <td>{{ p.code }}</td>
                <td>{{ p.enabled ? "启用" : "停用" }}</td>
                <td>
                  {{
                    p.implementationStatus === "IMPLEMENTED"
                      ? "已实现"
                      : "待实现"
                  }}
                </td>
              </tr>
              <tr v-if="!permissions.length">
                <td colspan="4">当前应用暂无已登记权限。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div v-else>
        <template v-if="c.platform.value"
          ><div class="section-heading">
            <h2>管理员与委派范围</h2>
            <button
              class="primary"
              :disabled="
                busy ||
                c.appId.value === '1' ||
                !c.can('admin:delegation:manage')
              "
              @click="edit()"
            >
              任命应用管理员
            </button>
          </div>
          <p v-if="c.appId.value === '1'" class="info-box">
            平台管理资格通过平台角色配置，业务委派不适用于权限平台本身。
          </p>
          <p class="muted">
            先任命管理员，再到“组织与用户 → 账号 → 角色与权限 →
            维护责任”指定其负责人员。业务管理员还需持有实际业务角色，才有权向他人新授予对应权限。
          </p>
          <form
            class="search-line"
            @submit.prevent="
              page = 1;
              load();
            "
          >
            <input
              v-model="q"
              maxlength="100"
              aria-label="搜索管理员委派"
              placeholder="输入管理员账号"
            /><button :disabled="busy">搜索委派</button>
          </form>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>管理员账号</th>
                  <th>人员管理范围</th>
                  <th>有效期</th>
                  <th>状态</th>
                  <th>维护</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="d in delegations?.items" :key="d.id">
                  <td>{{ c.accountName(d.administratorUserId) }}</td>
                  <td>
                    {{
                      d.peopleScope.allCompany
                        ? "全公司"
                        : d.peopleScope.organizations
                            .map(
                              (n) =>
                                (c.orgs.value.find(
                                  (o) => o.id === n.organizationId,
                                )?.name ?? "指定组织") +
                                (n.includeDescendants ? "及下级" : ""),
                            )
                            .join("、")
                    }}
                  </td>
                  <td>{{ date(d.validFrom) }} 至 {{ date(d.validTo) }}</td>
                  <td>{{ d.enabled ? "启用" : "停用" }}</td>
                  <td>
                    <div class="row-actions">
                      <button
                        :disabled="busy || !c.can('admin:delegation:manage')"
                        @click="edit(d)"
                      >
                        编辑委派</button
                      ><button
                        :disabled="busy || !c.can('admin:delegation:manage')"
                        @click="status(d)"
                      >
                        {{ d.enabled ? "停用委派" : "启用委派" }}
                      </button>
                    </div>
                  </td>
                </tr>
                <tr v-if="delegations && !delegations.items.length">
                  <td colspan="5">尚未任命此应用的业务管理员。</td>
                </tr>
              </tbody>
            </table>
          </div>
          <Pager
            v-if="delegations"
            :page="page"
            :size="20"
            :total="delegations.total"
            :busy="busy"
            @change="
              (p) => {
                page = p;
                load();
              }
            " /></template
        ><template v-else
          ><h2>你的管理委派</h2>
          <p>
            管理员：{{ c.session.user?.username }}；有效至
            {{ date(c.current.value?.delegation?.validTo) }}。
          </p>
          <h3>平台配置的权限上限</h3>
          <ul class="read-list">
            <li
              v-for="p in c.current.value?.delegation?.permissionCeilings"
              :key="p.permissionId"
            >
              {{ permissions.find((n) => n.id === p.permissionId)?.name }} ·
              {{ scopeText(p.scope) }}
            </li>
          </ul>
          <h3>当前可新授予能力</h3>
          <ul class="read-list">
            <li
              v-for="p in c.current.value?.delegablePermissions"
              :key="p.permissionId"
            >
              {{ permissions.find((n) => n.id === p.permissionId)?.name }} ·
              {{
                p.capabilities.all
                  ? "全公司数据"
                  : [
                      p.capabilities.self ? "本人数据" : "",
                      p.capabilities.organizationIds.length ? "指定组织" : "",
                      p.capabilities.subtreeRootIds.length ? "组织及下级" : "",
                    ]
                      .filter(Boolean)
                      .join("、") || "当前没有可新授予范围"
              }}
            </li>
          </ul>
          <p class="muted">
            平台上限与本人现有业务权限取交集；旧的合法授予不会被回溯裁剪。委派配置需由平台管理员修改。
          </p></template
        >
      </div>
    </div>
  </section>
  <EditorDialog
    v-if="dialog"
    :title="editing ? '编辑管理员委派' : '任命应用管理员'"
    :busy="busy"
    @close="close"
    ><p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <form
      class="form-grid"
      :inert="busy"
      @submit.prevent="save"
      @input="changed"
    >
      <PersonPicker
        v-model="form.administratorUserId"
        label="管理员账号"
        :disabled="!!editing"
      /><label
        ><input v-model="form.enabled" type="checkbox" />启用管理委派</label
      >
      <div class="time-pair">
        <label>任职开始时间<input v-model="from" type="datetime-local" /></label
        ><label
          >任职结束时间（不含）<input v-model="to" type="datetime-local"
        /></label>
      </div>
      <PeopleScopeEditor
        v-model="form.peopleScope"
        unrestricted
        @update:model-value="changed"
      />
      <section>
        <h3>可授通用角色白名单</h3>
        <p class="muted">
          仅通用角色可进入白名单。专用角色另由管理员本人维护。
        </p>
        <div class="selected-chips">
          <span v-for="id in form.commonRoleIds" :key="id"
            >{{ roleNames[id] ?? "已选通用角色"
            }}<button
              type="button"
              aria-label="从白名单移除"
              @click="toggleRole(id, false)"
            >
              ×
            </button></span
          >
        </div>
        <div class="search-line">
          <input
            v-model="roleQ"
            maxlength="100"
            aria-label="搜索通用角色"
          /><button type="button" :disabled="busy" @click="loadRoles(1)">
            查找角色
          </button>
        </div>
        <div class="choice-grid" :inert="busy">
          <label
            v-for="r in roles?.items.filter((n) => n.kind === 'COMMON')"
            :key="r.id"
            ><input
              type="checkbox"
              :checked="form.commonRoleIds.includes(r.id)"
              @change="
                toggleRole(r.id, ($event.target as HTMLInputElement).checked)
              "
            />{{ r.name }}</label
          >
        </div>
        <Pager
          v-if="roles"
          :page="rolePage"
          :size="20"
          :total="roles.total"
          :busy="busy"
          @change="loadRoles"
        />
      </section>
      <section>
        <h3>平台配置的业务权限上限</h3>
        <div v-for="p in permissions" :key="p.id" class="permission-item">
          <label
            ><input
              type="checkbox"
              :checked="
                form.permissionCeilings.some((n) => n.permissionId === p.id)
              "
              :disabled="
                (!p.enabled || p.implementationStatus !== 'IMPLEMENTED') &&
                !form.permissionCeilings.some((n) => n.permissionId === p.id)
              "
              @change="
                togglePermission(p, ($event.target as HTMLInputElement).checked)
              "
            />{{ p.name }} <small>{{ p.code }}</small></label
          ><ScopeEditor
            v-for="ceiling in form.permissionCeilings.filter(
              (n) => n.permissionId === p.id,
            )"
            :key="ceiling.permissionId"
            v-model="ceiling.scope"
            @update:model-value="changed"
          />
        </div>
      </section>
      <p class="info-box">
        此处只配置管理资格和上限，不自动给管理员本人授予业务角色。停用、收缩和续期不会撤回员工既有角色。
      </p>
      <button class="primary" :disabled="busy || !form.administratorUserId">
        保存管理员委派
      </button>
    </form></EditorDialog
  >
</template>
