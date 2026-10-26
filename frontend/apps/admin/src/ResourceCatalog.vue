<!-- 功能：应用、8层资源树与权限点真实管理；嵌入原工作区，启停先预览影响，角色授权另行显式配置。 -->
<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { catalogApi, type CatalogKind, type Schema } from "@municipal/api";
import { useAdmin, useTask, date } from "./context";
import EditorDialog from "./components/EditorDialog.vue";
const c = useAdmin(),
  { busy, error, notice, run } = useTask();
const applications = ref<Schema<"CatalogApplication">[]>([]),
  snapshot = ref<Schema<"CatalogSnapshot">>();
const appId = ref(c.appId.value),
  selected = ref(""),
  q = ref(""),
  mode = ref("");
const editingResource = ref<Schema<"CatalogResource">>(),
  editingPermission = ref<Schema<"CatalogPermission">>();
const preview = ref<Schema<"CatalogStatusPreview">>();
const writable = computed(
  () => c.platform.value && c.can("admin:catalog:manage"),
);
const applicationForm = reactive({ code: "", name: "", description: "" });
const resourceForm = reactive<Schema<"CatalogResourceCreate">>({
  parentId: null,
  code: "",
  name: "",
  kind: "FUNCTION",
  description: "",
});
const permissionForm = reactive<Schema<"CatalogPermissionCreate">>({
  resourceId: "",
  action: "view",
  name: "",
  description: "",
});
const resources = computed(() => snapshot.value?.resources ?? []);
const current = computed(() =>
  resources.value.find((r) => r.id === selected.value),
);
const directories = computed(() =>
  resources.value.filter((r) => r.kind === "DIRECTORY"),
);
const functions = computed(() =>
  resources.value.filter((r) => r.kind === "FUNCTION"),
);
const matchingPermissions = computed(() =>
  (snapshot.value?.permissions ?? []).filter(
    (p) =>
      (!selected.value ||
        p.resourceId === selected.value ||
        isDescendant(p.resourceId, selected.value)) &&
      (!q.value ||
        `${p.name} ${p.code}`.toLowerCase().includes(q.value.toLowerCase())),
  ),
);
const registered = computed(
  () =>
    snapshot.value?.implementedCodes.includes(
      `${snapshot.value.application.code}:${resources.value.find((r) => r.id === permissionForm.resourceId)?.code}:${permissionForm.action}`,
    ) ?? false,
);
function isDescendant(id: string | null, ancestor: string): boolean {
  let node = resources.value.find((r) => r.id === id);
  for (let n = 0; node?.parentId && n < 8; n++) {
    if (node.parentId === ancestor) return true;
    node = resources.value.find((r) => r.id === node?.parentId);
  }
  return false;
}
function depth(id: string): number {
  let node = resources.value.find((r) => r.id === id),
    n = 0;
  while (node?.parentId && n < 8) {
    n++;
    node = resources.value.find((r) => r.id === node?.parentId);
  }
  return n;
}
async function fetchCatalog() {
  if (writable.value)
    applications.value = (await catalogApi.applications()).items;
  snapshot.value = await catalogApi.snapshot(appId.value);
  if (!resources.value.some((r) => r.id === selected.value))
    selected.value = "";
}
async function load() {
  await run(fetchCatalog);
}
onMounted(load);
watch(
  () => c.appId.value,
  (value) => {
    if (value && value !== appId.value) {
      appId.value = value;
      selected.value = "";
      void load();
    }
  },
);
function close() {
  if (!busy.value && c.discard()) {
    mode.value = "";
    preview.value = undefined;
  }
}
function editApplication(create: boolean) {
  const a = snapshot.value?.application;
  Object.assign(applicationForm, {
    code: create ? "" : a?.code,
    name: create ? "" : a?.name,
    description: create ? "" : a?.description,
  });
  mode.value = create ? "new-app" : "edit-app";
  error.value = "";
}
function editResource(create: boolean) {
  editingResource.value = create ? undefined : current.value;
  const r = editingResource.value;
  Object.assign(resourceForm, {
    parentId: create
      ? current.value?.kind === "DIRECTORY"
        ? selected.value
        : null
      : (r?.parentId ?? null),
    code: r?.code ?? "",
    name: r?.name ?? "",
    kind: r?.kind ?? "FUNCTION",
    description: r?.description ?? "",
  });
  mode.value = create ? "new-resource" : "edit-resource";
  error.value = "";
}
function editPermission(p?: Schema<"CatalogPermission">) {
  editingPermission.value = p;
  Object.assign(permissionForm, {
    resourceId:
      p?.resourceId ??
      (current.value?.kind === "FUNCTION"
        ? selected.value
        : (functions.value[0]?.id ?? "")),
    action: p?.action ?? "view",
    name: p?.name ?? "",
    description: p?.description ?? "",
  });
  mode.value = p ? "edit-permission" : "new-permission";
  error.value = "";
}
async function save() {
  await run(async () => {
    switch (mode.value) {
      case "new-app": {
        const a = await catalogApi.createApplication(applicationForm);
        appId.value = a.id;
        selected.value = "";
        break;
      }
      case "edit-app":
        await catalogApi.updateApplication(appId.value, {
          name: applicationForm.name,
          description: applicationForm.description,
          expectedVersion: snapshot.value!.application.version,
        });
        break;
      case "new-resource": {
        const r = await catalogApi.createResource(appId.value, {
          ...resourceForm,
        });
        selected.value = r.id;
        break;
      }
      case "edit-resource":
        await catalogApi.updateResource(
          appId.value,
          editingResource.value!.id,
          {
            parentId: resourceForm.parentId,
            name: resourceForm.name,
            description: resourceForm.description,
            expectedVersion: editingResource.value!.version,
          },
        );
        break;
      case "new-permission":
        await catalogApi.createPermission(appId.value, { ...permissionForm });
        selected.value = permissionForm.resourceId;
        break;
      case "edit-permission":
        await catalogApi.updatePermission(
          appId.value,
          editingPermission.value!.id,
          {
            name: permissionForm.name,
            description: permissionForm.description,
            expectedVersion: editingPermission.value!.version,
          },
        );
        break;
    }
    c.dirty.value = false;
    mode.value = "";
    await fetchCatalog();
    await c.refresh();
    notice.value = "目录已保存。新权限需要在角色中配置并显式授予人员。";
  });
}
async function changeStatus(
  kind: CatalogKind,
  target:
    | Schema<"CatalogApplication">
    | Schema<"CatalogResource">
    | Schema<"CatalogPermission">,
) {
  await run(async () => {
    preview.value = await catalogApi.preview(appId.value, kind, target.id, {
      enabled: !target.enabled,
      expectedVersion: target.version,
    });
    mode.value = "status";
  });
}
async function confirmStatus() {
  const p = preview.value;
  if (!p) return;
  await run(async () => {
    await catalogApi.commit(p.applicationId, p.kind, p.targetId, p.token);
    mode.value = "";
    preview.value = undefined;
    await fetchCatalog();
    await c.refresh();
    notice.value = "启停已生效，原角色和成员关系保留。";
  });
}
</script>
<template>
  <section class="resource-catalog">
    <div class="section-heading">
      <h2>资源与权限目录</h2>
      <div class="inline-actions">
        <button :disabled="busy" @click="load">刷新目录</button
        ><button
          v-if="writable"
          :disabled="busy"
          @click="editApplication(true)"
        >
          登记应用
        </button>
      </div>
    </div>
    <p v-if="error && !mode" role="alert" class="error-box">{{ error }}</p>
    <p v-if="notice" role="status" class="notice">{{ notice }}</p>
    <div v-if="writable" class="search-line">
      <label
        >目录应用<select
          v-model="appId"
          aria-label="目录应用"
          :disabled="busy"
          @change="
            selected = '';
            load();
          "
        >
          <option v-for="a in applications" :key="a.id" :value="a.id">
            {{ a.name }}{{ a.enabled ? "" : "（停用）" }}
          </option>
        </select></label
      ><button :disabled="busy || !snapshot" @click="editApplication(false)">
        编辑应用</button
      ><button
        :disabled="busy || !snapshot || appId === '1'"
        @click="snapshot && changeStatus('APPLICATION', snapshot.application)"
      >
        {{ snapshot?.application.enabled ? "停用应用" : "启用应用" }}
      </button>
    </div>
    <p class="muted">
      目录登记与业务接口接入分别管理。只有已接入且实际启用的权限点才能新授予；目录父项不会自动授予子项。
    </p>
    <div v-if="snapshot" class="catalog-layout">
      <aside class="catalog-tree">
        <div class="section-heading">
          <h3>资源树</h3>
          <button v-if="writable" :disabled="busy" @click="editResource(true)">
            新增资源
          </button>
        </div>
        <button :aria-pressed="!selected" @click="selected = ''">
          全部权限点</button
        ><button
          v-for="r in resources"
          :key="r.id"
          :aria-pressed="selected === r.id"
          :style="{ paddingLeft: `${12 + depth(r.id) * 15}px` }"
          @click="selected = r.id"
        >
          <span>{{ r.kind === "DIRECTORY" ? "目录" : "功能" }}</span> {{ r.name
          }}{{ r.enabled ? "" : "（停用）" }}
        </button>
        <p v-if="!resources.length" class="muted">
          暂无资源。先新增目录或功能节点。
        </p>
      </aside>
      <section class="catalog-detail">
        <div class="section-heading">
          <div>
            <h3>{{ current?.name ?? "全部权限点" }}</h3>
            <p class="muted">
              {{
                current
                  ? current.description || current.code
                  : snapshot.application.description
              }}
            </p>
          </div>
          <div v-if="writable" class="inline-actions">
            <button
              v-if="current"
              :disabled="busy"
              @click="editResource(false)"
            >
              编辑资源</button
            ><button
              v-if="current"
              :disabled="busy || appId === '1'"
              @click="changeStatus('RESOURCE', current)"
            >
              {{ current.enabled ? "停用资源" : "启用资源" }}</button
            ><button
              class="primary"
              :disabled="busy || !functions.length"
              @click="editPermission()"
            >
              新增权限点
            </button>
          </div>
        </div>
        <input
          v-model="q"
          aria-label="搜索权限点"
          placeholder="搜索权限名称或编码"
          maxlength="100"
        />
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>权限名称 / 编码</th>
                <th>接入状态</th>
                <th>启用状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in matchingPermissions" :key="p.id">
                <td>
                  <strong>{{ p.name }}</strong
                  ><small class="cell-note">{{ p.code }}</small
                  ><small>{{ p.description }}</small>
                </td>
                <td>
                  {{
                    p.implementationStatus === "IMPLEMENTED"
                      ? "已接入"
                      : "待接入"
                  }}
                </td>
                <td>
                  {{
                    !p.enabled
                      ? "已停用"
                      : p.effectiveEnabled
                        ? "启用"
                        : "上级或应用停用"
                  }}
                </td>
                <td>
                  <div v-if="writable" class="inline-actions">
                    <button :disabled="busy" @click="editPermission(p)">
                      编辑权限点</button
                    ><button
                      :disabled="busy || appId === '1'"
                      @click="changeStatus('PERMISSION', p)"
                    >
                      {{ p.enabled ? "停用权限点" : "启用权限点" }}
                    </button>
                  </div>
                  <span v-else>只读</span>
                </td>
              </tr>
              <tr v-if="!matchingPermissions.length">
                <td colspan="4">暂无匹配权限点。选中功能后可登记其动作。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </section>
  <EditorDialog
    v-if="mode"
    :title="
      mode === 'status'
        ? '确认目录启停影响'
        : mode.includes('permission')
          ? '维护权限点'
          : mode.includes('resource')
            ? '维护资源节点'
            : '维护应用'
    "
    :busy="busy"
    @close="close"
  >
    <p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <div v-if="mode === 'status' && preview" class="catalog-impact">
      <p>
        确认{{ preview.enabled ? "启用" : "停用" }}后，可能影响
        {{ preview.affectedRoles }} 个引用角色、{{ preview.affectedUsers }}
        个账号。
      </p>
      <p>
        统计包含历史、停用及未来关系。停用立即阻止相关访问；启用可能恢复仍有效的既有授权，关系记录会保留。
      </p>
      <p class="muted">
        预览有效至 {{ date(preview.expiresAt) }}；数据变化后需重新预览。
      </p>
      <button class="primary" :disabled="busy" @click="confirmStatus">
        确认目录启停
      </button>
    </div>
    <form
      v-else
      class="form-grid"
      :inert="busy"
      @input="c.dirty.value = true"
      @submit.prevent="save"
    >
      <template v-if="mode.includes('app')"
        ><label v-if="mode === 'new-app'"
          >应用编码<input
            v-model="applicationForm.code"
            required
            pattern="[a-z0-9][a-z0-9._-]{2,31}"
            maxlength="32" /></label
        ><label
          >应用名称<input
            v-model="applicationForm.name"
            required
            maxlength="100" /></label
        ><label
          >应用说明<textarea
            v-model="applicationForm.description"
            maxlength="500"
          /></label
      ></template>
      <template v-else-if="mode.includes('resource')"
        ><label v-if="mode === 'new-resource'"
          >资源编码<input
            v-model="resourceForm.code"
            required
            pattern="[a-z0-9][a-z0-9._-]{2,63}"
            maxlength="64" /></label
        ><label
          >资源名称<input
            v-model="resourceForm.name"
            required
            maxlength="100" /></label
        ><label v-if="mode === 'new-resource'"
          >资源类型<select v-model="resourceForm.kind">
            <option value="DIRECTORY">目录</option>
            <option value="FUNCTION">功能</option>
          </select></label
        ><label
          >上级目录<select v-model="resourceForm.parentId">
            <option :value="null">顶层</option>
            <option
              v-for="r in directories.filter(
                (n) =>
                  n.id !== editingResource?.id &&
                  !isDescendant(n.id, editingResource?.id ?? ''),
              )"
              :key="r.id"
              :value="r.id"
            >
              {{ r.name }}
            </option>
          </select></label
        ><label
          >资源说明<textarea
            v-model="resourceForm.description"
            maxlength="500"
          />
        </label>
        <p class="muted">
          编码及资源类型创建后不变。目录最多8层，移动不会隐式授予权限。
        </p></template
      >
      <template v-else
        ><label v-if="mode === 'new-permission'"
          >功能资源<select v-model="permissionForm.resourceId" required>
            <option v-for="r in functions" :key="r.id" :value="r.id">
              {{ r.name }}（{{ r.code }}）
            </option>
          </select></label
        ><label v-if="mode === 'new-permission'"
          >权限动作<select v-model="permissionForm.action">
            <option
              v-for="a in [
                'view',
                'create',
                'update',
                'submit',
                'withdraw',
                'approve',
                'reject',
                'void',
                'cancel',
                'export',
              ]"
              :key="a"
              :value="a"
            >
              {{ a }}
            </option>
          </select></label
        ><label
          >权限名称<input
            v-model="permissionForm.name"
            required
            maxlength="100" /></label
        ><label
          >权限说明<textarea
            v-model="permissionForm.description"
            maxlength="500"
          />
        </label>
        <p v-if="mode === 'new-permission'" class="info-box">
          {{
            registered
              ? "该动作已接入业务接口，保存后可在角色中配置。"
              : "该编码尚未接入接口，仅登记为待接入，不能授予。"
          }}
        </p>
        <p v-else class="muted">
          权限编码、资源和动作不可修改；需要新含义时创建新权限点。
        </p></template
      >
      <button class="primary" :disabled="busy">保存目录配置</button>
    </form>
  </EditorDialog>
</template>
