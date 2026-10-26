<!-- 功能：人员组织/岗位与全部新角色的原子变动表单；跨应用明确保留/撤任，先预览再确认。 -->
<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { adminApi, type Schema } from "@municipal/api";
import { useAdmin, useTask, date } from "../context";
import EditorDialog from "./EditorDialog.vue";
import PreviewSummary from "./PreviewSummary.vue";
import DelegationPicker from "./DelegationPicker.vue";
import Pager from "./Pager.vue";
const props = defineProps<{
  employee: Schema<"Employee">;
  account: Schema<"ManagedAccount"> | null;
}>();
const emit = defineEmits<{ close: []; saved: [] }>();
const c = useAdmin();
const { busy, error, run } = useTask();
const org = ref(props.employee.organizationId),
  position = ref(props.employee.positionName ?? ""),
  action = ref<"KEEP" | "RETIRE">("KEEP"),
  app = ref(c.appId.value),
  draft = ref<Schema<"AccessTransferApplication">[]>([]),
  old = ref<Schema<"AccessUserRoles">[]>([]),
  roles = ref<Schema<"AccessRolePage">>(),
  page = ref(1),
  q = ref(""),
  preview = ref<Schema<"AccessPreview">>();
const current = computed(() =>
  draft.value.find((a) => a.applicationId === app.value),
);
async function loadRoles(p = 1) {
  page.value = p;
  await run(async () => {
    roles.value = await adminApi.roles(app.value, {
      page: p,
      size: 20,
      q: q.value,
    });
  });
}
onMounted(async () => {
  await run(async () => {
    if (props.account) {
      old.value = await Promise.all(
        c.apps.value.map((a) => adminApi.userRoles(a.id, props.account!.id)),
      );
      draft.value = old.value.map((a) => ({
        applicationId: a.applicationId,
        memberships: a.memberships.map((m) => ({
          roleId: m.roleId,
          validFrom: m.validFrom,
          validTo: m.validTo,
        })),
        responsibilityDelegationId: a.responsibility?.delegationId ?? null,
      }));
    }
  });
  if (props.account) await loadRoles();
});
function changed() {
  preview.value = undefined;
  c.dirty.value = true;
}
function setRole(id: string, checked: boolean) {
  if (!current.value) return;
  if (checked)
    current.value.memberships.push({
      roleId: id,
      validFrom: null,
      validTo: null,
    });
  else
    current.value.memberships = current.value.memberships.filter(
      (m) => m.roleId !== id,
    );
  changed();
}
async function prepare() {
  await run(async () => {
    preview.value = await adminApi.previewAssignment(props.employee.id, {
      expectedVersion: props.employee.version,
      expectedAccountVersion: props.account?.version ?? null,
      organizationId: org.value,
      positionName: position.value || null,
      managementAction: action.value,
      applications: draft.value,
    });
  });
}
async function commit() {
  await run(async () => {
    await adminApi.commitAssignment(props.employee.id, preview.value!.token);
    c.dirty.value = false;
    emit("saved");
  });
}
function close() {
  if (!busy.value && c.discard()) emit("close");
}
const roleName = (a: string, id: string) =>
  roles.value?.items.find((r) => r.id === id)?.name ??
  old.value
    .find((n) => n.applicationId === a)
    ?.memberships.find((m) => m.roleId === id)?.roleName ??
  "所选角色";
</script>
<template>
  <EditorDialog
    :title="`${employee.displayName} · 调岗换角`"
    :busy="busy"
    @close="close"
    ><p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <p class="info-box">
      本操作只替换此人旧角色并更新组织、岗位。撤任时其原先授予其他员工的角色仍然有效。
    </p>
    <form
      v-if="!preview"
      class="form-grid"
      :inert="busy"
      @submit.prevent="prepare"
      @input="changed"
    >
      <label
        >新主要组织<select v-model="org" required>
          <option
            v-for="o in c.orgs.value.filter(
              (n) => n.enabled && n.peopleSelectable,
            )"
            :key="o.id"
            :value="o.id"
          >
            {{ o.name }}
          </option>
        </select></label
      ><label>新岗位<input v-model="position" maxlength="100" /></label
      ><label
        >本人管理任职<select v-model="action">
          <option value="KEEP">保留管理任职</option>
          <option value="RETIRE">撤任，原维护人员待接管</option>
        </select></label
      ><template v-if="account"
        ><label
          >编辑应用<select v-model="app" @change="loadRoles()">
            <option v-for="a in c.apps.value" :key="a.id" :value="a.id">
              {{ a.name }}
            </option>
          </select></label
        >
        <section v-if="current">
          <h3>本应用的新角色集合</h3>
          <p v-for="m in current.memberships" :key="m.roleId">
            {{ roleName(app, m.roleId) }}
            <button type="button" @click="setRole(m.roleId, false)">
              移除新集合中的角色
            </button>
          </p>
          <p v-if="!current.memberships.length" class="muted">
            新集合为空，确认后将没有本应用角色。
          </p>
          <div class="search-line">
            <input
              v-model="q"
              aria-label="搜索调岗新角色"
              maxlength="100"
            /><button type="button" :disabled="busy" @click="loadRoles(1)">
              搜索角色
            </button>
          </div>
          <div class="choice-grid" :inert="busy">
            <label v-for="r in roles?.items" :key="r.id"
              ><input
                type="checkbox"
                :disabled="
                  !r.enabled &&
                  !current.memberships.some((m) => m.roleId === r.id)
                "
                :checked="current.memberships.some((m) => m.roleId === r.id)"
                @change="
                  setRole(r.id, ($event.target as HTMLInputElement).checked)
                "
              />{{ r.name }}</label
            >
          </div>
          <Pager
            v-if="roles"
            :page="page"
            :size="20"
            :total="roles.total"
            :busy="busy"
            @change="loadRoles"
          /><DelegationPicker
            v-if="app !== '1'"
            :key="app"
            :model-value="current.responsibilityDelegationId ?? ''"
            :application-id="app"
            label="新维护责任管理员"
            @update:model-value="
              current.responsibilityDelegationId = $event || null;
              changed();
            "
          /></section
      ></template>
      <p v-else>此人尚未建号，只更新组织和岗位，不授予任何角色。</p>
      <button class="primary" :disabled="busy">预览人员变动</button>
    </form>
    <PreviewSummary
      v-else
      :preview="preview"
      :busy="busy"
      @back="preview = undefined"
      @confirm="commit"
      ><p>
        {{ employee.displayName }}：{{
          c.orgs.value.find((o) => o.id === org)?.name
        }}
        / {{ position || "未填写岗位" }}；{{
          action === "KEEP" ? "保留" : "撤回"
        }}本人管理任职。
      </p>
      <div v-for="a in draft" :key="a.applicationId">
        <strong>{{
          c.apps.value.find((n) => n.id === a.applicationId)?.name
        }}</strong>
        <p>
          {{
            a.memberships.length
              ? a.memberships
                  .map((m) => roleName(a.applicationId, m.roleId))
                  .join("、")
              : "不授予角色"
          }}
        </p>
      </div>
      <p>
        所有新角色均由此处明确的新集合决定。记录变化或越界时，整次操作拒绝。
      </p></PreviewSummary
    ></EditorDialog
  >
</template>
