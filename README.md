# 市政协同权限工作台

当前已实现 RBAC0 管理平台：身份管理、角色权限、业务管理员委派、人员责任、原子调岗及五个真实 API 工作区。资源目录 CRUD、在线导入、OA/PMIS 审批和 RBAC1–3 尚未实现。

从 [00-文档导航](docs/00-文档导航.adoc) 开始。正文采用中文编号，历史材料独立留存，不与当前完成清单混读。

| 需要做什么 | 阅读入口 |
|---|---|
| 了解进度与开发规划 | [01-项目总览与开发规划](docs/01-项目总览与开发规划.adoc) |
| 理解架构及权限规则 | [02-架构与模块实现](docs/02-架构与模块实现.adoc)、[03-功能与权限规则详解](docs/03-功能与权限规则详解.adoc) |
| 新电脑运行 | [04-新环境安装与运行](docs/04-新环境安装与运行.adoc) |
| 测试各功能 | [05-功能操作与测试指南](docs/05-功能操作与测试指南.adoc)、[09-测试结果与验收记录](docs/09-测试结果与验收记录.adoc) |
| 查 API 和数据库 | [06-API设计与接口说明](docs/06-API设计与接口说明.adoc)、[07-数据库设计说明](docs/07-数据库设计说明.adoc) |
| 后续 RBAC1/2/3 | [10-后续开发路线与RBAC演进](docs/10-后续开发路线与RBAC演进.adoc) |
| 接手维护/课程交付 | [11-开发协作与维护约定](docs/11-开发协作与维护约定.adoc)、[12-课程交付与资料边界](docs/12-课程交付与资料边界.adoc) |

项目基线：Java21 / Spring Boot3.5.16 / MyBatis / MySQL8.4 / Redis7.4；Vue3 / TypeScript / pnpm12.4.2。当前58个API操作、67个模型、24张业务表，契约唯一来源为 [OpenAPI](contracts/openapi.yaml)，DDL唯一来源为 [Flyway](backend/src/main/resources/db/migration)。

干净检出验证：后端88项中87通过、1可选跳过，50项必需容器测试全部执行；真实浏览器17/17通过。准确口径与限制见09。发布分支为 `main`，后续开发分支为 `develop`；新环境使用最新发布提交，具体验证与CI状态见09及GitHub记录。

当前电脑保留已有 `.env` 和宿主MySQL；启动Docker/Redis后分别运行 `./scripts/run-backend.ps1` 和 `./scripts/run-frontends.ps1`。以前端脚本输出入口为准，本机最近为 http://127.0.0.1:15173/admin/ 。显式demo账号 `super-admin` / `1234`；其他演示账号与委派配置步骤见04、05。新电脑按04从独立配置开始，不能覆盖已有数据库或复制秘密。

生成可浏览说明：`pnpm -C frontend run docs`，打开 `reports/docs/00-文档导航.html`。README、AGENTS、源代码和原始任务输入保留惯例文件名；详细正文统一中文编号。
