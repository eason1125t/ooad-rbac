-- 功能：身份管理增量及最小审计；V1保持原样，闭包/版本/预览支撑IdentityDirectory事务。
ALTER TABLE id_organization
    ADD COLUMN name_key VARCHAR(160) NOT NULL DEFAULT '' COMMENT '规范名称比较键，显示名保持原文',
    ADD COLUMN parent_key BIGINT GENERATED ALWAYS AS (COALESCE(parent_id,0)) STORED COMMENT '根父域的非空唯一键',
    ADD COLUMN root_key INT GENERATED ALWAYS AS (IF(parent_id IS NULL,1,NULL)) STORED COMMENT '最多一个行政根',
    ADD COLUMN manager_employee_id BIGINT NULL COMMENT '负责人资料，不产生角色',
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC更新时间';
UPDATE id_organization SET name_key=LOWER(TRIM(name));
ALTER TABLE id_organization
    ADD CONSTRAINT uk_org_sibling UNIQUE (parent_key,name_key),
    ADD CONSTRAINT uk_org_root UNIQUE (root_key),
    ADD CONSTRAINT ck_org_root_type CHECK ((node_type='HEADQUARTERS' AND parent_id IS NULL) OR (node_type<>'HEADQUARTERS' AND parent_id IS NOT NULL)),
    ADD CONSTRAINT fk_org_manager FOREIGN KEY (manager_employee_id) REFERENCES id_employee(id) ON DELETE RESTRICT;
ALTER TABLE id_employee
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '资料覆盖更新版本',
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC更新时间';
ALTER TABLE id_user
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '账号管理覆盖更新版本',
    ADD COLUMN force_password_change BOOLEAN NOT NULL DEFAULT FALSE COMMENT '正式临时口令须先改密',
    ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC更新时间';

CREATE TABLE id_directory_guard (
    id INT NOT NULL COMMENT '1组织目录，2最后平台管理员保护',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '结构快照版本',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
INSERT INTO id_directory_guard(id) VALUES(1),(2);

CREATE TABLE id_organization_path (
    ancestor_id BIGINT NOT NULL COMMENT '祖先，含自身',
    descendant_id BIGINT NOT NULL COMMENT '后代，含自身',
    depth INT NOT NULL COMMENT '自身0，最多7条边',
    PRIMARY KEY (ancestor_id,descendant_id),
    CONSTRAINT fk_path_ancestor FOREIGN KEY (ancestor_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
    CONSTRAINT fk_path_descendant FOREIGN KEY (descendant_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
    CONSTRAINT ck_path_depth CHECK (depth BETWEEN 0 AND 7),
    INDEX ix_path_descendant (descendant_id,ancestor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
INSERT INTO id_organization_path(ancestor_id,descendant_id,depth)
WITH RECURSIVE paths AS (
    SELECT id AS ancestor_id,id AS descendant_id,0 AS depth FROM id_organization
    UNION ALL
    SELECT p.ancestor_id,o.id,p.depth+1 FROM paths p JOIN id_organization o ON o.parent_id=p.descendant_id
) SELECT ancestor_id,descendant_id,depth FROM paths;

CREATE TABLE id_move_preview (
    token CHAR(36) NOT NULL COMMENT '服务端随机预览引用',
    actor_user_id BIGINT NOT NULL COMMENT '绑定实际操作者',
    organization_id BIGINT NOT NULL COMMENT '移动目标',
    new_parent_id BIGINT NOT NULL COMMENT '已校验父节点',
    target_version BIGINT NOT NULL COMMENT '目标版本',
    tree_version BIGINT NOT NULL COMMENT '整树依赖版本',
    expires_at DATETIME(3) NOT NULL COMMENT '15分钟有效',
    consumed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '只允许提交一次',
    PRIMARY KEY (token),
    CONSTRAINT fk_preview_actor FOREIGN KEY (actor_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_preview_org FOREIGN KEY (organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
    CONSTRAINT fk_preview_parent FOREIGN KEY (new_parent_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
    INDEX ix_preview_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '追加审计标识',
    actor_user_id BIGINT NOT NULL COMMENT '真实操作者',
    action VARCHAR(80) NOT NULL COMMENT '明确管理动作',
    target_type VARCHAR(32) NOT NULL COMMENT '组织/人员/账号',
    target_id BIGINT NOT NULL COMMENT '目标标识',
    before_json JSON NULL COMMENT '脱敏前状态，仅ID/字段/版本',
    after_json JSON NULL COMMENT '脱敏后状态，禁止口令/电话/姓名',
    trace_id VARCHAR(64) NOT NULL COMMENT '请求追踪，不是会话标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC记录时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
    INDEX ix_audit_actor (actor_user_id,created_at,id),
    INDEX ix_audit_target (target_type,target_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- 新权限仅授予现有工程引导角色；演示平台入口角色不会因此获得管理能力。
INSERT INTO auth_permission(application_id,code,name) VALUES
 (1,'admin:platform:manage','平台管理资格'),
 (1,'admin:organization:view','查询组织'),(1,'admin:organization:create','创建组织'),
 (1,'admin:organization:update','编辑组织资料'),(1,'admin:organization:move','移动组织'),(1,'admin:organization:status','启停组织'),
 (1,'admin:employee:view','查询人员'),(1,'admin:employee:create','创建人员'),
 (1,'admin:employee:update','编辑人员资料'),(1,'admin:employee:status','人员启停'),
 (1,'admin:account:view','查询账号'),(1,'admin:account:create','显式建号'),
 (1,'admin:account:status','账号启停'),(1,'admin:account:reset-password','重置临时密码'),
 (1,'admin:audit:view','查询管理审计');
INSERT INTO auth_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM auth_role r JOIN auth_permission p ON p.application_id=1
 WHERE r.code='bootstrap_admin' AND p.code<>'admin:console:view';
