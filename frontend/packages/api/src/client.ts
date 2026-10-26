/** 功能：三应用唯一HTTP客户端；模型来自OpenAPI生成文件，集中维护Cookie、CSRF和错误映射。 */
import type { components } from "./schema.generated";
export type Schema<K extends keyof components["schemas"]> =
  components["schemas"][K];

export type Me = components["schemas"]["Me"];
export type DemoResponse = components["schemas"]["DemoResponse"];
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly traceId: string,
  ) {
    super(message);
  }
}
let csrf: components["schemas"]["Csrf"] | undefined;

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    credentials: "same-origin",
    cache: "no-store",
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (
      response.status === 401 &&
      !["/auth/me", "/auth/login", "/auth/csrf"].includes(path)
    )
      window.dispatchEvent(new Event("municipal:session-expired"));
    const error = body as Partial<components["schemas"]["Error"]>;
    throw new ApiError(
      response.status,
      error.code ?? "REQUEST_FAILED",
      error.message ?? "服务暂不可用",
      error.traceId ?? response.headers.get("X-Trace-Id") ?? "",
    );
  }
  return body as T;
}

/** 管理客户端共用此出口；每次写前重新取得CSRF，不重放失败的写请求。 */
export async function readData<T>(
  path: string,
  signal?: AbortSignal,
): Promise<T> {
  return (await request<{ data: T }>(path, { signal })).data;
}
export async function sendData<T>(
  path: string,
  method: "POST" | "PUT",
  body: unknown,
): Promise<T> {
  const current = await token();
  return (
    await request<{ data: T }>(path, {
      method,
      headers: {
        "Content-Type": "application/json",
        [current.headerName]: current.token,
      },
      body: JSON.stringify(body),
    })
  ).data;
}

async function token() {
  csrf = (await request<{ data: components["schemas"]["Csrf"] }>("/auth/csrf"))
    .data;
  return csrf;
}

export const api = {
  async me(): Promise<Me> {
    return (await request<{ data: Me }>("/auth/me")).data;
  },
  async login(username: string, password: string) {
    const current = await token();
    await request("/auth/login", {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        [current.headerName]: current.token,
      },
      body: new URLSearchParams({ username, password }),
    });
    // 登录防护会轮换Session和CSRF；下一次不安全操作必须取新token。
    csrf = undefined;
    return api.me();
  },
  async logout() {
    const current = await token();
    await request("/auth/logout", {
      method: "POST",
      headers: { [current.headerName]: current.token },
    });
    csrf = undefined;
  },
  async changePassword(body: Schema<"PasswordChange">) {
    return sendData<unknown>("/auth/password", "POST", body);
  },
  async demo(application: "admin" | "oa" | "pmis") {
    return (await request<DemoResponse>(`/${application}/demo`)).data;
  },
};
export { adminApi } from "./administration";
