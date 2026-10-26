-- 功能：RBAC0资源目录与OA/PMIS最小流程；只增加V4，既有授权不自动扩展到新业务动作。
ALTER TABLE auth_application ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '' COMMENT '应用说明，不表示已实现业务';

CREATE TABLE auth_resource (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '资源标识',
 application_id BIGINT NOT NULL COMMENT '所属应用',
 parent_id BIGINT NULL COMMENT '同应用父目录，根为空',
 code VARCHAR(64) NOT NULL COMMENT '应用内不可变且不可复用编码',
 name VARCHAR(100) NOT NULL COMMENT '目录或功能名称',
 kind VARCHAR(16) NOT NULL COMMENT 'DIRECTORY/FUNCTION，最多8层',
 description VARCHAR(500) NOT NULL DEFAULT '' COMMENT '资源说明',
 enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '停用影响全部后代功能权限',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '目录覆盖版本',
 PRIMARY KEY(id),
 CONSTRAINT uk_resource_code UNIQUE(application_id,code),
 CONSTRAINT uk_resource_app_id UNIQUE(application_id,id),
 CONSTRAINT fk_resource_app FOREIGN KEY(application_id) REFERENCES auth_application(id) ON DELETE RESTRICT,
 CONSTRAINT fk_resource_parent FOREIGN KEY(application_id,parent_id) REFERENCES auth_resource(application_id,id) ON DELETE RESTRICT,
 CONSTRAINT ck_resource_kind CHECK(kind IN ('DIRECTORY','FUNCTION')),
 INDEX ix_resource_parent(application_id,parent_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE auth_resource_path (
 application_id BIGINT NOT NULL COMMENT '所属应用',
 ancestor_id BIGINT NOT NULL COMMENT '祖先目录，含自身',
 descendant_id BIGINT NOT NULL COMMENT '后代资源',
 depth INT NOT NULL COMMENT '自身为0，最多7',
 PRIMARY KEY(application_id,ancestor_id,descendant_id),
 CONSTRAINT fk_resource_path_ancestor FOREIGN KEY(application_id,ancestor_id) REFERENCES auth_resource(application_id,id) ON DELETE RESTRICT,
 CONSTRAINT fk_resource_path_descendant FOREIGN KEY(application_id,descendant_id) REFERENCES auth_resource(application_id,id) ON DELETE RESTRICT,
 CONSTRAINT ck_resource_depth CHECK(depth BETWEEN 0 AND 7),
 INDEX ix_resource_path_descendant(application_id,descendant_id,ancestor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- 既有权限全部回填到功能节点；只有新增平台目录管理点授予受保护引导角色。
INSERT INTO auth_permission(application_id,code,name) VALUES(1,'admin:catalog:manage','维护应用资源目录');
INSERT INTO auth_role_permission(role_id,permission_id)
 SELECT 1,id FROM auth_permission WHERE code='admin:catalog:manage';
INSERT INTO auth_resource(application_id,code,name,kind)
 SELECT DISTINCT application_id,SUBSTRING_INDEX(SUBSTRING_INDEX(code,':',2),':',-1),SUBSTRING_INDEX(SUBSTRING_INDEX(code,':',2),':',-1),'FUNCTION' FROM auth_permission;
INSERT INTO auth_resource_path(application_id,ancestor_id,descendant_id,depth) SELECT application_id,id,id,0 FROM auth_resource;
ALTER TABLE auth_permission
 ADD COLUMN resource_id BIGINT NULL COMMENT '功能节点；历史兼容允许为空，管理API强制绑定',
 ADD COLUMN action VARCHAR(20) NULL COMMENT '显式动作，不是通配规则',
 ADD COLUMN description VARCHAR(500) NOT NULL DEFAULT '' COMMENT '权限说明',
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '目录元数据覆盖版本',
 ADD CONSTRAINT fk_permission_resource FOREIGN KEY(application_id,resource_id) REFERENCES auth_resource(application_id,id) ON DELETE RESTRICT;
UPDATE auth_permission p JOIN auth_resource r ON r.application_id=p.application_id AND r.code=SUBSTRING_INDEX(SUBSTRING_INDEX(p.code,':',2),':',-1)
 SET p.resource_id=r.id,p.action=SUBSTRING_INDEX(p.code,':',-1);

CREATE TABLE oa_document (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '公文标识',
 number VARCHAR(64) NOT NULL COMMENT '服务端随机唯一公文编号',
 title VARCHAR(160) NOT NULL COMMENT '公文标题',
 body TEXT NOT NULL COMMENT '纯文本正文，应用限制10000字',
 owner_user_id BIGINT NOT NULL COMMENT '创建本人，不接受客户端代建',
 organization_id BIGINT NOT NULL COMMENT '创建时组织快照，不随调岗移动',
 approver_user_id BIGINT NULL COMMENT '本轮指定审批人',
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/APPROVED/REJECTED/VOIDED',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '状态与覆盖更新版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC最近动作时间',
 PRIMARY KEY(id),
 CONSTRAINT uk_document_number UNIQUE(number),
 CONSTRAINT fk_document_owner FOREIGN KEY(owner_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_document_org FOREIGN KEY(organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
 CONSTRAINT fk_document_approver FOREIGN KEY(approver_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_document_status CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','VOIDED')),
 INDEX ix_document_owner(owner_user_id,status,id),
 INDEX ix_document_org(organization_id,status,id),
 INDEX ix_document_approver(approver_user_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE oa_document_event (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '公文历史标识',
 record_id BIGINT NOT NULL COMMENT '关联公文',
 actor_user_id BIGINT NOT NULL COMMENT '动作主体',
 action VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/SUBMIT/WITHDRAW/APPROVE/REJECT/VOID',
 from_status VARCHAR(16) NULL COMMENT '创建时为空',
 to_status VARCHAR(16) NOT NULL COMMENT '动作后状态',
 comment VARCHAR(500) NOT NULL DEFAULT '' COMMENT '审批意见，业务历史可读，不进入通用审计',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC发生时间',
 PRIMARY KEY(id),
 CONSTRAINT fk_document_event_record FOREIGN KEY(record_id) REFERENCES oa_document(id) ON DELETE RESTRICT,
 CONSTRAINT fk_document_event_actor FOREIGN KEY(actor_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 INDEX ix_document_event_record(record_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE pmis_project (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '立项标识',
 number VARCHAR(64) NOT NULL COMMENT '服务端随机唯一项目编号',
 name VARCHAR(160) NOT NULL COMMENT '项目名称',
 description VARCHAR(2000) NOT NULL COMMENT '立项说明',
 planned_start DATE NOT NULL COMMENT '计划开始日期',
 planned_end DATE NOT NULL COMMENT '计划结束日期',
 budget DECIMAL(16,2) NOT NULL COMMENT '非负预算，API以两位小数字符串传输',
 owner_user_id BIGINT NOT NULL COMMENT '创建负责人，不提供代建或转交',
 organization_id BIGINT NOT NULL COMMENT '创建时组织快照',
 approver_user_id BIGINT NULL COMMENT '本轮指定审批人',
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '状态与覆盖更新版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC最近动作时间',
 PRIMARY KEY(id),
 CONSTRAINT uk_project_number UNIQUE(number),
 CONSTRAINT fk_project_owner FOREIGN KEY(owner_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_project_org FOREIGN KEY(organization_id) REFERENCES id_organization(id) ON DELETE RESTRICT,
 CONSTRAINT fk_project_approver FOREIGN KEY(approver_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_project_status CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
 CONSTRAINT ck_project_dates CHECK(planned_end>=planned_start),
 CONSTRAINT ck_project_budget CHECK(budget>=0),
 INDEX ix_project_owner(owner_user_id,status,id),
 INDEX ix_project_org(organization_id,status,id),
 INDEX ix_project_approver(approver_user_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE pmis_project_event (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '立项历史标识',
 record_id BIGINT NOT NULL COMMENT '关联项目',
 actor_user_id BIGINT NOT NULL COMMENT '动作主体',
 action VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/SUBMIT/WITHDRAW/APPROVE/REJECT/CANCEL',
 from_status VARCHAR(16) NULL COMMENT '创建时为空',
 to_status VARCHAR(16) NOT NULL COMMENT '动作后状态',
 comment VARCHAR(500) NOT NULL DEFAULT '' COMMENT '审批意见',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC发生时间',
 PRIMARY KEY(id),
 CONSTRAINT fk_project_event_record FOREIGN KEY(record_id) REFERENCES pmis_project(id) ON DELETE RESTRICT,
 CONSTRAINT fk_project_event_actor FOREIGN KEY(actor_user_id) REFERENCES id_user(id) ON DELETE RESTRICT,
 INDEX ix_project_event_record(record_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
