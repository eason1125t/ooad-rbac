/** 功能：从唯一OpenAPI生成中文接口/模型查阅表；与06讲解和契约检查配合，不产生第二份可执行契约。 */
import SwaggerParser from "@apidevtools/swagger-parser";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
const root = new URL("../../", import.meta.url);
const api = await SwaggerParser.parse(
  fileURLToPath(new URL("contracts/openapi.yaml", root)),
);
const models = api.components.schemas;
const methods = new Set([
  "get",
  "post",
  "put",
  "patch",
  "delete",
  "head",
  "options",
]);
const operations = Object.entries(api.paths).flatMap(([url, item]) =>
  Object.entries(item)
    .filter(([method]) => methods.has(method))
    .map(([method, value]) => ({
      url,
      method,
      value,
      shared: item.parameters ?? [],
    })),
);
const esc = (value) =>
  String(value ?? "—")
    .replaceAll("|", "\\|")
    .replaceAll("\n", " ");
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .filter((key) => key !== "nullable")
      .map((key) => [key, canonical(value[key])]),
  );
}
const signatures = new Map(
  Object.entries(models).map(([name, value]) => [
    JSON.stringify(canonical(value)),
    name,
  ]),
);
const refName = (ref) => ref.split("/").at(-1);
function resolve(value) {
  if (!value?.$ref) return value;
  return value.$ref
    .slice(2)
    .split("/")
    .reduce((part, key) => part[key], api);
}
function type(value, nested = false) {
  if (!value) return "无请求体";
  if (value.$ref)
    return `<<model-${refName(value.$ref)},${refName(value.$ref)}>>`;
  const match =
    value.properties && signatures.get(JSON.stringify(canonical(value)));
  let result = match
    ? `<<model-${match},${match}>>`
    : value.type === "array"
      ? `数组（${type(value.items, true)}）`
      : value.type === "object" && value.properties && !nested
        ? `对象{${Object.entries(value.properties)
            .map(([key, item]) => `${key}:${type(item, true)}`)
            .join("，")}}`
        : value.allOf
          ? value.allOf.map((item) => type(item, true)).join(" ∩ ")
          : (value.type ?? "任意JSON");
  if (value.nullable) result += "（可空）";
  return result;
}
function constraints(s) {
  return (
    [
      s.enum && `枚举=${s.enum.join("/")}`,
      s.pattern && `格式=${s.pattern}`,
      s.format && `format=${s.format}`,
      s.minLength !== undefined && `最短${s.minLength}`,
      s.maxLength !== undefined && `最长${s.maxLength}`,
      s.minimum !== undefined && `最小${s.minimum}`,
      s.maximum !== undefined && `最大${s.maximum}`,
      s.minItems !== undefined && `至少${s.minItems}项`,
      s.maxItems !== undefined && `最多${s.maxItems}项`,
      s.default !== undefined && `默认=${JSON.stringify(s.default)}`,
    ]
      .filter(Boolean)
      .join("；") || "—"
  );
}
const labels = {
  id: "稳定标识",
  code: "稳定编码",
  name: "显示名称",
  version: "当前版本",
  expectedVersion: "提交前读取的版本；具体版本对象见06",
  expectedUserVersion: "人员+应用授权版本",
  expectedAccountVersion: "账号对象版本",
  applicationId: "应用标识",
  userId: "目标账号标识，不是操作者",
  employeeId: "人员标识",
  roleId: "角色标识",
  permissionId: "权限标识",
  organizationId: "组织标识",
  organizationIds: "组织节点集合",
  parentId: "上级组织",
  username: "登录账号",
  password: "口令，不写日志",
  currentPassword: "旧口令",
  newPassword: "新口令",
  temporaryPassword: "一次性临时凭据，不持久化",
  enabled: "是否启用",
  kind: "类型",
  ownerUserId: "专用角色维护人",
  personnelScope: "角色人员适用范围",
  peopleScope: "委派人员管理范围",
  allCompany: "明确全公司人员范围",
  includeDescendants: "是否包含下级",
  scope: "业务数据范围",
  validFrom: "生效时刻，包含",
  validTo: "结束时刻，不包含",
  memberships: "该应用的新完整角色集合",
  permissions: "权限配置/有效权限集合",
  token: "服务端返回的预览/CSRF令牌",
  previewToken: "本次预览实际返回值",
  expiresAt: "预览过期时刻",
  beforeState: "变更前状态",
  afterState: "变更后状态",
  affectedUsers: "影响人数",
  administratorUserId: "被委派管理员",
  delegationId: "人员维护责任委派",
  commonRoleIds: "可授通用角色白名单",
  permissionCeilings: "平台指定权限与范围上限",
  allowedOperations: "服务端当前允许的管理动作",
  delegablePermissions: "当前本人权限与平台上限交集",
  responsibility: "维护责任，不影响员工已有授权生效",
  sources: "有效权限来源",
  items: "本页记录",
  page: "从1开始的页码",
  size: "页大小",
  total: "总记录数",
  forcePasswordChange: "是否必须先改密",
  implementationStatus: "仅IMPLEMENTED且启用的点可新授",
  traceId: "请求追踪标识",
  displayName: "人员显示名",
  employeeCode: "稳定人员编码",
  positionName: "岗位资料，不自动授权",
  phone: "联系电话，敏感资料",
  managementAction: "KEEP保留或RETIRE撤任",
  applications: "逐应用明确的新授权集合",
  canEdit: "服务端当前是否允许编辑",
  canAssign: "服务端当前是否允许分配",
  peopleSelectable: "可作为人员管理范围节点",
  peopleSubtreeAllowed: "该人员节点是否允许包含下级",
  account: "账号视图，不包含密码散列",
  action: "管理事件动作",
  actorUserId: "服务端记录的操作者账号标识",
  affectedEmployees: "组织移动影响人数",
  all: "是否包含本应用全部数据范围",
  createdAt: "UTC创建时刻",
  data: "成功响应数据",
  delegation: "当前应用管理委派，可空",
  details: "脱敏事件详情",
  employee: "人员视图",
  grantVersion: "人员+应用授权版本",
  grantedByUserId: "历史授予者；其后续状态不参与目标授权生效",
  headerName: "写请求应携带的CSRF头名",
  legacy: "是否为历史引导来源",
  managerEmployeeId: "组织负责人，仅资料关联，不产生权限",
  memberCount: "当前成员关系数",
  members: "批量目标人员与各自版本/有效期",
  message: "可理解的结果或错误说明",
  namespaceVersions: "预览依赖的应用授权空间版本",
  newAdministratorUserId: "交接接任管理员",
  nodeType: "HEADQUARTERS总公司、BRANCH分公司、DEPARTMENT部门",
  organizations: "人员组织范围及各节点包含下级标志",
  permission: "权限目录视图",
  platform: "当前应用管理视图是否为平台身份",
  responsibilityDelegationId: "该应用新人员维护责任委派，可空",
  roleName: "角色显示名",
  self: "是否包含目标本人记录范围",
  state: "人员维护责任状态CURRENT/AWAITING等契约枚举",
  subtreeRootIds: "业务数据子树根节点集合",
  targetId: "操作目标标识",
  targetType: "操作目标类型",
  targetVersion: "预览绑定的目标版本",
  treeVersion: "预览绑定的组织目录版本",
  users: "本次受影响账号及新版本",
};
const lines = [
  "// 自动生成：frontend/scripts/generate-api-docs.mjs；修改contracts/openapi.yaml后重生成。",
  "= API接口与模型清单",
  ":toc: left",
  ":toclevels: 1",
  "",
  `契约版本${api.info.version}；${operations.length}个HTTP操作、${Object.keys(models).length}个模型。下列内容来自唯一OpenAPI；调用流程和版本语义见link:06-API设计与接口说明.adoc[06]。模型字段名保留程序名，说明使用中文。`,
  "",
  "== 操作索引",
  "",
  '[cols="1,5,4",options="header"]',
  "|===",
  "|方法 |完整路径 |操作",
];
for (const { url, method, value } of operations)
  lines.push(
    `|${method.toUpperCase()} |\`${esc(url)}\` |<<api-${value.operationId},${esc(value.summary ?? value.operationId)}>>`,
  );
