/** 功能：校验唯一OpenAPI及本轮操作元数据；生成模型前执行，杜绝原型字段成为第二份接口基线。 */
import SwaggerParser from "@apidevtools/swagger-parser";
import { fileURLToPath } from "node:url";
const path = fileURLToPath(
  new URL("../../contracts/openapi.yaml", import.meta.url),
);
const api = await SwaggerParser.validate(path);
const ids = new Set();
for (const [url, item] of Object.entries(api.paths)) {
  for (const [method, operation] of Object.entries(item)) {
    if (!["get", "post", "put", "patch", "delete"].includes(method)) continue;
    for (const key of [
      "operationId",
      "x-application",
      "x-requirements",
      "x-access",
      "x-csrf",
    ]) {
      if (operation[key] === undefined)
        throw new Error(`${method} ${url} missing ${key}`);
    }
    if (ids.has(operation.operationId))
      throw new Error("duplicate operationId");
    if (operation["x-access"] === "permission" && !operation["x-permission"])
      throw new Error("missing permission");
    ids.add(operation.operationId);
  }
}
// 外部同步进度只记录在docs/plan.adoc，不在接口契约中复制会过期的环境状态。
console.log(`OpenAPI valid: ${ids.size} operations`);
