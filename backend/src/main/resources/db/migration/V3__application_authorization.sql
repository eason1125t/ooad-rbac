-- 功能：单应用角色、数据范围、平台委派和独立维护责任；V1/V2保持原校验和。
ALTER TABLE auth_role
 ADD COLUMN application_id BIGINT NULL COMMENT 'LEGACY为空；普通角色只属于一个应用',
 ADD COLUMN kind VARCHAR(12) NOT NULL DEFAULT 'LEGACY' COMMENT 'PLATFORM/COMMON/SCOPED/LEGACY',
 ADD COLUMN owner_user_id BIGINT NULL COMMENT '专用角色维护人，不参与成员运行时授权',
 ADD COLUMN personnel_all BOOLEAN NOT NULL DEFAULT TRUE COMMENT '人员适用范围为全公司',
 ADD COLUMN name_key VARCHAR(100) NULL COMMENT '规范名称，应用和维护人内唯一',
 ADD COLUMN app_key BIGINT GENERATED ALWAYS AS (COALESCE(application_id,0)) STORED,
 ADD COLUMN owner_key BIGINT GENERATED ALWAYS AS (COALESCE(owner_user_id,0)) STORED,
 ADD CONSTRAINT fk_role_application FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
 ADD CONSTRAINT fk_role_owner FOREIGN KEY(owner_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 ADD CONSTRAINT uk_role_name UNIQUE(app_key,owner_key,name_key),
 ADD CONSTRAINT ck_role_kind CHECK ((kind='LEGACY' AND application_id IS NULL AND owner_user_id IS NULL) OR (kind='PLATFORM' AND application_id=1 AND owner_user_id IS NULL) OR (kind='COMMON' AND application_id<>1 AND application_id IS NOT NULL AND owner_user_id IS NULL) OR (kind='SCOPED' AND application_id<>1 AND application_id IS NOT NULL AND owner_user_id IS NOT NULL));
UPDATE auth_role SET application_id=1,kind='PLATFORM' WHERE code='demo_platform_view';
UPDATE auth_role SET application_id=2,kind='COMMON' WHERE code='demo_oa';
UPDATE auth_role SET application_id=3,kind='COMMON' WHERE code='demo_pmis';
UPDATE auth_role SET name_key=LOWER(TRIM(name));
ALTER TABLE auth_permission
 ADD COLUMN implementation_status VARCHAR(16) NOT NULL DEFAULT 'IMPLEMENTED' COMMENT '仅实现权限允许新增授予',
 ADD CONSTRAINT ck_permission_implementation CHECK(implementation_status IN ('IMPLEMENTED','PLANNED'));
ALTER TABLE auth_role_permission
 ADD COLUMN scope_kind VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'SELF/ORG/ORG_SUBTREE/ALL',
 ADD CONSTRAINT ck_rp_scope CHECK(scope_kind IN ('SELF','ORG','ORG_SUBTREE','ALL'));
ALTER TABLE auth_user_role
 ADD COLUMN granted_by BIGINT NULL COMMENT '历史未知为空；授予人后续状态不参与计算',
 ADD COLUMN granted_at DATETIME(3) NULL COMMENT '本次新授/扩大时间，不改写保留关系',
 ADD CONSTRAINT fk_ur_grantor FOREIGN KEY(granted_by) REFERENCES id_user(id) ON DELETE RESTRICT;

CREATE TABLE auth_role_organization (
 role_id BIGINT NOT NULL COMMENT '专用角色',
 organization_id BIGINT NOT NULL COMMENT '适用组织',
 include_descendants BOOLEAN NOT NULL COMMENT '是否包含后代',
 PRIMARY KEY(role_id,organization_id),
 CONSTRAINT fk_ro_role FOREIGN KEY(role_id) REFERENCES auth_role(id) ON DELETE RESTRICT,
 CONSTRAINT fk_ro_org FOREIGN KEY(organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_role_permission_organization (
 role_id BIGINT NOT NULL COMMENT '角色',
 permission_id BIGINT NOT NULL COMMENT '权限',
 organization_id BIGINT NOT NULL COMMENT '数据范围节点',
 PRIMARY KEY(role_id,permission_id,organization_id),
 CONSTRAINT fk_rpo_source FOREIGN KEY(role_id,permission_id) REFERENCES auth_role_permission(role_id,permission_id) ON DELETE RESTRICT,
 CONSTRAINT fk_rpo_org FOREIGN KEY(organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_namespace_guard (
 application_id BIGINT NOT NULL COMMENT '应用授权命名空间',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '任何管理变更使预览过时',
 PRIMARY KEY(application_id),
 CONSTRAINT fk_namespace_app FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
INSERT INTO auth_namespace_guard(application_id) SELECT id FROM auth_application;
CREATE TABLE auth_delegation (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '平台任命',
 application_id BIGINT NOT NULL COMMENT '业务应用',
 administrator_user_id BIGINT NOT NULL COMMENT '被任命管理员',
 enabled BOOLEAN NOT NULL COMMENT '是否具备管理资格',
 valid_from DATETIME(3) NULL COMMENT '开始含',
 valid_to DATETIME(3) NULL COMMENT '结束不含',
 people_all BOOLEAN NOT NULL COMMENT '显式全公司范围',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '配置版本',
 PRIMARY KEY(id),
 CONSTRAINT uk_delegation UNIQUE(application_id,administrator_user_id),
 CONSTRAINT fk_delegation_app FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
 CONSTRAINT fk_delegation_admin FOREIGN KEY(administrator_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_delegation_app CHECK(application_id<>1),
 CONSTRAINT ck_delegation_time CHECK(valid_from IS NULL OR valid_to IS NULL OR valid_from<valid_to),
 INDEX ix_delegation_admin(administrator_user_id,enabled,application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_delegation_organization (
 delegation_id BIGINT NOT NULL COMMENT '委派',
 organization_id BIGINT NOT NULL COMMENT '平台指定组织',
 include_descendants BOOLEAN NOT NULL COMMENT '包含后代',
 PRIMARY KEY(delegation_id,organization_id),
 CONSTRAINT fk_do_delegation FOREIGN KEY(delegation_id) REFERENCES auth_delegation(id) ON DELETE RESTRICT,
 CONSTRAINT fk_do_org FOREIGN KEY(organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_delegation_role (
 delegation_id BIGINT NOT NULL COMMENT '委派',
 role_id BIGINT NOT NULL COMMENT '可授通用角色白名单',
 PRIMARY KEY(delegation_id,role_id),
 CONSTRAINT fk_dr_delegation FOREIGN KEY(delegation_id) REFERENCES auth_delegation(id) ON DELETE RESTRICT,
 CONSTRAINT fk_dr_role FOREIGN KEY(role_id) REFERENCES auth_role(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_delegation_permission (
 delegation_id BIGINT NOT NULL COMMENT '委派',
 permission_id BIGINT NOT NULL COMMENT '平台指定权限上限',
 scope_kind VARCHAR(16) NOT NULL COMMENT '数据范围上限模板',
 PRIMARY KEY(delegation_id,permission_id),
 CONSTRAINT fk_dp_delegation FOREIGN KEY(delegation_id) REFERENCES auth_delegation(id) ON DELETE RESTRICT,
 CONSTRAINT fk_dp_permission FOREIGN KEY(permission_id) REFERENCES auth_permission(id) ON DELETE RESTRICT,
 CONSTRAINT ck_dp_scope CHECK(scope_kind IN ('SELF','ORG','ORG_SUBTREE','ALL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_delegation_permission_organization (
 delegation_id BIGINT NOT NULL COMMENT '委派',
 permission_id BIGINT NOT NULL COMMENT '上限权限',
 organization_id BIGINT NOT NULL COMMENT '上限组织节点',
 PRIMARY KEY(delegation_id,permission_id,organization_id),
 CONSTRAINT fk_dpo_source FOREIGN KEY(delegation_id,permission_id) REFERENCES auth_delegation_permission(delegation_id,permission_id) ON DELETE RESTRICT,
 CONSTRAINT fk_dpo_org FOREIGN KEY(organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_delegation_action (
 delegation_id BIGINT NOT NULL COMMENT '显式平台任命',
 permission_id BIGINT NOT NULL COMMENT '本应用管理动作资格，不等于业务权限',
 PRIMARY KEY(delegation_id,permission_id),
 CONSTRAINT fk_da_delegation FOREIGN KEY(delegation_id) REFERENCES auth_delegation(id) ON DELETE RESTRICT,
 CONSTRAINT fk_da_permission FOREIGN KEY(permission_id) REFERENCES auth_permission(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_person_responsibility (
 application_id BIGINT NOT NULL COMMENT '应用',
 user_id BIGINT NOT NULL COMMENT '由谁维护的目标人员',
 delegation_id BIGINT NULL COMMENT '维护任命；不参与目标权限计算',
 state VARCHAR(12) NOT NULL COMMENT 'CURRENT/AWAITING',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '交接版本',
 PRIMARY KEY(application_id,user_id),
 CONSTRAINT fk_pr_app FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
 CONSTRAINT fk_pr_user FOREIGN KEY(user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_pr_delegation FOREIGN KEY(delegation_id) REFERENCES auth_delegation(id) ON DELETE RESTRICT,
 CONSTRAINT ck_pr_state CHECK((state='CURRENT' AND delegation_id IS NOT NULL) OR state='AWAITING'),
 INDEX ix_pr_delegation(delegation_id,state,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_person_version (
 application_id BIGINT NOT NULL COMMENT '应用',
 user_id BIGINT NOT NULL COMMENT '人员账号',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '仅本应用角色覆盖版本',
 PRIMARY KEY(application_id,user_id),
 CONSTRAINT fk_pv_app FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
 CONSTRAINT fk_pv_user FOREIGN KEY(user_id) REFERENCES id_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
CREATE TABLE auth_change_preview (
 token CHAR(36) NOT NULL COMMENT '随机一次性预览',
 actor_user_id BIGINT NOT NULL COMMENT '实际主体',
 kind VARCHAR(24) NOT NULL COMMENT '权限/成员/交接/调岗',
 target_id BIGINT NOT NULL COMMENT '相应实体ID',
 application_id BIGINT NULL COMMENT '调岗跨应用为空',
 target_version BIGINT NOT NULL COMMENT '目标覆盖版本',
 tree_version BIGINT NOT NULL COMMENT '目录快照',
 namespace_json JSON NOT NULL COMMENT '命名空间版本快照',
 payload_json JSON NOT NULL COMMENT '已校验命令；禁止凭据',
 expires_at DATETIME(3) NOT NULL COMMENT '十五分钟',
 consumed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已消费',
 PRIMARY KEY(token),
 CONSTRAINT fk_cp_actor FOREIGN KEY(actor_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_cp_app FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
 INDEX ix_cp_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

INSERT INTO auth_permission(application_id,code,name) VALUES
 (1,'admin:application:view','查询可管理应用'),
 (1,'admin:role:view','查询应用角色'),(1,'admin:role:create','创建角色'),
 (1,'admin:role:update','编辑角色'),(1,'admin:role:status','角色启停'),
 (1,'admin:role:permissions','配置角色权限'),(1,'admin:role:members','维护角色成员'),
 (1,'admin:role:transfer','转交角色维护人'),
 (1,'admin:grant:view','查询人员授权'),(1,'admin:grant:update','配置人员角色'),
 (1,'admin:delegation:view','查询管理员委派'),(1,'admin:delegation:manage','配置管理员委派'),
 (1,'admin:responsibility:manage','转交人员维护责任'),(1,'admin:employee:transfer','人员原子换角');
INSERT INTO auth_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM auth_role r JOIN auth_permission p ON p.application_id=1
 WHERE r.code='bootstrap_admin' AND p.code IN ('admin:application:view','admin:role:view','admin:role:create','admin:role:update','admin:role:status','admin:role:permissions','admin:role:members','admin:role:transfer','admin:grant:view','admin:grant:update','admin:delegation:view','admin:delegation:manage','admin:responsibility:manage','admin:employee:transfer');
