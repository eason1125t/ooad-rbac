/** 功能：V4目录接口薄适配；模型来自唯一OpenAPI，写入复用Cookie/CSRF且不自动重放。 */
import { readData, sendData, type Schema } from "./client";
const enc = encodeURIComponent;
const root = (id: string) => `/admin/applications/${enc(id)}`;
export type CatalogKind = "APPLICATION" | "RESOURCE" | "PERMISSION";
function target(app: string, kind: CatalogKind, id: string) {
  return kind === "APPLICATION"
    ? `/admin/catalog/applications/${enc(app)}`
    : `${root(app)}/${kind === "RESOURCE" ? "resources" : "permissions"}/${enc(id)}`;
}
export const catalogApi = {
  applications: () =>
    readData<Schema<"CatalogApplicationList">>("/admin/catalog/applications"),
  snapshot: (id: string) =>
    readData<Schema<"CatalogSnapshot">>(`${root(id)}/catalog`),
  createApplication: (body: Schema<"CatalogApplicationCreate">) =>
    sendData<Schema<"CatalogApplication">>(
      "/admin/catalog/applications",
      "POST",
      body,
    ),
  updateApplication: (id: string, body: Schema<"CatalogApplicationUpdate">) =>
    sendData<Schema<"CatalogApplication">>(
      `/admin/catalog/applications/${enc(id)}`,
      "PUT",
      body,
    ),
  createResource: (app: string, body: Schema<"CatalogResourceCreate">) =>
    sendData<Schema<"CatalogResource">>(`${root(app)}/resources`, "POST", body),
  updateResource: (
    app: string,
    id: string,
    body: Schema<"CatalogResourceUpdate">,
  ) =>
    sendData<Schema<"CatalogResource">>(
      `${root(app)}/resources/${enc(id)}`,
      "PUT",
      body,
    ),
  createPermission: (app: string, body: Schema<"CatalogPermissionCreate">) =>
    sendData<Schema<"CatalogPermission">>(
      `${root(app)}/permissions`,
      "POST",
      body,
    ),
  updatePermission: (
    app: string,
    id: string,
    body: Schema<"CatalogPermissionUpdate">,
  ) =>
    sendData<Schema<"CatalogPermission">>(
      `${root(app)}/permissions/${enc(id)}`,
      "PUT",
      body,
    ),
  preview: (
    app: string,
    kind: CatalogKind,
    id: string,
    body: Schema<"StatusChange">,
  ) =>
    sendData<Schema<"CatalogStatusPreview">>(
      `${target(app, kind, id)}/status-preview`,
      "POST",
      body,
    ),
  commit: (app: string, kind: CatalogKind, id: string, previewToken: string) =>
    sendData<
      | Schema<"CatalogApplication">
      | Schema<"CatalogResource">
      | Schema<"CatalogPermission">
    >(`${target(app, kind, id)}/status`, "PUT", { previewToken }),
};
