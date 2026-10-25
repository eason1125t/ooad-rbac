# 项目协作约束

遵守用户给出的六条 AGENTS 指令：代码规范、交付前验证、交付后给建议、审视需求、禁止批量删除、源码顶部说明用途与联系并为关键算法简短注释。

- 先读 [进度入口](docs/plan.adoc)，再读取涉及的需求、设计、契约与测试；保留现有修改。
- 设计方法：面向功能的结构化设计。第一轮骨架已完成；后续开发遵循已确认的 docs/development-baseline.adoc v2，按契约、测试设计、新增迁移、实现的纵向切片推进，不提前实现RBAC1–3。
- 单一 Spring Boot 构建，按功能分包；业务只依赖 authorization 的公开接口，权限核心不得依赖 security/HTTP/OA/PMIS。构造器注入。
- 契约唯一来源 `contracts/openapi.yaml`；可执行 DDL 唯一来源 Flyway。先改契约/测试设计，再实现。已应用迁移不得修改。
- 未声明 API 默认拒绝；使用 Spring Security 方法权限；不得关闭 CSRF、设置正式默认管理员口令或将客户端 userId 当操作者。用户指定的1234仅用于显式隔离的demo/测试账号。
- 不提交原始 Excel、个人资料、Cookie、会话标识或本地秘密。仅在用户明确授权时提交/推送；不自动部署或合并。
- 不批量删除目录/文件；仅能逐个删除明确路径文件。需要批量清理时请用户手工操作。
- 命令：`backend/mvnw.cmd -f backend/pom.xml verify`；`pnpm -C frontend run verify`；`pnpm -C frontend run docs`。完整说明见 README 与 docs/engineering.adoc。
