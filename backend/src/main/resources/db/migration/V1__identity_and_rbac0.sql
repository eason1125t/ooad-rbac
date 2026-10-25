-- 功能：建立当前登录/RBAC0所需模型；数据字典与ER由本迁移生成，MyBatis按此模型查询。
CREATE TABLE id_organization (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '组织标识',
    code VARCHAR(64) NOT NULL COMMENT '不可复用的组织编码',
    name VARCHAR(160) NOT NULL COMMENT '组织名称，不作为关联键',
    node_type VARCHAR(20) NOT NULL COMMENT 'HEADQUARTERS/BRANCH/DEPARTMENT',
    parent_id BIGINT NULL COMMENT '父组织，根节点为空',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '覆盖更新版本',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_org_code UNIQUE (code),
    CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
    CONSTRAINT ck_org_type CHECK (node_type IN ('HEADQUARTERS','BRANCH','DEPARTMENT')),
    INDEX ix_org_parent (parent_id, enabled, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE id_employee (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '员工标识',
    employee_code VARCHAR(64) NOT NULL COMMENT '导入时分配的稳定员工编码',
    display_name VARCHAR(100) NOT NULL COMMENT '姓名，不能唯一匹配负责人',
    organization_id BIGINT NOT NULL COMMENT '主要组织，可直接是分公司',
    position_name VARCHAR(100) NULL COMMENT '岗位资料，不自动授予角色',
    phone VARCHAR(32) NULL COMMENT '电话字符串，仅授权人员可读',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '人员是否在职启用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_employee_code UNIQUE (employee_code),
    CONSTRAINT fk_employee_org FOREIGN KEY (organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
    INDEX ix_employee_org (organization_id, enabled, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE id_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '登录主体标识，JSON用十进制字符串',
    username VARCHAR(64) NOT NULL COMMENT '小写规范账号，编码永不复用',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt散列，不可输出',
    employee_id BIGINT NULL COMMENT '最多一对一员工；引导管理员可为空',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号可用状态',
    auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '改密或停用增加，旧会话请求撤销',
    grant_version BIGINT NOT NULL DEFAULT 0 COMMENT '未来用户角色覆盖更新版本',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT uk_user_employee UNIQUE (employee_id),
    CONSTRAINT fk_user_employee FOREIGN KEY (employee_id) REFERENCES id_employee(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_application (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '应用标识',
    code VARCHAR(32) NOT NULL COMMENT 'admin/oa/pmis等唯一编码',
    name VARCHAR(100) NOT NULL COMMENT '应用展示名称',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '应用停用则不授予权限',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '未来应用配置版本',
    PRIMARY KEY (id),
    CONSTRAINT uk_app_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_role (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色标识，与组织层级无关',
    code VARCHAR(64) NOT NULL COMMENT '唯一且不复用的角色编码',
    name VARCHAR(100) NOT NULL COMMENT '角色展示名称',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '停用角色不得参与计算',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '角色权限覆盖更新版本',
    PRIMARY KEY (id),
    CONSTRAINT uk_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_permission (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限标识',
    application_id BIGINT NOT NULL COMMENT '所属应用，禁止跨应用误用',
    code VARCHAR(128) NOT NULL COMMENT '应用:资源:动作，唯一且不复用',
    name VARCHAR(100) NOT NULL COMMENT '功能展示名，父目录不隐式授权',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否参与计算',
    PRIMARY KEY (id),
    CONSTRAINT uk_permission_code UNIQUE (code),
    CONSTRAINT fk_permission_app FOREIGN KEY (application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
    INDEX ix_permission_app (application_id, enabled, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_user_role (
    user_id BIGINT NOT NULL COMMENT '主体标识',
    role_id BIGINT NOT NULL COMMENT '角色标识',
    valid_from DATETIME(3) NULL COMMENT 'UTC开始含边界，NULL无开始限制',
    valid_to DATETIME(3) NULL COMMENT 'UTC结束不含边界，NULL无结束限制',
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES auth_role(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ur_time CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from < valid_to),
    INDEX ix_ur_role (role_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_role_permission (
    role_id BIGINT NOT NULL COMMENT '角色标识',
    permission_id BIGINT NOT NULL COMMENT '权限标识',
    valid_from DATETIME(3) NULL COMMENT 'UTC开始含边界，NULL无开始限制',
    valid_to DATETIME(3) NULL COMMENT 'UTC结束不含边界，NULL无结束限制',
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES auth_role(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES auth_permission(id) ON DELETE RESTRICT,
    CONSTRAINT ck_rp_time CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from < valid_to),
    INDEX ix_rp_permission (permission_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- 仅建立应用、权限目录和引导角色；没有任何账号、密码或真实员工数据。
INSERT INTO auth_application (id, code, name) VALUES (1,'admin','统一权限管理'),(2,'oa','OA公文'),(3,'pmis','工程项目');
INSERT INTO auth_role (id, code, name) VALUES (1,'bootstrap_admin','引导管理员');
INSERT INTO auth_permission (id, application_id, code, name) VALUES
 (1,1,'admin:console:view','访问管理演示'),
 (2,2,'oa:document:view','访问公文演示'),
 (3,3,'pmis:project:view','访问项目演示');
INSERT INTO auth_role_permission (role_id,permission_id) VALUES (1,1),(1,2),(1,3);
