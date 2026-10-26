<!-- 功能：公文办理真实台账、本人草稿及指定审批；数据来自oaApi，按钮仅提示，后端核验范围和状态。 -->
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import { oaApi, ApiError, type Schema } from "@municipal/api";
import { useSession } from "@municipal/shell/session";
import RecordDialog from "@municipal/shell/RecordDialog.vue";
import ForcePasswordChange from "@municipal/shell/ForcePasswordChange.vue";
const session = useSession();
const context = ref<Schema<"BusinessContext">>(),
  records = ref<Schema<"OaDocumentPage">>(),
  selected = ref<Schema<"OaDocument">>();
const history = ref<Schema<"BusinessEvent">[]>([]),
  approvers = ref<Schema<"BusinessApprover">[]>([]);
const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  dialog = ref(""),
  dirty = ref(false);
const page = ref(1),
  q = ref(""),
  status = ref(""),
  filter = ref("all"),
  approver = ref(""),
  comment = ref("");
const form = reactive<Schema<"OaDocumentCreate">>({ title: "", body: "" });
const can = (action: string) =>
  context.value?.permissions.includes("oa:document:" + action) ?? false;
const own = computed(() => selected.value?.ownerUserId === session.user?.id);
const editable = computed(
  () => selected.value && ["DRAFT", "REJECTED"].includes(selected.value.status),
);
const assigned = computed(
  () =>
    selected.value?.status === "SUBMITTED" &&
    selected.value.approverUserId === session.user?.id &&
    !own.value,
);
const labels: Record<string, string> = {
  DRAFT: "草稿",
  SUBMITTED: "待审批",
  APPROVED: "已通过",
  REJECTED: "已驳回",
  VOIDED: "作废",
};
const actions: Record<string, string> = {
  CREATE: "创建",
  UPDATE: "编辑",
  SUBMIT: "提交",
  WITHDRAW: "撤回",
  APPROVE: "通过",
  REJECT: "驳回",
  VOID: "作废",
};
const when = (value: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "short",
    timeStyle: "short",
    timeZone: "Asia/Shanghai",
  }).format(new Date(value));

