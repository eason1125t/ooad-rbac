<!-- 功能：按应用编辑人员完整角色集合/维护责任并查看有效来源；所有覆盖写先走后端预览。 -->
<script setup lang="ts">
import { ref, onMounted, watch, computed } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import {
  useAdmin,
  useTask,
  date,
  inputTime,
  utc,
  kinds,
  scopes,
} from "../context";
import EditorDialog from "./EditorDialog.vue";
import Pager from "./Pager.vue";
import PreviewSummary from "./PreviewSummary.vue";
import DelegationPicker from "./DelegationPicker.vue";
const props = defineProps<{ userId: string; username: string }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const c = useAdmin();
const { busy, error, notice, run } = useTask();
const state = ref<Schema<"AccessUserRoles">>(),
  effective = ref<Schema<"AccessEffective">>(),
  roles = ref<Schema<"AccessRolePage">>(),
  responsible = ref(""),
  page = ref(1),
  q = ref(""),
  tab = ref("roles"),
  draft = ref<Schema<"AccessMembershipInput">[]>([]),
  preview = ref<Schema<"AccessPreview">>(),
  editing = ref(false),
  responsibilityDirty = ref(false);
const canWrite = computed(
  () =>
    c.can("admin:grant:update") &&
    (c.platform.value || props.userId !== c.session.user?.id),
);
async function load() {
  await run(async () => {
    state.value = await adminApi.userRoles(c.appId.value, props.userId);
    draft.value = state.value.memberships.map((m) => ({
      roleId: m.roleId,
      validFrom: m.validFrom,
      validTo: m.validTo,
    }));
    responsible.value = state.value.responsibility?.delegationId ?? "";
    effective.value = await adminApi.effective(c.appId.value, props.userId);
    roles.value = await adminApi.roles(c.appId.value, {
      page: page.value,
      size: 20,
      q: q.value,
    });
    editing.value = false;
    responsibilityDirty.value = false;
    preview.value = undefined;
    c.dirty.value = false;
  });
}
async function rolePage(p: number) {
  page.value = p;
  await run(async () => {
    roles.value = await adminApi.roles(c.appId.value, {
      page: p,
      size: 20,
      q: q.value,
    });
  });
}
function toggle(role: Schema<"AccessRole">, checked: boolean) {
  if (checked)
    draft.value.push({ roleId: role.id, validFrom: null, validTo: null });
  else draft.value = draft.value.filter((m) => m.roleId !== role.id);
  editing.value = true;
  preview.value = undefined;
  c.dirty.value = true;
}
function changed() {
  editing.value = true;
  preview.value = undefined;
  c.dirty.value = true;
}
async function prepare() {
  await run(async () => {
    preview.value = await adminApi.previewUserRoles(
      c.appId.value,
      props.userId,
      { expectedVersion: state.value!.version, memberships: draft.value },
    );
  });
}
async function commit() {
  await run(async () => {
    await adminApi.commitUserRoles(
      c.appId.value,
      props.userId,
      preview.value!.token,
    );
    preview.value = undefined;
    editing.value = false;
    c.dirty.value = false;
    emit("saved");
  });
  if (!error.value) {
    await load();
    if (!error.value) notice.value = "角色授权已保存。";
  }
}
async function saveResponsibility() {
  await run(async () => {
    const r = await adminApi.setResponsibility(c.appId.value, props.userId, {
      delegationId: responsible.value || null,
      expectedVersion: state.value?.responsibility?.version ?? null,
    });
    if (state.value) state.value.responsibility = r;
    responsibilityDirty.value = false;
    c.dirty.value = editing.value;
    emit("saved");
    notice.value = "维护责任已更新，已有角色未改变。";
  });
}
function close() {
  if (
    !busy.value &&
    (!(editing.value || responsibilityDirty.value) || c.discard())
  ) {
    c.dirty.value = false;
    emit("close");
  }
}
function roleName(id: string) {
  return (
    roles.value?.items.find((r) => r.id === id)?.name ??
    state.value?.memberships.find((m) => m.roleId === id)?.roleName ??
    "已授角色"
  );
}
watch(tab, async (value) => {
  if (value === "effective")
    await run(async () => {
      effective.value = await adminApi.effective(c.appId.value, props.userId);
    });
});
onMounted(load);
</script>
<template>
  <EditorDialog
    :title="`${username} · ${c.current.value?.name ?? ''}授权`"
    :busy="busy"
    @close="close"
    ><p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <p v-if="notice" role="status" class="success-box">{{ notice }}</p>
    <div class="tab-bar" role="tablist">
      <button
        v-for="t in [
          { id: 'roles', name: '角色与有效期' },
          { id: 'effective', name: '有效权限与来源' },
          ...(c.platform.value
            ? [{ id: 'responsibility', name: '维护责任' }]
            : []),
        ]"
        :key="t.id"
        type="button"
        role="tab"
        :aria-selected="tab === t.id"
        @click="tab = t.id"
      >
        {{ t.name }}
      </button>
    </div>
    <p v-if="busy && !state" role="status">正在加载授权…</p>
    <template v-if="state"
      ><div v-if="tab === 'roles'">
        <p class="muted">
          这里按当前应用覆盖角色集合。取消勾选会撤回该角色；保留的旧角色不会因管理员失权而被裁剪。
        </p>
        <div v-if="!preview">
          <h3>当前选择（{{ draft.length }}）</h3>
          <p v-if="!draft.length" class="muted">
            尚未选择角色；保存空集合将撤回本应用的全部角色。
          </p>
          <div
            v-for="m in draft"
            :key="m.roleId"
            class="grant-row"
            :inert="busy"
          >
            <strong>{{ roleName(m.roleId) }}</strong
            ><label
              >生效时间<input
                type="datetime-local"
                :value="inputTime(m.validFrom)"
                :disabled="!canWrite"
                @change="
                  m.validFrom = utc(($event.target as HTMLInputElement).value);
                  changed();
                " /></label
            ><label
              >结束时间（不含）<input
                type="datetime-local"
                :value="inputTime(m.validTo)"
                :disabled="!canWrite"
                @change="
                  m.validTo = utc(($event.target as HTMLInputElement).value);
                  changed();
                " /></label
            ><button
              type="button"
              :disabled="busy || !canWrite"
              @click="
                draft = draft.filter((n) => n.roleId !== m.roleId);
                changed();
              "
            >
              移除角色
            </button>
          </div>
          <form class="search-line" @submit.prevent="rolePage(1)">
            <input
              v-model="q"
              aria-label="搜索可分配角色"
              placeholder="搜索角色名称或编码"
              maxlength="100"
            /><button :disabled="busy">搜索角色</button>
          </form>
          <div class="choice-grid" :inert="busy">
            <label v-for="r in roles?.items" :key="r.id"
              ><input
                type="checkbox"
                :disabled="
                  busy ||
                  !canWrite ||
                  (!r.canAssign && !draft.some((m) => m.roleId === r.id))
                "
                :checked="draft.some((m) => m.roleId === r.id)"
                @change="toggle(r, ($event.target as HTMLInputElement).checked)"
              />{{ r.name }}
              <small
                >{{ kinds[r.kind] }}{{ r.enabled ? "" : " · 停用" }}</small
              ></label
            >
          </div>
          <Pager
            v-if="roles"
            :page="page"
            :size="20"
            :total="roles.total"
            :busy="busy"
            @change="rolePage"
          />
          <div class="form-actions">
            <button
              type="button"
              :disabled="busy"
              @click="c.discard() && load()"
            >
              重新加载</button
            ><button
              type="button"
              class="primary"
              :disabled="busy || !canWrite"
              @click="prepare"
            >
              预览授权变更
            </button>
          </div>
        </div>
        <PreviewSummary
          v-else
          :preview="preview"
          :busy="busy"
          @back="preview = undefined"
          @confirm="commit"
          ><p>对象：{{ username }}；应用：{{ c.current.value?.name }}</p>
          <ul>
            <li v-for="m in draft" :key="m.roleId">
              保留 / 授予 {{ roleName(m.roleId) }}：{{ date(m.validFrom) }} 至
              {{ date(m.validTo) }}（结束不含）
            </li>
            <li
              v-for="m in state.memberships.filter(
                (old) => !draft.some((n) => n.roleId === old.roleId),
              )"
              :key="m.roleId"
            >
              撤回 {{ m.roleName }}
            </li>
          </ul>
          <strong v-if="!draft.length"
            >本应用新角色集合为空。</strong
          ></PreviewSummary
        >
      </div>
      <div v-else-if="tab === 'effective'">
        <p v-if="!effective?.permissions.length" class="muted">
          当前没有普通业务数据权限。
        </p>
        <ul class="read-list">
          <li v-for="p in effective?.permissions" :key="p.permissionId">
            <strong>{{ p.code }}</strong> ·
            {{
              p.capabilities.all
                ? "全公司数据"
                : [
                    p.capabilities.self ? "本人数据" : "",
                    ...p.capabilities.organizationIds.map(
                      (id) =>
                        c.orgs.value.find((o) => o.id === id)?.name ??
                        "指定组织",
                    ),
                    ...p.capabilities.subtreeRootIds.map(
                      (id) =>
                        (c.orgs.value.find((o) => o.id === id)?.name ??
                          "指定组织") + "及下级",
                    ),
                  ]
                    .filter(Boolean)
                    .join("、")
            }}
          </li>
        </ul>
        <h3>有效角色来源</h3>
        <div
          v-for="(s, index) in effective?.sources"
          :key="index"
          class="source-row"
        >
          <strong>{{ s.code }}</strong> · {{ scopes[s.scope.kind] }}
          <p>
            {{ roleName(s.roleId) }} · {{ date(s.validFrom) }} 至
            {{ date(s.validTo)
            }}{{ s.legacy ? " · 历史引导演示来源（不计普通数据范围）" : "" }}
          </p>
        </div>
      </div>
      <div v-else>
        <p>
          当前状态：{{
            state.responsibility?.state === "CURRENT"
              ? "已指定维护人"
              : "待接管"
          }}。维护责任不会改变员工已持有的角色。
        </p>
        <DelegationPicker
          v-model="responsible"
          label="责任管理员"
          @update:model-value="
            responsibilityDirty = true;
            c.dirty.value = true;
          "
        /><button
          type="button"
          class="primary"
          :disabled="busy || !c.can('admin:responsibility:manage')"
          @click="saveResponsibility"
        >
          保存维护责任
        </button>
      </div></template
    ></EditorDialog
  >
</template>
