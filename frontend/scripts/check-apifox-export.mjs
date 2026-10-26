/** 功能：比较官方MCP导出的Apifox协作视图和唯一OpenAPI；排除展示扩展，验证接口及安全语义。 */
import SwaggerParser from "@apidevtools/swagger-parser";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const root = new URL("../../", import.meta.url);
const reportPath = new URL("reports/environment/apifox-mcp-check.json", root);
const exported = JSON.parse(
  await fs.readFile(
    new URL("reports/environment/apifox-export-result.json", root),
    "utf8",
  ),
);
const remoteDocument = JSON.parse(
  exported.content.find((block) => block.type === "text").text,
);
const local = await SwaggerParser.dereference(
  fileURLToPath(new URL("contracts/openapi.yaml", root)),
);
const remote = await SwaggerParser.dereference(remoteDocument);
const methods = new Set([
  "get",
  "post",
  "put",
  "patch",
  "delete",
  "head",
  "options",
]);
const mismatches = [];

// 排序集合字段并移除平台展示信息；保留类型、约束、required、默认值等契约语义。
function normalize(value, parentKey = "") {
  if (Array.isArray(value)) {
    const normalized = value.map((item) => normalize(item));
    return ["required", "enum"].includes(parentKey)
      ? normalized.sort()
      : normalized;
  }
  if (value && typeof value === "object") {
    // properties中的键是业务字段名；不能把名为description/title的字段当展示元数据删掉。
    if (parentKey === "properties") {
      return Object.fromEntries(
        Object.keys(value)
          .sort()
          .map((key) => [key, normalize(value[key])]),
      );
    }
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .filter((key) => {
          if (/^x-apifox|^x-run-in-apifox/.test(key)) return false;
          if (key === "properties" && Object.keys(value[key]).length === 0)
            return false;
          if (
            key === "required" &&
            Array.isArray(value[key]) &&
            value[key].length === 0
          )
            return false;
          if (["title", "description", "example", "examples"].includes(key))
            return false;
          if (
            ["nullable", "deprecated", "readOnly", "writeOnly"].includes(key) &&
            value[key] === false
          )
            return false;
          return !(key === "additionalProperties" && value[key] === true);
        })
        .map((key) => [key, normalize(value[key], key)]),
    );
  }
  return value;
}

function content(value = {}) {
  return Object.fromEntries(
    Object.entries(value)
      .map(([mediaType, item]) => [mediaType, normalize(item.schema)])
      .sort(([a], [b]) => a.localeCompare(b)),
  );
}

function operation(api, pathItem, item) {
  const security = (item.security ?? api.security ?? []).map((requirement) =>
    Object.fromEntries(
      Object.entries(requirement).filter(([key]) => !key.startsWith("x-")),
    ),
  );
  return {
    operationId: item.operationId,
    metadata: Object.fromEntries(
      [
        "x-application",
        "x-requirements",
        "x-access",
        "x-permission",
        "x-platform-only",
        "x-managed-application",
        "x-management-console",
        "x-csrf",
      ].map((key) => [key, item[key]]),
    ),
    security: normalize(security),
    parameters: [...(pathItem.parameters ?? []), ...(item.parameters ?? [])]
      .map((parameter) =>
        normalize({
          name: parameter.name,
          in: parameter.in,
          required: parameter.required ?? false,
          schema: parameter.schema,
        }),
      )
      .sort((a, b) => `${a.in}:${a.name}`.localeCompare(`${b.in}:${b.name}`)),
    requestBody: item.requestBody
      ? {
          required: item.requestBody.required ?? false,
          content: content(item.requestBody.content),
        }
      : null,
    responses: Object.fromEntries(
      Object.entries(item.responses)
        .map(([status, response]) => [status, content(response.content)])
        .sort(([a], [b]) => a.localeCompare(b)),
    ),
  };
}

function compare(label, expected, actual) {
  if (JSON.stringify(normalize(expected)) !== JSON.stringify(normalize(actual)))
    mismatches.push(label);
}

const expectedOperations = [];
const actualOperations = [];
for (const [path, pathItem] of Object.entries(local.paths)) {
  for (const [method, item] of Object.entries(pathItem)) {
    if (!methods.has(method)) continue;
    const key = `${method.toUpperCase()} ${path}`;
    expectedOperations.push(key);
    const actual = remote.paths[path]?.[method];
    if (!actual) {
      mismatches.push(`${key}: missing`);
      continue;
    }
    const expectedView = operation(local, pathItem, item);
    const actualView = operation(remote, remote.paths[path], actual);
    for (const field of Object.keys(expectedView))
      compare(`${key}: ${field}`, expectedView[field], actualView[field]);
  }
}
for (const [path, pathItem] of Object.entries(remote.paths)) {
  for (const method of Object.keys(pathItem))
    if (methods.has(method))
      actualOperations.push(`${method.toUpperCase()} ${path}`);
}
compare("operation set", expectedOperations.sort(), actualOperations.sort());
compare(
  "schema names",
  Object.keys(local.components.schemas).sort(),
  Object.keys(remote.components.schemas).sort(),
);
for (const [name, schema] of Object.entries(local.components.schemas))
  compare(`schema ${name}`, schema, remote.components.schemas[name]);

const report = JSON.parse(await fs.readFile(reportPath, "utf8"));
Object.assign(report, {
  contractComparison: mismatches.length ? "FAIL" : "PASS",
  operationCount: expectedOperations.length,
  schemaCount: Object.keys(local.components.schemas).length,
  mismatches,
  excludedPresentationFields: [
    "info",
    "servers",
    "description",
    "title",
    "examples",
    "x-apifox-*",
  ],
  comparedFields: [
    "method/path",
    "operationId",
    "security",
    "CSRF and permission metadata",
    "parameters",
    "request and response schemas",
    "shared schemas",
  ],
});
await fs.writeFile(reportPath, JSON.stringify(report, null, 2) + "\n");
if (mismatches.length)
  throw new Error(`Apifox contract differences: ${mismatches.join("; ")}`);
console.log(
  `Apifox MCP readback PASS: ${expectedOperations.length} operations, ${Object.keys(local.components.schemas).length} schemas, authentication/CSRF/permission metadata and request/response constraints match.`,
);
