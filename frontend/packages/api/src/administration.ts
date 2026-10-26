/** 功能：真实管理接口薄适配；请求/响应全部引用唯一OpenAPI生成模型，复用client的Cookie/CSRF出口。 */
import { readData, sendData, type Schema } from "./client";
const s = (id: string) => encodeURIComponent(id);
const app = (id: string) => `/admin/applications/${s(id)}`;
export function query(
  values: Record<string, string | number | boolean | null | undefined>,
) {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(values))
    if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  return p.size ? `?${p}` : "";
}
export const adminApi = {
  account: (id: string) =>
    readData<Schema<"ManagedAccount">>(`/admin/accounts/${s(id)}`),
  employee: (id: string) =>
    readData<Schema<"Employee">>(`/admin/employees/${s(id)}`),
  organizations: () =>
    readData<{ items: Schema<"Organization">[] }>("/admin/organizations"),
  createOrganization: (r: Schema<"OrganizationCreate">) =>
    sendData<Schema<"Organization">>("/admin/organizations", "POST", r),
  updateOrganization: (id: string, r: Schema<"OrganizationUpdate">) =>
    sendData<Schema<"Organization">>(`/admin/organizations/${s(id)}`, "PUT", r),
  organizationStatus: (id: string, r: Schema<"StatusChange">) =>
    sendData<Schema<"Organization">>(
      `/admin/organizations/${s(id)}/status`,
      "POST",
      r,
    ),
  previewMove: (id: string, r: Schema<"MovePreviewRequest">) =>
    sendData<Schema<"MovePreview">>(
      `/admin/organizations/${s(id)}/move-preview`,
      "POST",
      r,
    ),
  moveOrganization: (id: string, previewToken: string) =>
    sendData<Schema<"Organization">>(
      `/admin/organizations/${s(id)}/move`,
      "POST",
      { previewToken },
    ),
  employees: (
    p: Record<string, string | number | boolean | null | undefined>,
  ) => readData<Schema<"EmployeePage">>(`/admin/employees${query(p)}`),
  createEmployee: (r: Schema<"EmployeeCreate">) =>
    sendData<Schema<"Employee">>("/admin/employees", "POST", r),
  updateEmployee: (id: string, r: Schema<"EmployeeUpdate">) =>
    sendData<Schema<"Employee">>(`/admin/employees/${s(id)}`, "PUT", r),
  employeeStatus: (id: string, r: Schema<"StatusChange">) =>
    sendData<Schema<"Employee">>(`/admin/employees/${s(id)}/status`, "POST", r),
  employeeAccount: (id: string) =>
    readData<Schema<"ManagedAccount"> | null>(
      `/admin/employees/${s(id)}/account`,
    ),
  accounts: (p: Record<string, string | number | boolean | null | undefined>) =>
    readData<Schema<"ManagedAccountPage">>(`/admin/accounts${query(p)}`),
  createAccount: (r: Schema<"AccountCreate">) =>
    sendData<Schema<"TemporaryCredential">>("/admin/accounts", "POST", r),
  accountStatus: (id: string, r: Schema<"StatusChange">) =>
    sendData<Schema<"ManagedAccount">>(
      `/admin/accounts/${s(id)}/status`,
      "POST",
      r,
    ),
  resetPassword: (id: string, expectedVersion: number) =>
    sendData<Schema<"TemporaryCredential">>(
      `/admin/accounts/${s(id)}/password-reset`,
      "POST",
      { expectedVersion },
    ),
  applications: () =>
    readData<{ items: Schema<"AccessApplication">[] }>(
      "/admin/managed-applications",
    ),
  organizationChoices: (a: string) =>
    readData<{ items: Schema<"AccessOrganizationChoice">[] }>(
      `${app(a)}/organizations`,
    ),
  permissions: (a: string) =>
    readData<{ items: Schema<"AccessPermission">[] }>(`${app(a)}/permissions`),
  roles: (a: string, p: Record<string, string | number>) =>
    readData<Schema<"AccessRolePage">>(`${app(a)}/roles${query(p)}`),
  role: (a: string, id: string) =>
    readData<Schema<"AccessRole">>(`${app(a)}/roles/${s(id)}`),
  createRole: (a: string, r: Schema<"AccessRoleCreate">) =>
    sendData<Schema<"AccessRole">>(`${app(a)}/roles`, "POST", r),
  updateRole: (a: string, id: string, r: Schema<"AccessRoleUpdate">) =>
    sendData<Schema<"AccessRole">>(`${app(a)}/roles/${s(id)}`, "PUT", r),
  roleStatus: (a: string, id: string, r: Schema<"StatusChange">) =>
    sendData<Schema<"AccessRole">>(
      `${app(a)}/roles/${s(id)}/status`,
      "POST",
      r,
    ),
  rolePermissions: (a: string, id: string) =>
    readData<Schema<"AccessRolePermissions">>(
      `${app(a)}/roles/${s(id)}/permissions`,
    ),
  previewPermissions: (
    a: string,
    id: string,
    r: Schema<"AccessPermissionSet">,
  ) =>
    sendData<Schema<"AccessPreview">>(
      `${app(a)}/roles/${s(id)}/permissions/preview`,
      "POST",
      r,
    ),
  commitPermissions: (a: string, id: string, previewToken: string) =>
    sendData<Schema<"AccessRolePermissions">>(
      `${app(a)}/roles/${s(id)}/permissions`,
      "PUT",
      { previewToken },
    ),
  members: (a: string, id: string, p: Record<string, string | number>) =>
    readData<Schema<"AccessRoleMemberPage">>(
      `${app(a)}/roles/${s(id)}/members${query(p)}`,
    ),
  addMembers: (a: string, id: string, r: Schema<"AccessMemberBatch">) =>
    sendData<Schema<"AccessBatchResult">>(
      `${app(a)}/roles/${s(id)}/members`,
      "POST",
      r,
    ),
  removeMember: (a: string, id: string, r: Schema<"AccessMemberRemoval">) =>
    sendData<Schema<"AccessUserRoles">>(
      `${app(a)}/roles/${s(id)}/members/remove`,
      "POST",
      r,
    ),
  people: (a: string, p: Record<string, string | number>) =>
    readData<Schema<"AccessPersonPage">>(`${app(a)}/people${query(p)}`),
  userRoles: (a: string, id: string) =>
    readData<Schema<"AccessUserRoles">>(`${app(a)}/users/${s(id)}/roles`),
  previewUserRoles: (a: string, id: string, r: Schema<"AccessUserRoleSet">) =>
    sendData<Schema<"AccessPreview">>(
      `${app(a)}/users/${s(id)}/roles/preview`,
      "POST",
      r,
    ),
  commitUserRoles: (a: string, id: string, previewToken: string) =>
    sendData<Schema<"AccessUserRoles">>(
      `${app(a)}/users/${s(id)}/roles`,
      "PUT",
      { previewToken },
    ),
  effective: (a: string, id: string) =>
    readData<Schema<"AccessEffective">>(
      `${app(a)}/users/${s(id)}/effective-permissions`,
    ),
  ownEffective: (a: string) =>
    readData<Schema<"AccessEffective">>(
      `/auth/effective-permissions${query({ applicationId: a })}`,
    ),
  delegations: (a: string, p: Record<string, string | number>) =>
    readData<Schema<"AccessDelegationPage">>(
      `${app(a)}/delegations${query(p)}`,
    ),
  saveDelegation: (
    a: string,
    id: string | null,
    r: Schema<"AccessDelegationSet">,
  ) =>
    sendData<Schema<"AccessDelegation">>(
      `${app(a)}/delegations${id ? `/${s(id)}` : ""}`,
      id ? "PUT" : "POST",
      r,
    ),
  delegationStatus: (a: string, id: string, r: Schema<"StatusChange">) =>
    sendData<Schema<"AccessDelegation">>(
      `${app(a)}/delegations/${s(id)}/status`,
      "POST",
      r,
    ),
  responsibility: (a: string, id: string) =>
    readData<Schema<"AccessResponsibility"> | null>(
      `${app(a)}/responsibilities/${s(id)}`,
    ),
  setResponsibility: (
    a: string,
    id: string,
    r: Schema<"AccessResponsibilitySet">,
  ) =>
    sendData<Schema<"AccessResponsibility">>(
      `${app(a)}/responsibilities/${s(id)}`,
      "PUT",
      r,
    ),
  previewOwner: (a: string, id: string, r: Schema<"AccessRoleOwner">) =>
    sendData<Schema<"AccessPreview">>(
      `${app(a)}/roles/${s(id)}/owner-preview`,
      "POST",
      r,
    ),
  commitOwner: (a: string, id: string, previewToken: string) =>
    sendData<Schema<"AccessRole">>(`${app(a)}/roles/${s(id)}/owner`, "PUT", {
      previewToken,
    }),
  previewAssignment: (id: string, r: Schema<"AccessEmployeeTransfer">) =>
    sendData<Schema<"AccessPreview">>(
      `/admin/employees/${s(id)}/assignment-preview`,
      "POST",
      r,
    ),
  commitAssignment: (id: string, previewToken: string) =>
    sendData<Schema<"AccessEmployeeTransferResult">>(
      `/admin/employees/${s(id)}/assignment`,
      "PUT",
      { previewToken },
    ),
  audit: (page: number) =>
    readData<Schema<"AuditRecordPage">>(
      `/admin/audit${query({ page, size: 20 })}`,
    ),
};
