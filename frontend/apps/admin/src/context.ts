/** 功能：admin工作区共享真实应用上下文；按钮仅作提示，服务器仍核验每次权限与版本。 */
import {
  computed,
  inject,
  ref,
  reactive,
  type InjectionKey,
  type Ref,
  onBeforeUnmount,
} from "vue";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { ApiError, adminApi, type Schema } from "@municipal/api";
import { useSession } from "@municipal/shell/session";
export const contextKey: InjectionKey<ReturnType<typeof createContext>> =
  Symbol("admin-context");
export function createContext() {
  const session = useSession(),
    apps = ref<Schema<"AccessApplication">[]>([]),
    appId = ref(""),
    orgs = ref<Schema<"AccessOrganizationChoice">[]>([]),
    revision = ref(0),
    dirty = ref(false);
  const current = computed(() => apps.value.find((a) => a.id === appId.value));
  const accountNames = reactive<Record<string, string>>({});
  const nameRequests = new Map<string, Promise<void>>();
  async function resolveAccountName(id: string) {
    if (accountNames[id] || nameRequests.has(id)) return nameRequests.get(id);
    const task = adminApi
      .account(id)
      .then((a) => {
        accountNames[id] = a.username;
      })
      .catch(() => {})
      .finally(() => nameRequests.delete(id));
    nameRequests.set(id, task);
    return task;
  }
  const accountName = (id: string) =>
    accountNames[id] ??
    (session.user?.id === id ? session.user.username : "管理员");
  const platform = computed(
    () => session.user?.permissions.includes("admin:platform:manage") ?? false,
  );
  const can = (op: string) =>
    platform.value
      ? !!session.user?.permissions.includes(op)
      : !!current.value?.allowedOperations.includes(op);
  let sequence = 0;
  async function refresh() {
    const request = ++sequence;
    await session.refresh();
    if (request !== sequence) return;
    const result = await adminApi.applications();
    if (request !== sequence) return;
    apps.value = result.items;
    if (!apps.value.some((a) => a.id === appId.value))
      appId.value =
        apps.value.find((a) => a.code === "oa")?.id ?? apps.value[0]?.id ?? "";
    await refreshOrgs();
    revision.value++;
  }
  async function refreshOrgs() {
    const id = appId.value;
    if (!id) {
      orgs.value = [];
      return;
    }
    const result = await adminApi.organizationChoices(id);
    if (appId.value === id) orgs.value = result.items;
  }
  function discard() {
    if (
      !dirty.value ||
      window.confirm("还有未保存的修改，确定放弃这些修改？")
    ) {
      dirty.value = false;
      return true;
    }
    return false;
  }
  return {
    session,
    apps,
    appId,
    current,
    platform,
    orgs,
    revision,
    dirty,
    can,
    refresh,
    refreshOrgs,
    discard,
    accountNames,
    resolveAccountName,
    accountName,
  };
}
export function useAdmin() {
  const c = inject(contextKey);
  if (!c) throw Error("Admin context missing");
  return c;
}
export function useTask() {
  const busy = ref(false),
    error = ref(""),
    notice = ref("");
  async function run<T>(fn: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) return;
    busy.value = true;
    error.value = "";
    notice.value = "";
    try {
      return await fn();
    } catch (e) {
      if (e instanceof ApiError) {
        error.value =
          e.message +
          (e.status === 409
            ? "。记录已变化，请先重新加载，检查差异后再预览。"
            : e.status === 503
              ? "。操作结果尚需确认，请刷新记录后再决定是否重试。"
              : "") +
          (e.traceId ? `（请求编号 ${e.traceId}）` : "");
      } else if (!(e instanceof DOMException && e.name === "AbortError"))
        error.value = "连接失败，请检查网络后重试。";
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, notice, run };
}
export function useDirty(dirty: Ref<boolean>) {
  const c = useAdmin();
  const handler = () => !dirty.value || c.discard();
  onBeforeRouteLeave(handler);
  onBeforeRouteUpdate(handler);
  onBeforeUnmount(() => {
    c.dirty.value = false;
  });
}
export function date(value: string | null | undefined) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
      }).format(new Date(value))
    : "不限";
}
export function inputTime(value: string | null | undefined) {
  if (!value) return "";
  return new Date(new Date(value).getTime() + 8 * 3600_000)
    .toISOString()
    .slice(0, 16);
}
export function utc(value: string) {
  if (!value) return null;
  const time = new Date(`${value}:00+08:00`);
  if (!Number.isFinite(time.getTime())) throw Error("Invalid time");
  return time.toISOString();
}
export const kinds: Record<string, string> = {
  PLATFORM: "平台角色",
  COMMON: "通用角色",
  SCOPED: "专用角色",
  LEGACY: "历史引导角色",
};
export const scopes: Record<string, string> = {
  ALL: "全公司数据",
  SELF: "本人数据",
  ORG: "指定组织",
  ORG_SUBTREE: "组织及下级",
};

/** API模型仅含JSON值；复制响应后编辑，避免修改Vue代理或原始版本快照。 */
export function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
