/** 功能：OA/PMIS业务客户端；各自强类型模型，共用Session/CSRF请求出口而不共享业务状态。 */
import { readData, sendData, type Schema } from "./client";
import { query } from "./administration";
function createBusinessApi<RecordType, CreateType, UpdateType, PageType>(
  application: "oa" | "pmis",
  collection: string,
) {
  const root = `/${application}/${collection}`;
  const record = (id: string) => `${root}/${encodeURIComponent(id)}`;
  return {
    context: () =>
      readData<Schema<"BusinessContext">>(`/${application}/context`),
    page: (p: Record<string, string | number | boolean>) =>
      readData<PageType>(`${root}${query(p)}`),
    get: (id: string) => readData<RecordType>(record(id)),
    create: (body: CreateType) => sendData<RecordType>(root, "POST", body),
    update: (id: string, body: UpdateType) =>
      sendData<RecordType>(record(id), "PUT", body),
    approvers: (id: string) =>
      readData<Schema<"BusinessApproverList">>(`${record(id)}/approvers`),
    history: (id: string) =>
      readData<Schema<"BusinessEventList">>(`${record(id)}/history`),
    action: (
      id: string,
      action: "submit" | "withdraw" | "approve" | "reject" | "void" | "cancel",
      body:
        | Schema<"BusinessVersion">
        | Schema<"BusinessSubmit">
        | Schema<"BusinessDecision">,
    ) => sendData<RecordType>(`${record(id)}/${action}`, "POST", body),
  };
}
export const oaApi = createBusinessApi<
  Schema<"OaDocument">,
  Schema<"OaDocumentCreate">,
  Schema<"OaDocumentUpdate">,
  Schema<"OaDocumentPage">
>("oa", "documents");
export const pmisApi = createBusinessApi<
  Schema<"PmisProject">,
  Schema<"PmisProjectCreate">,
  Schema<"PmisProjectUpdate">,
  Schema<"PmisProjectPage">
>("pmis", "projects");
