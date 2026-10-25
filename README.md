# 市政协同权限工作台 · 第一轮

面向 OOAD、软件工程导论与 JavaEE 的模块化单体课程项目。本轮已创建需求/结构化设计、8 表 Flyway、可编辑 ER/原型、唯一 OpenAPI、Spring Boot 后端及 admin、oa、pmis 三个 Vue 应用。

已实现环境初始化管理员、Spring Security 登录、Redis Session 接入、CSRF、me、三端权限演示、退出、绝对会话截止、账号状态/身份版本复核和 RBAC0 有效期/多来源计算。完整 CRUD、导入执行、业务审批、缓存同步与 RBAC1–3 尚未实现。

用户已确认[开发基线v2](docs/development-baseline.adoc)，其余方案沿用上一版。新增/扩大授权时检查管理员本人上限，既有授权独立于其以后的人事/权限变化；人员变动只替换本人角色。组织账号、导入、OA/PMIS、模型/接口和验收方案均已选定，尚未完整实现。[后端第一切片](docs/backend-iteration-1.adoc)是下一阶段入口，[决策记录](docs/decisions.adoc)保留明确决定及修正。

**环境和最小真实链路已验证。** Redis7.4.6在本机6380、Navicat配置的MySQL8.4.8专用库可用，6项真实MySQL/Redis容器联合测试全部通过，宿主实际登录/CSRF/权限/退出及同站点代理验证通过。Apifox私有项目`ooad-rbac`（8967444）已完成8接口/6模型的MCP语义回读。当前快照用于用户授权的GitHub首次归档，提交及Actions状态见[项目仓库](https://github.com/eason1125t/ooad-rbac)，未部署；实际功能及外部缺项见[进度](docs/plan.adoc)和[验证](docs/verification.adoc)。

## 本地运行（PowerShell 7）

需要 Java21、Node>=22.12、pnpm12.4.2，以及 Docker Compose（或你自行配置的 MySQL8.4、Redis7.4）。Maven 由 wrapper 下载3.9.11。保留 Boot3.5.16，支持状态代价见 [ADR](docs/adr/001-baseline.adoc)。

当前机器已配置 `.env`，请保留。WSL2.7.14、两项必需Windows组件、Docker Desktop4.96.3安装成功并已完成所需重启。Docker Linux引擎29.9.2运行正常，Compose项目名固定为ooad-rbac，Redis的PING返回PONG。当前不需要重新安装或重启；后续电脑重启后，启动Docker Desktop并执行Redis命令即可。

以下仅是新机器尚未安装 WSL 时的管理员 PowerShell 入口；脚本会显示进度，并将结果写入报告，不自动重启：

```powershell
& 'D:\lesson\26-27 SEM1\ooad rbac0-final\scripts\enable-wsl.ps1'
```

新机器 WSL 就绪后，可在普通 PowerShell 安装 Docker Desktop；当前机器已完成该安装：

```powershell
winget install --id Docker.DockerDesktop --version 4.96.3 --exact --source winget --silent --accept-source-agreements --accept-package-agreements --disable-interactivity --override 'install --user --quiet --accept-license --backend=wsl-2 --no-windows-containers'
# 启动 Docker Desktop，等待引擎就绪；必要时重新打开终端以载入 PATH。
docker info
docker compose up -d --wait redis
docker compose exec redis redis-cli ping
```

当前使用宿主 MySQL `127.0.0.1:3306/ooad_rbac`，只启动 Compose 的 Redis 即可。随后在项目根目录执行：

```powershell
pnpm -C frontend install --frozen-lockfile
./scripts/run-backend.ps1
```

另开终端，在根目录执行：

```powershell
pnpm -C frontend run dev:all
```

浏览器统一从 [管理工作台](http://127.0.0.1:5173/admin/) 进入；同站点还有 `/oa/`、`/pmis/`。管理端已落实“工作台、组织与用户、角色与授权、应用与资源、操作审计”5个一级工作区，角色权限/成员和应用资源等放在详情标签页。登录后点击“验证访问”调用真实接口；勾选“显示合成原型数据”审阅流程。管理写入按钮仍禁用，合成的员工关联、委派范围、审计和业务记录不是数据库事实。

当前机器显式启用 `SPRING_PROFILES_ACTIVE=demo`。以下开发/测试账号的口令**统一为 `1234`**，唯一运行定义在 `backend/src/main/resources/demo/accounts.json`：

| 账号 | 当前真实演示访问能力 |
|---|---|
| `super-admin` | 平台、OA、PMIS三个演示入口 |
| `oa-admin` | 平台演示入口与OA；PMIS拒绝 |
| `pmis-admin` | 平台演示入口与PMIS；OA拒绝 |
| `oa-user-1` | 仅OA演示入口 |
| `pmis-user-1` | 仅PMIS演示入口 |
| `employee-1` | 可登录，但三个演示入口均拒绝 |

业务管理员账号尚不具备未实现的委派授权写能力。示例只创建合成账号，不导入原始员工附件。旧随机引导账号 `admin` 已显式停用，保留历史。启动不会重置既有演示账号密码或恢复被撤销的角色；新的业务系统按 `<应用代码>-admin`、`<应用代码>-user-1` 命名。数据库/Redis/MCP等基础设施凭据保持原配置，不使用此简易口令。

`demo`只绑定127.0.0.1，禁止与`prod`组合；正常/正式引导入口仍要求12字符以上口令，没有默认正式密码。本机 `.env` 的两个 `BOOTSTRAP_ADMIN_*` 值已清空，由独立demo初始化器创建测试账号。当前环境切换脚本为 `./scripts/configure-demo.ps1`，它限定本项目已配置的本机3306开发库、私有备份原.env并保留基础设施凭据；无需重复运行。

全新机器若希望 MySQL/Redis 都使用 Compose：仅在没有 `.env` 时复制 `.env.example`，填写随机数据库密码，再运行 `docker compose up -d --wait`。示例显式启用本机demo并连接 `3307/rbac`，不要与当前Navicat的 `3306/ooad_rbac` 凭据混用。正常/生产部署关闭demo，使用外部强引导凭据；账号格式3–64位小写字母/数字/._-，引导密码至少12字符且最多72个UTF-8字节。

Compose 的 MySQL/Redis 只绑定本机3307/6380。`.env` 对 Compose 生效，后端通过上述脚本显式导入；宿主 Java 不自动读取 `.env`。生产用 `SPRING_PROFILES_ACTIVE=prod`、HTTPS和外部凭据。当前同站点会话不是跨域 SSO。

Apifox 开发环境前置 URL 已设为 `http://127.0.0.1:8080`。当前用户令牌已配置，`.codex/config.toml` 已启用。独立 HTTP MCP 客户端实测认证、18项工具、项目读取/导出及三个公开接口鉴权修正；重启后Codex内置Apifox工具也已载入，并实际读取项目8967444成功。新机器用户可通过 `./scripts/configure-apifox-token.ps1` 隐藏输入令牌，不要发到聊天或写进仓库。配置依据：[Apifox HTTP MCP](https://docs.apifox.com/8395000m0)、[Codex MCP](https://developers.openai.com/codex/mcp)。桌面导入与实际 MCP 操作分别记录。

重复只读验证 MCP 与契约语义（PowerShell7，无需 Docker）：

```powershell
./scripts/verify-apifox-mcp.ps1
pnpm -C frontend exec node scripts/check-apifox-export.mjs
```

比较涵盖方法/路径、operationId、鉴权、CSRF/权限元数据、参数、请求/响应和6个模型约束；排除平台展示扩展、info/servers与描述/示例，不声称导出文件逐字节相同。报告位于 `reports/environment/apifox-mcp-check.json`。

## 验证与产物

```powershell
./backend/mvnw.cmd -f backend/pom.xml verify
# 后端和本地依赖已启动时，验证当前开发库的真实链路与同站点代理；demo读取唯一账号目录。
./scripts/verify-local-login.ps1
./scripts/verify-local-login.ps1 -BaseUrl 'http://127.0.0.1:5173'
./scripts/verify-demo-accounts.ps1
pnpm -C frontend run verify
python scripts/schema_docs.py
pnpm -C frontend run docs
pnpm -C frontend exec node scripts/ui-smoke.mjs
# 可选：不需要Docker的真实MySQL隔离验证；只使用指定二进制，保留专用数据目录，不删除用户资料。
./scripts/mysql-smoke.ps1 -MySqlBin 'D:/codetool/Mysql/bin' -CheckApplication
```

Windows 文档/UI验证默认使用 Edge，可通过 `CHROME_PATH` 指定兼容 Chromium；Linux 使用已安装 Chrome 或 Puppeteer 浏览器。CI 文件已提供，尚未在远程执行。

- 后端：`backend/target/rbac-workbench-0.1.0-SNAPSHOT.jar`；真实覆盖率 `backend/target/site/jacoco/index.html` / `jacoco.xml`。
- 前端：`frontend/apps/{admin,oa,pmis}/dist/`；契约 `contracts/openapi.yaml`，生成模型 `frontend/packages/api/src/schema.generated.ts`。
- 文档：`reports/docs/`；截图 `reports/ui/`；源 `docs/`。构建报告、依赖和原始 Excel 均由 `.gitignore` 排除。
- 只读附件分析：先安装 `scripts/requirements.txt`，再执行 `python scripts/analyze_sources.py`；结果是脱敏聚合，不导入人员。

实际命令结果、跳过原因与覆盖率见[验证记录](docs/verification.adoc)。当前完整verify为26项：25通过、1项可选独立MySQL测试默认跳过；该可选项另由隔离MySQL脚本执行通过，6项容器联合测试全部通过。JaCoCo行273/284（96.13%）、分支95/120（79.17%）。当前实现结束于骨架和真实登录鉴权链，v2及开发规划已确认；下一阶段从组织/人员/账号的API、迁移和后端纵向切片开始，不先并行开发整套业务。