async function run(task: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await task();
  } catch (e) {
    error.value =
      e instanceof ApiError
        ? e.message +
          (e.status === 409
            ? "。请关闭表单，重新加载并核对后再操作。"
            : e.status === 503
              ? "。请先回读记录确认结果，不要重复提交。"
              : "")
        : "连接失败，请检查服务后重试。";
  } finally {
    busy.value = false;
  }
}
async function listData() {
  context.value = await oaApi.context();
  if (!can("view")) {
    records.value = undefined;
    selected.value = undefined;
    history.value = [];
    return;
  }
  records.value = await oaApi.page({
    page: page.value,
    size: 20,
    q: q.value,
    status: status.value,
    mine: filter.value === "mine",
    pendingForMe: filter.value === "pending",
  });
}
async function load() {
  await run(listData);
}
async function pick(id: string) {
  await run(async () => {
    selected.value = await oaApi.get(id);
    history.value = (await oaApi.history(id)).items;
  });
}
async function search() {
  page.value = 1;
  selected.value = undefined;
  history.value = [];
  await load();
}
async function refresh() {
  await run(async () => {
    await listData();
    if (selected.value) {
      selected.value = await oaApi.get(selected.value.id);
      history.value = (await oaApi.history(selected.value.id)).items;
    }
  });
}
onMounted(() => {
  if (!session.user?.forcePasswordChange) void load();
});
onBeforeRouteLeave(
  () => !dirty.value || window.confirm("还有未保存的修改，确定离开？"),
);
function close() {
  if (
    !busy.value &&
    (!dirty.value || window.confirm("确定放弃尚未保存的修改？"))
  ) {
    dialog.value = "";
    dirty.value = false;
  }
}
function edit(create: boolean) {
  Object.assign(
    form,
    create
      ? { title: "", body: "" }
      : { title: selected.value!.title, body: selected.value!.body },
  );
  dialog.value = create ? "new" : "edit";
  dirty.value = false;
  error.value = "";
}
async function save() {
  await run(async () => {
    const result =
      dialog.value === "new"
        ? await oaApi.create({ ...form })
        : await oaApi.update(selected.value!.id, {
            ...form,
            expectedVersion: selected.value!.version,
          });
    dialog.value = "";
    dirty.value = false;
    selected.value = result;
    await listData();
    history.value = (await oaApi.history(result.id)).items;
    notice.value = "公文已保存。";
  });
}
async function submitDialog() {
  await run(async () => {
    approvers.value = (await oaApi.approvers(selected.value!.id)).items;
    approver.value = "";
    dialog.value = "submit";
  });
}
function decision(action: "approve" | "reject") {
  dialog.value = action;
  comment.value = "";
  error.value = "";
}
async function perform(
  action: "submit" | "withdraw" | "approve" | "reject" | "void",
) {
  const record = selected.value;
  if (!record) return;
  if (
    ["withdraw", "void"].includes(action) &&
    !window.confirm(`确定${action === "withdraw" ? "撤回" : "作废"}这份公文？`)
  )
    return;
  await run(async () => {
    const body =
      action === "submit"
        ? { expectedVersion: record.version, approverUserId: approver.value }
        : ["approve", "reject"].includes(action)
          ? { expectedVersion: record.version, comment: comment.value }
          : { expectedVersion: record.version };
    const result = await oaApi.action(record.id, action, body);
    selected.value = result;
    dialog.value = "";
    dirty.value = false;
    await listData();
    history.value = (await oaApi.history(result.id)).items;
    notice.value = `公文${labels[result.status]}。`;
  });
}
</script>
<template>
  <ForcePasswordChange v-if="session.user?.forcePasswordChange" />
  <section v-else class="business-workspace" data-business="oa">
    <div class="business-heading">
      <div>
        <h1>公文办理</h1>
        <p>起草、提交与办理公文，按当前角色查看本人或授权范围内的记录。</p>
      </div>
      <div class="business-actions">
        <button :disabled="busy" @click="refresh">刷新业务数据</button
        ><button
          class="primary"
          :disabled="busy || !can('create') || !context?.organizationId"
          @click="edit(true)"
        >
          新建公文
        </button>
      </div>
    </div>
    <p v-if="error && !dialog" role="alert" class="error-box">{{ error }}</p>
    <p v-if="notice" role="status" class="business-notice">{{ notice }}</p>
    <p v-if="context && !context.organizationId" class="info-box">
      当前账号未关联人员档案，暂不能创建公文。请由平台建立组织、人员和对应账号后配置业务角色。
    </p>
    <p v-if="context && !can('view')" class="info-box">
      当前没有查看本业务的有效权限，请联系管理员配置角色。授权完成后点击“刷新业务数据”。
    </p>
    <template v-if="can('view')">
      <form class="business-filters" @submit.prevent="search">
        <label
          >查找公文<input
            v-model="q"
            maxlength="100"
            placeholder="输入公文名称" /></label
        ><label
          >状态<select v-model="status">
            <option value="">全部状态</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">
              {{ label }}
            </option>
          </select></label
        ><label
          >记录范围<select v-model="filter">
            <option value="all">当前可查看</option>
            <option value="mine">我创建的</option>
            <option value="pending">待我审批</option>
          </select></label
        ><button :disabled="busy">查询公文</button>
      </form>
      <div class="business-layout">
        <section class="business-register">
          <h2>公文台账</h2>
          <div class="business-table-scroll">
            <table>
              <thead>
                <tr>
                  <th>公文 / 编号</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="r in records?.items"
                  :key="r.id"
                  :class="{ 'record-selected': r.id === selected?.id }"
                >
                  <td>
                    <strong>{{ r.title }}</strong
                    ><small>{{ r.number }}</small>
                  </td>
                  <td>
                    <span class="business-status" :data-status="r.status">{{
                      labels[r.status]
                    }}</span>
                  </td>
                  <td>
                    <button :disabled="busy" @click="pick(r.id)">
                      查看公文
                    </button>
                  </td>
                </tr>
                <tr v-if="!records?.items.length">
                  <td colspan="4">
                    当前条件下没有公文，可新建草稿或调整筛选。
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="business-pagination">
            <span>共 {{ records?.total ?? 0 }} 条 · 第 {{ page }} 页</span
            ><button
              :disabled="busy || page === 1"
              @click="
                page--;
                load();
              "
            >
              上一页</button
            ><button
              :disabled="busy || page * 20 >= (records?.total ?? 0)"
              @click="
                page++;
                load();
              "
            >
              下一页
            </button>
          </div>
        </section>
        <section class="business-detail">
          <template v-if="selected"
            ><div class="business-detail-heading">
              <h2>{{ selected.title }}</h2>
              <span class="business-status" :data-status="selected.status">{{
                labels[selected.status]
              }}</span>
            </div>
            <p class="muted">
              {{ selected.number }} · 版本 {{ selected.version }}
            </p>
            <dl class="business-facts">
              <dt>创建人</dt>
              <dd>{{ own ? "本人" : `账号 ${selected.ownerUserId}` }}</dd>
              <dt>创建组织</dt>
              <dd>{{ selected.organizationId }}</dd>
              <dt>指定审批人</dt>
              <dd>
                {{
                  selected.approverUserId
                    ? `账号 ${selected.approverUserId}`
                    : "尚未指定"
                }}
              </dd>
              <dt>最近更新</dt>
              <dd>{{ when(selected.updatedAt) }}</dd>
            </dl>
            <article class="business-text">
              <h3>公文正文</h3>
              <p>{{ selected.body || "未填写" }}</p>
            </article>
            <div class="business-actions">
              <button
                v-if="own && editable && can('update')"
                :disabled="busy"
                @click="edit(false)"
              >
                编辑公文</button
              ><button
                v-if="own && editable && can('submit')"
                class="primary"
                :disabled="busy"
                @click="submitDialog"
              >
                提交审批</button
              ><button
                v-if="own && selected.status === 'SUBMITTED' && can('withdraw')"
                :disabled="busy"
                @click="perform('withdraw')"
              >
                撤回公文</button
              ><button
                v-if="own && editable && can('void')"
                :disabled="busy"
                @click="perform('void')"
              >
                作废公文</button
              ><button
                v-if="assigned && can('approve')"
                class="primary"
                :disabled="busy"
                @click="decision('approve')"
              >
                审批通过</button
              ><button
                v-if="assigned && can('reject')"
                :disabled="busy"
                @click="decision('reject')"
              >
                驳回公文
              </button>
            </div>
            <p
              v-if="['APPROVED', 'VOIDED'].includes(selected.status)"
              class="muted"
            >
              该公文已进入终态，保留历史供查看。
            </p>
            <section class="business-history">
              <h3>办理记录</h3>
              <ol>
                <li v-for="event in history" :key="event.id">
                  <strong>{{ actions[event.action] ?? event.action }}</strong
                  ><span
                    >{{ labels[event.toStatus] }} · 账号
                    {{ event.actorUserId }} · {{ when(event.createdAt) }}</span
                  >
                  <p v-if="event.comment">{{ event.comment }}</p>
                </li>
              </ol>
            </section></template
          >
          <p v-else class="muted">
            选择一份公文，查看正文、当前状态与办理记录。
          </p>
        </section>
      </div>
    </template>
  </section>
  <RecordDialog
    v-if="dialog"
    :title="
      dialog === 'new'
        ? '新建公文'
        : dialog === 'edit'
          ? '编辑公文'
          : dialog === 'submit'
            ? '选择审批人'
            : dialog === 'approve'
              ? '审批通过'
              : '驳回公文'
    "
    :busy="busy"
    @close="close"
  >
    <p v-if="error" role="alert" class="error-box">{{ error }}</p>
    <form
      v-if="dialog === 'new' || dialog === 'edit'"
      class="record-form"
      :inert="busy"
      @input="dirty = true"
      @submit.prevent="save"
    >
      <label
        >公文标题<input v-model="form.title" required maxlength="160" /></label
      ><label
        >公文正文<textarea v-model="form.body" maxlength="10000" rows="12" />
      </label>
      <p class="muted">
        保存为草稿后选择审批人并提交；提交后冻结内容，驳回后可重新编辑。
      </p>
      <button class="primary" :disabled="busy">保存公文草稿</button>
    </form>
    <form
      v-else-if="dialog === 'submit'"
      class="record-form"
      :inert="busy"
      @submit.prevent="perform('submit')"
    >
      <label
        >指定审批人<select v-model="approver" required>
          <option value="">请选择</option>
          <option v-for="a in approvers" :key="a.id" :value="a.id">
            {{ a.username }}
          </option>
        </select></label
      >
      <p v-if="!approvers.length" class="info-box">
        当前没有可审批此记录的其他账号。请联系管理员配置审批人的查看、审批权限及数据范围。
      </p>
      <p class="muted">只有指定审批人能处理；审批时会重新检查其当前权限。</p>
      <button class="primary" :disabled="busy || !approver">
        确认提交审批
      </button>
    </form>
    <form
      v-else
      class="record-form"
      :inert="busy"
      @submit.prevent="perform(dialog === 'approve' ? 'approve' : 'reject')"
    >
      <label
        >审批意见<textarea
          v-model="comment"
          :required="dialog === 'reject'"
          maxlength="500"
          rows="5"
        />
      </label>
      <p class="muted">驳回意见必填，最多500字。处理记录保留在本公文历史中。</p>
      <button class="primary" :disabled="busy">
        {{ dialog === "approve" ? "确认通过" : "确认驳回" }}
      </button>
    </form>
  </RecordDialog>
</template>
