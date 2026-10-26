/** 功能：身份持久化唯一适配，参数绑定SQL；闭包与版本操作由IdentityDirectory外层事务组合。 */
package com.municipal.rbac.identity.internal;

import com.municipal.rbac.identity.IdentityData.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface IdentityMapper {
    String ORG = "id,code,name,node_type,parent_id,manager_employee_id,enabled,version";
    String EMP =
            "id,employee_code,display_name,organization_id,position_name,phone,enabled,version";
    String USR = "id,username,employee_id,enabled,force_password_change,version";

    @Select("SELECT version FROM id_directory_guard WHERE id=#{id} FOR UPDATE")
    long lockGuard(int id);

    @Update("UPDATE id_directory_guard SET version=version+1 WHERE id=1")
    int bumpTree();

    @Select("SELECT " + ORG + " FROM id_organization ORDER BY id")
    List<Organization> organizations();

    @Select("SELECT " + ORG + " FROM id_organization WHERE id=#{id}")
    Organization organization(long id);

    @Select("SELECT " + EMP + " FROM id_employee WHERE id=#{id} FOR UPDATE")
    Employee lockEmployee(long id);

    @Select("SELECT " + EMP + " FROM id_employee WHERE id=#{id}")
    Employee employee(long id);

    @Select("SELECT " + USR + " FROM id_user WHERE id=#{id}")
    UserAccount account(long id);

    @Select("SELECT id,enabled,auth_version,version FROM id_user WHERE id=#{id} FOR UPDATE")
    AccountState lockAccount(long id);

    @Select("SELECT id FROM id_user WHERE employee_id=#{id}")
    Long employeeAccount(long id);

    @Select("SELECT COUNT(*) FROM id_organization WHERE parent_id IS NULL")
    int roots();

    @Select(
            "SELECT COUNT(*) FROM id_organization WHERE parent_key=COALESCE(#{parentId,jdbcType=BIGINT},0) AND name_key=#{key} AND id<>#{exclude}")
    int siblings(
            @Param("parentId") Long parentId,
            @Param("key") String key,
            @Param("exclude") long exclude);

    @Select(
            "SELECT COUNT(*) FROM id_organization_path p JOIN id_organization o ON o.id=p.ancestor_id WHERE p.descendant_id=#{id} AND o.enabled=FALSE")
    int disabledAncestors(long id);

    @Select("SELECT COALESCE(MAX(depth),0) FROM id_organization_path WHERE descendant_id=#{id}")
    int depth(long id);

    @Select("SELECT COALESCE(MAX(depth),0) FROM id_organization_path WHERE ancestor_id=#{id}")
    int height(long id);

    @Select(
            "SELECT COUNT(*) FROM id_organization_path WHERE ancestor_id=#{ancestor} AND descendant_id=#{descendant}")
    int path(@Param("ancestor") long ancestor, @Param("descendant") long descendant);

    @Select("SELECT COUNT(*) FROM id_organization WHERE parent_id=#{id} AND enabled=TRUE")
    int enabledChildren(long id);

    @Select("SELECT COUNT(*) FROM id_employee WHERE organization_id=#{id} AND enabled=TRUE")
    int activeEmployees(long id);

    @Select("SELECT COUNT(*) FROM id_organization WHERE manager_employee_id=#{id}")
    int managedOrganizations(long id);

    @Select(
            "SELECT COUNT(*) FROM id_employee e JOIN id_organization_path p ON p.descendant_id=e.organization_id WHERE p.ancestor_id=#{id}")
    long affectedEmployees(long id);

    @Select(
            "SELECT COUNT(*) FROM id_employee e JOIN id_organization_path p ON p.descendant_id=e.organization_id JOIN id_user u ON u.employee_id=e.id JOIN auth_user_role ur ON ur.user_id=u.id WHERE p.ancestor_id=#{id}")
    long affectedGrants(long id);

    @Select(
            "SELECT (SELECT COUNT(*) FROM auth_role_organization)+(SELECT COUNT(*) FROM auth_role_permission_organization)+(SELECT COUNT(*) FROM auth_delegation_organization)+(SELECT COUNT(*) FROM auth_delegation_permission_organization)")
    long authorizationScopeReferences();

    @Insert(
            "INSERT INTO id_organization(code,name,name_key,node_type,parent_id) VALUES(#{code},#{name},#{nameKey},#{nodeType},#{parentId,jdbcType=BIGINT})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrganization(Map<String, Object> values);

    @Insert(
            "INSERT INTO id_organization_path(ancestor_id,descendant_id,depth) SELECT ancestor_id,#{id},depth+1 FROM id_organization_path WHERE descendant_id=#{parentId}")
    int insertAncestors(@Param("id") long id, @Param("parentId") long parentId);

    @Insert(
            "INSERT INTO id_organization_path(ancestor_id,descendant_id,depth) VALUES(#{id},#{id},0)")
    int insertSelf(long id);

    @Update(
            "UPDATE id_organization SET name=#{name},name_key=#{nameKey},manager_employee_id=#{managerId,jdbcType=BIGINT},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int updateOrganization(Map<String, Object> values);

    @Update(
            "UPDATE id_organization SET enabled=#{enabled},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int organizationStatus(
            @Param("id") long id,
            @Param("enabled") boolean enabled,
            @Param("version") long version);

    @Update(
            "UPDATE id_organization SET parent_id=#{parentId},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int move(
            @Param("id") long id, @Param("parentId") long parentId, @Param("version") long version);

    @Delete("DELETE FROM id_organization_path")
    int clearPaths();

    @Insert(
            "INSERT INTO id_organization_path(ancestor_id,descendant_id,depth) WITH RECURSIVE paths AS (SELECT id AS ancestor_id,id AS descendant_id,0 AS depth FROM id_organization UNION ALL SELECT p.ancestor_id,o.id,p.depth+1 FROM paths p JOIN id_organization o ON o.parent_id=p.descendant_id) SELECT ancestor_id,descendant_id,depth FROM paths")
    int rebuildPaths();

    @Insert(
            "INSERT INTO id_move_preview(token,actor_user_id,organization_id,new_parent_id,target_version,tree_version,expires_at) VALUES(#{token},#{actorUserId},#{organizationId},#{newParentId},#{targetVersion},#{treeVersion},#{expiresAt})")
    int preview(PreviewState preview);

    @Select(
            "SELECT token,actor_user_id,organization_id,new_parent_id,target_version,tree_version,expires_at,consumed FROM id_move_preview WHERE token=#{token} FOR UPDATE")
    PreviewState lockPreview(String token);

    @Update("UPDATE id_move_preview SET consumed=TRUE WHERE token=#{token} AND consumed=FALSE")
    int consumePreview(String token);

    String EMP_FILTER =
            " WHERE (#{enabled,jdbcType=BOOLEAN} IS NULL OR e.enabled=#{enabled,jdbcType=BOOLEAN}) AND (#{q}='' OR e.display_name LIKE CONCAT('%',#{q},'%') ESCAPE '!' OR e.employee_code LIKE CONCAT('%',#{q},'%') ESCAPE '!') AND (#{organizationId,jdbcType=BIGINT} IS NULL OR EXISTS(SELECT 1 FROM id_organization_path p WHERE p.ancestor_id=#{organizationId,jdbcType=BIGINT} AND p.descendant_id=e.organization_id AND (#{includeDescendants}=TRUE OR p.depth=0)))";

    @Select(
            "SELECT "
                    + EMP
                    + " FROM id_employee e"
                    + EMP_FILTER
                    + " ORDER BY CASE WHEN #{sort}='name' THEN display_name END,id LIMIT #{size} OFFSET #{offset}")
    List<Employee> employees(Map<String, Object> values);

    @Select("SELECT COUNT(*) FROM id_employee e" + EMP_FILTER)
    long employeeCount(Map<String, Object> values);

    @Insert(
            "INSERT INTO id_employee(employee_code,display_name,organization_id,position_name,phone) VALUES(#{employeeCode},#{displayName},#{organizationId},#{positionName,jdbcType=VARCHAR},#{phone,jdbcType=VARCHAR})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEmployee(Map<String, Object> values);

    @Update(
            "UPDATE id_employee SET display_name=#{displayName},phone=#{phone,jdbcType=VARCHAR},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int updateEmployee(Map<String, Object> values);

    @Update(
            "UPDATE id_employee SET enabled=#{enabled},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int employeeStatus(
            @Param("id") long id,
            @Param("enabled") boolean enabled,
            @Param("version") long version);

    String USER_FILTER =
            " WHERE (#{enabled,jdbcType=BOOLEAN} IS NULL OR enabled=#{enabled,jdbcType=BOOLEAN}) AND (#{q}='' OR username LIKE CONCAT('%',#{q},'%') ESCAPE '!')";

    @Select(
            "SELECT "
                    + USR
                    + " FROM id_user"
                    + USER_FILTER
                    + " ORDER BY CASE WHEN #{sort}='name' THEN username END,id LIMIT #{size} OFFSET #{offset}")
    List<UserAccount> accounts(Map<String, Object> values);

    @Select("SELECT COUNT(*) FROM id_user" + USER_FILTER)
    long accountCount(Map<String, Object> values);

    @Insert(
            "INSERT INTO id_user(username,password_hash,employee_id,force_password_change) VALUES(#{username},#{hash},#{employeeId},#{force})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAccount(Map<String, Object> values);

    @Update(
            "UPDATE id_user SET enabled=#{enabled},auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int accountStatus(
            @Param("id") long id,
            @Param("enabled") boolean enabled,
            @Param("version") long version);

    @Update(
            "UPDATE id_user SET password_hash=#{hash},force_password_change=#{force},auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int password(
            @Param("id") long id,
            @Param("hash") String hash,
            @Param("force") boolean force,
            @Param("version") long version);

    @Delete("DELETE FROM auth_user_role WHERE user_id=#{id}")
    int revokeOwnRoles(long id);

    @Select(
            "SELECT role_id,valid_from,valid_to FROM auth_user_role WHERE user_id=#{id} ORDER BY role_id FOR UPDATE")
    List<Membership> memberships(long id);

    @Select(
            "SELECT DISTINCT u.id FROM id_user u JOIN auth_user_role ur ON ur.user_id=u.id JOIN auth_role r ON r.id=ur.role_id JOIN auth_role_permission rp ON rp.role_id=r.id JOIN auth_permission p ON p.id=rp.permission_id JOIN auth_application a ON a.id=p.application_id WHERE u.enabled=TRUE AND r.enabled=TRUE AND p.enabled=TRUE AND a.enabled=TRUE AND (r.kind='LEGACY' OR (r.kind='PLATFORM' AND r.application_id=p.application_id)) AND p.code='admin:platform:manage' AND (ur.valid_from IS NULL OR ur.valid_from<=#{now}) AND (ur.valid_to IS NULL OR ur.valid_to>#{now}) AND (rp.valid_from IS NULL OR rp.valid_from<=#{now}) AND (rp.valid_to IS NULL OR rp.valid_to>#{now})")
    List<Long> platformAdmins(Instant now);

    @Update(
            "UPDATE id_employee SET organization_id=#{organization},position_name=#{position,jdbcType=VARCHAR},version=version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}")
    int assignment(
            @Param("id") long id,
            @Param("organization") long organization,
            @Param("position") String position,
            @Param("version") long version);

    @Delete(
            "DELETE ur FROM auth_user_role ur JOIN auth_role r ON r.id=ur.role_id WHERE ur.user_id=#{user} AND r.kind='LEGACY'")
    int revokeLegacyMemberships(long user);
}