lines.push("|===", "", "== 操作详情", "");
for (const { url, method, value: o, shared } of operations) {
  const boundary = o["x-platform-only"]
    ? "平台资格 + 具体动作"
    : o["x-managed-application"]
      ? "本应用委派/平台资格 + 具体动作 + 服务端范围/责任"
      : o["x-management-console"]
        ? "管理控制台资格，返回可管理应用"
        : (o["x-access"] ?? "见认证声明");
  lines.push(
    `[[api-${o.operationId}]]`,
    `=== ${method.toUpperCase()} ${url}`,
    "",
    `${esc(o.summary ?? o.operationId)}。operationId：\`${o.operationId}\`。`,
    "",
    `边界：${esc(boundary)}；权限：\`${esc(o["x-permission"] ?? "无独立业务权限码")}\`；CSRF：${o["x-csrf"] ? "需要" : "不需要"}。`,
    "",
  );
  const params = [...shared, ...(o.parameters ?? [])].map(resolve);
  if (params.length) {
    lines.push(
      '[cols="2,1,1,3,4",options="header"]',
      "|===",
      "|参数 |位置 |必填 |类型 |限制/说明",
    );
    for (const p of params)
      lines.push(
        `|\`${esc(p.name)}\` |${esc(p.in)} |${p.required ? "是" : "否"} |${type(p.schema)} |${esc([constraints(p.schema ?? {}), p.description].filter(Boolean).join("；"))}`,
      );
    lines.push("|===", "");
  }
  const request = resolve(o.requestBody);
  if (request)
    for (const [media, body] of Object.entries(request.content))
      lines.push(
        `请求体：\`${media}\`，${request.required ? "必填" : "可选"}，${type(body.schema)}。`,
        "",
      );
  else lines.push("请求体：无。", "");
  lines.push(
    '[cols="1,4,4",options="header"]',
    "|===",
    "|状态 |响应结构 |说明",
  );
  for (const [status, raw] of Object.entries(o.responses)) {
    if (!status.startsWith("2")) continue;
    const response = resolve(raw);
    const schema = Object.values(response.content ?? {})[0]?.schema;
    lines.push(
      `|${status} |${schema ? type(schema) : "无JSON模型"} |${esc(response.description)}`,
    );
  }
  lines.push("|===", "");
  lines.push(
    `契约声明的错误状态：${
      Object.keys(o.responses)
        .filter((status) => !status.startsWith("2"))
        .join("、") || "无"
    }。通用含义及恢复方式见06，完整错误结构见Error模型。`,
    "",
  );
}
lines.push(
  "== 模型与字段",
  "",
  "required与可空是两个维度：必填字段也可能要求显式null。对象默认值、数组元素和更深层嵌套的完整约束仍以OpenAPI为准；这里保留字段类型与主要标量/集合限制，不列凭据示例值。",
  "",
);
for (const [name, model] of Object.entries(models)) {
  lines.push(`[[model-${name}]]`, `=== ${name}`, "");
  if (model.description) lines.push(esc(model.description), "");
  lines.push(
    '[cols="2,3,1,4,4",options="header"]',
    "|===",
    "|字段 |类型 |必填 |限制 |用途",
  );
  for (const [field, schema] of Object.entries(model.properties ?? {}))
    lines.push(
      `|\`${field}\` |${type(schema, true)} |${model.required?.includes(field) ? "是" : "否"} |${esc(constraints(schema))} |${esc(schema.description ?? labels[field] ?? "按字段名及调用流程解释，详见06/契约")}`,
    );
  lines.push("|===", "");
}
fs.writeFileSync(
  new URL("docs/14-API接口与模型清单.adoc", root),
  lines.join("\n"),
);
console.log(
  `API documentation generated: ${operations.length} operations, ${Object.keys(models).length} models`,
);
