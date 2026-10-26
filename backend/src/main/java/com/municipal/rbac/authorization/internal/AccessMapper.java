/** 功能：V3授权持久化；全部用户值参数化，列表在SQL内完成责任/组织范围过滤后分页。 */
package com.municipal.rbac.authorization.internal;

import static com.municipal.rbac.authorization.AccessData.*;
import static com.municipal.rbac.authorization.internal.AccessRows.*;

import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AccessMapper {
    @Select(
            "SELECT id,code,name,node_type,parent_id,manager_employee_id,enabled,version FROM id_organization ORDER BY id")
    List<com.municipal.rbac.identity.IdentityData.Organization> organizationDirectory();

    @Select("SELECT id,code,name,enabled,version FROM auth_application ORDER BY id")
    List<App> apps();

    @Select("SELECT version FROM id_directory_guard WHERE id=1 FOR SHARE")
    long lockTree();

    @Select("SELECT version FROM auth_namespace_guard WHERE application_id=#{app} FOR UPDATE")
    long lockNamespace(long app);

    @Select("SELECT version FROM auth_namespace_guard WHERE application_id=1 FOR SHARE")
    long lockPlatform();

    @Select("SELECT version FROM auth_namespace_guard WHERE application_id=#{app}")
    long namespace(long app);

    @Update("UPDATE auth_namespace_guard SET version=version+1 WHERE application_id=#{app}")
    int bumpNamespace(long app);

    @Select("SELECT version FROM id_directory_guard WHERE id=2 FOR UPDATE")
    long lockLastAdmin();

    @Select("SELECT id FROM id_user WHERE id=#{id} FOR UPDATE")
    Long lockUser(long id);

    String USER =
            "SELECT u.id,u.username,u.employee_id,e.organization_id,u.enabled,COALESCE(e.enabled,TRUE) employee_enabled,(e.id IS NULL OR NOT EXISTS(SELECT 1 FROM id_organization_path z JOIN id_organization o ON o.id=z.ancestor_id WHERE z.descendant_id=e.organization_id AND o.enabled=FALSE)) organization_enabled,u.auth_version,u.version FROM id_user u LEFT JOIN id_employee e ON e.id=u.employee_id";

    @Select(USER + " WHERE u.id=#{id}")
    User user(long id);

    @Select(
            "SELECT COUNT(*) FROM id_organization_path WHERE ancestor_id=#{root} AND descendant_id=#{node}")
    int ancestor(@Param("root") long root, @Param("node") long node);

    @Select(
            "SELECT COUNT(*) FROM id_organization o WHERE o.id=#{id} AND o.enabled=TRUE AND NOT EXISTS(SELECT 1 FROM id_organization_path p JOIN id_organization a ON a.id=p.ancestor_id WHERE p.descendant_id=o.id AND a.enabled=FALSE)")
    int activeOrg(long id);

    String ROLE =
            "SELECT r.id,r.application_id,r.code,r.name,r.kind,r.owner_user_id,r.personnel_all,r.enabled,r.version,(SELECT COUNT(*) FROM auth_user_role ur WHERE ur.role_id=r.id) member_count FROM auth_role r";

    @Select(ROLE + " WHERE r.id=#{id}")
    RoleRow role(long id);

    @Select(
            "SELECT organization_id,include_descendants FROM auth_role_organization WHERE role_id=#{id} ORDER BY organization_id")
    List<Node> roleNodes(long id);

    @Select(
            "SELECT organization_id,include_descendants FROM auth_delegation_organization WHERE delegation_id=#{id} ORDER BY organization_id")
    List<Node> delegationNodes(long id);

    String VISIBLE_ROLE =
            " r.application_id=#{app} AND (#{root}=TRUE OR (r.kind='SCOPED' AND r.owner_user_id=#{actor}) OR (r.kind='COMMON' AND EXISTS(SELECT 1 FROM auth_delegation_role dr WHERE dr.delegation_id=#{delegation} AND dr.role_id=r.id)))";

    @Select(
            ROLE
                    + " WHERE "
                    + VISIBLE_ROLE
                    + " AND (#{q}='' OR r.name LIKE CONCAT('%',#{q},'%') ESCAPE '!' OR r.code LIKE CONCAT('%',#{q},'%') ESCAPE '!') ORDER BY r.id LIMIT #{size} OFFSET #{offset}")
    List<RoleRow> roles(Map<String, Object> v);

    @Select(
            "SELECT COUNT(*) FROM auth_role r WHERE "
                    + VISIBLE_ROLE
                    + " AND (#{q}='' OR r.name LIKE CONCAT('%',#{q},'%') ESCAPE '!' OR r.code LIKE CONCAT('%',#{q},'%') ESCAPE '!')")
    long roleCount(Map<String, Object> v);

    @Insert(
            "INSERT INTO auth_role(code,name,name_key,application_id,kind,owner_user_id,personnel_all) VALUES(#{code},#{name},#{nameKey},#{app},#{kind},#{owner,jdbcType=BIGINT},#{all})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRole(Map<String, Object> v);

    @Update(
            "UPDATE auth_role SET name=#{name},name_key=#{nameKey},personnel_all=#{all},version=version+1 WHERE id=#{id}")
    int updateRole(Map<String, Object> v);

    @Update("UPDATE auth_role SET enabled=#{enabled},version=version+1 WHERE id=#{id}")
    int roleStatus(@Param("id") long id, @Param("enabled") boolean enabled);

    @Update("UPDATE auth_role SET owner_user_id=#{owner},version=version+1 WHERE id=#{id}")
    int roleOwner(@Param("id") long id, @Param("owner") long owner);

    @Update("UPDATE auth_role SET version=version+1 WHERE id=#{id}")
    int bumpRole(long id);

    @Delete("DELETE FROM auth_role_organization WHERE role_id=#{id}")
    int clearRoleNodes(long id);

    @Insert(
            "INSERT INTO auth_role_organization(role_id,organization_id,include_descendants) VALUES(#{id},#{node},#{subtree})")
    int roleNode(@Param("id") long id, @Param("node") long node, @Param("subtree") boolean subtree);

    @Select(
            "SELECT p.id,p.application_id,p.code,p.name,(p.enabled AND "
                    + GrantMapper.ACTIVE_RESOURCE
                    + ") enabled,p.implementation_status FROM auth_permission p WHERE p.application_id=#{app} ORDER BY p.id")
    List<Permission> permissions(long app);

    @Select(
            "SELECT permission_id,scope_kind,valid_from,valid_to FROM auth_role_permission WHERE role_id=#{id} ORDER BY permission_id")
    List<PermissionRow> rolePermissions(long id);

    @Select(
            "SELECT organization_id FROM auth_role_permission_organization WHERE role_id=#{role} AND permission_id=#{permission} ORDER BY organization_id")
    List<String> permissionNodes(@Param("role") long role, @Param("permission") long permission);

    @Delete("DELETE FROM auth_role_permission_organization WHERE role_id=#{id}")
    int clearPermissionNodes(long id);

    @Delete("DELETE FROM auth_role_permission WHERE role_id=#{id}")
    int clearPermissions(long id);

    @Insert(
            "INSERT INTO auth_role_permission(role_id,permission_id,scope_kind,valid_from,valid_to) VALUES(#{role},#{permission},#{kind},#{from,jdbcType=TIMESTAMP},#{to,jdbcType=TIMESTAMP})")
    int rolePermission(
            @Param("role") long role,
            @Param("permission") long permission,
            @Param("kind") String kind,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Insert(
            "INSERT INTO auth_role_permission_organization(role_id,permission_id,organization_id) VALUES(#{role},#{permission},#{node})")
    int permissionNode(
            @Param("role") long role,
            @Param("permission") long permission,
            @Param("node") long node);

    @Select(
            "SELECT r.id role_id,r.name role_name,r.kind,r.enabled,ur.valid_from,ur.valid_to,ur.granted_by granted_by_user_id,ur.granted_at FROM auth_user_role ur JOIN auth_role r ON r.id=ur.role_id WHERE ur.user_id=#{user} AND r.application_id=#{app} ORDER BY r.id")
    List<Membership> memberships(@Param("app") long app, @Param("user") long user);

    @Select("SELECT user_id FROM auth_user_role WHERE role_id=#{role} ORDER BY user_id")
    List<Long> memberIds(long role);

    @Select(
            "SELECT COALESCE((SELECT version FROM auth_person_version WHERE application_id=#{app} AND user_id=#{user}),0)")
    long personVersion(@Param("app") long app, @Param("user") long user);

    @Insert(
            "INSERT INTO auth_person_version(application_id,user_id,version) VALUES(#{app},#{user},1) ON DUPLICATE KEY UPDATE version=version+1")
    int bumpPerson(@Param("app") long app, @Param("user") long user);

    @Delete("DELETE FROM auth_user_role WHERE user_id=#{user} AND role_id=#{role}")
    int removeMember(@Param("user") long user, @Param("role") long role);

    @Update(
            "UPDATE auth_user_role SET valid_from=#{from,jdbcType=TIMESTAMP},valid_to=#{to,jdbcType=TIMESTAMP} WHERE user_id=#{user} AND role_id=#{role}")
    int narrowMember(
            @Param("user") long user,
            @Param("role") long role,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Insert(
            "INSERT INTO auth_user_role(user_id,role_id,valid_from,valid_to,granted_by,granted_at) VALUES(#{user},#{role},#{from,jdbcType=TIMESTAMP},#{to,jdbcType=TIMESTAMP},#{actor},#{now}) ON DUPLICATE KEY UPDATE valid_from=#{from,jdbcType=TIMESTAMP},valid_to=#{to,jdbcType=TIMESTAMP},granted_by=#{actor},granted_at=#{now}")
    int member(
            @Param("user") long user,
            @Param("role") long role,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("actor") long actor,
            @Param("now") Instant now);

    @Select(
            "SELECT application_id,user_id,delegation_id,state,version FROM auth_person_responsibility WHERE application_id=#{app} AND user_id=#{user}")
    Responsibility responsibility(@Param("app") long app, @Param("user") long user);

    @Insert(
            "INSERT INTO auth_person_responsibility(application_id,user_id,delegation_id,state) VALUES(#{app},#{user},#{delegation,jdbcType=BIGINT},#{state}) ON DUPLICATE KEY UPDATE delegation_id=#{delegation,jdbcType=BIGINT},state=#{state},version=version+1")
    int setResponsibility(
            @Param("app") long app,
            @Param("user") long user,
            @Param("delegation") Long delegation,
            @Param("state") String state);

    @Select(
            "SELECT user_id FROM auth_person_responsibility WHERE delegation_id=#{id} AND state='CURRENT' ORDER BY user_id")
    List<Long> responsibleUsers(long id);

    String DELEGATE =
            "SELECT id,application_id,administrator_user_id,enabled,valid_from,valid_to,people_all,version FROM auth_delegation";

    @Select(DELEGATE + " WHERE id=#{id}")
    DelegateRow delegation(long id);

    @Select(DELEGATE + " WHERE application_id=#{app} AND administrator_user_id=#{user}")
    DelegateRow ownDelegation(@Param("app") long app, @Param("user") long user);

    @Select(
            DELEGATE
                    + " WHERE application_id=#{app} AND (#{q}='' OR administrator_user_id IN(SELECT id FROM id_user WHERE username LIKE CONCAT('%',#{q},'%') ESCAPE '!')) ORDER BY id LIMIT #{size} OFFSET #{offset}")
    List<DelegateRow> delegations(Map<String, Object> v);

    @Select(
            "SELECT COUNT(*) FROM auth_delegation WHERE application_id=#{app} AND (#{q}='' OR administrator_user_id IN(SELECT id FROM id_user WHERE username LIKE CONCAT('%',#{q},'%') ESCAPE '!'))")
    long delegationCount(Map<String, Object> v);

    @Select("SELECT role_id FROM auth_delegation_role WHERE delegation_id=#{id} ORDER BY role_id")
    List<String> commonRoles(long id);

    @Select(
            "SELECT permission_id,scope_kind,NULL valid_from,NULL valid_to FROM auth_delegation_permission WHERE delegation_id=#{id} ORDER BY permission_id")
    List<PermissionRow> ceilings(long id);

    @Select(
            "SELECT organization_id FROM auth_delegation_permission_organization WHERE delegation_id=#{delegation} AND permission_id=#{permission} ORDER BY organization_id")
    List<String> ceilingNodes(
            @Param("delegation") long delegation, @Param("permission") long permission);

    @Insert(
            "INSERT INTO auth_delegation(application_id,administrator_user_id,enabled,valid_from,valid_to,people_all) VALUES(#{app},#{user},#{enabled},#{from,jdbcType=TIMESTAMP},#{to,jdbcType=TIMESTAMP},#{all})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDelegation(Map<String, Object> v);

    @Update(
            "UPDATE auth_delegation SET enabled=#{enabled},valid_from=#{from,jdbcType=TIMESTAMP},valid_to=#{to,jdbcType=TIMESTAMP},people_all=#{all},version=version+1 WHERE id=#{id}")
    int updateDelegation(Map<String, Object> v);

    @Update(
            "UPDATE auth_delegation SET enabled=FALSE,version=version+1 WHERE administrator_user_id=#{user}")
    int retireDelegations(long user);

    @Delete("DELETE FROM auth_delegation_permission_organization WHERE delegation_id=#{id}")
    int clearCeilingNodes(long id);

    @Delete("DELETE FROM auth_delegation_permission WHERE delegation_id=#{id}")
    int clearCeilings(long id);

    @Delete("DELETE FROM auth_delegation_organization WHERE delegation_id=#{id}")
    int clearDelegationNodes(long id);

    @Delete("DELETE FROM auth_delegation_role WHERE delegation_id=#{id}")
    int clearCommonRoles(long id);

    @Delete("DELETE FROM auth_delegation_action WHERE delegation_id=#{id}")
    int clearActions(long id);

    @Insert(
            "INSERT INTO auth_delegation_organization(delegation_id,organization_id,include_descendants) VALUES(#{id},#{node},#{subtree})")
    int delegationNode(
            @Param("id") long id, @Param("node") long node, @Param("subtree") boolean subtree);

    @Insert("INSERT INTO auth_delegation_role(delegation_id,role_id) VALUES(#{id},#{role})")
    int commonRole(@Param("id") long id, @Param("role") long role);

    @Insert(
            "INSERT INTO auth_delegation_permission(delegation_id,permission_id,scope_kind) VALUES(#{id},#{permission},#{kind})")
    int ceiling(
            @Param("id") long id, @Param("permission") long permission, @Param("kind") String kind);

    @Insert(
            "INSERT INTO auth_delegation_permission_organization(delegation_id,permission_id,organization_id) VALUES(#{id},#{permission},#{node})")
    int ceilingNode(
            @Param("id") long id, @Param("permission") long permission, @Param("node") long node);

    @Insert(
            "INSERT INTO auth_delegation_action(delegation_id,permission_id) SELECT #{id},id FROM auth_permission WHERE code IN('admin:application:view','admin:role:view','admin:role:create','admin:role:update','admin:role:status','admin:role:permissions','admin:role:members','admin:grant:view','admin:grant:update')")
    int actions(long id);

    @Select(
            "SELECT p.code FROM auth_delegation_action da JOIN auth_permission p ON p.id=da.permission_id AND p.enabled=TRUE WHERE da.delegation_id=#{id} ORDER BY p.id")
    List<String> actionsFor(long id);

    @Select(
            "SELECT COUNT(*) FROM auth_delegation d WHERE d.administrator_user_id=#{user} AND d.enabled=TRUE AND (d.valid_to IS NULL OR d.valid_to>#{now})")
    int potentialDelegate(@Param("user") long user, @Param("now") Instant now);

    @Select(
            "SELECT COUNT(*) FROM auth_user_role ur JOIN auth_role r ON r.id=ur.role_id JOIN auth_role_permission rp ON rp.role_id=r.id JOIN auth_permission p ON p.id=rp.permission_id WHERE ur.user_id=#{user} AND (r.kind='LEGACY' OR (r.kind='PLATFORM' AND r.application_id=p.application_id)) AND p.code='admin:platform:manage' AND (ur.valid_to IS NULL OR ur.valid_to>#{now}) AND (rp.valid_to IS NULL OR rp.valid_to>#{now})")
    int potentialPlatform(@Param("user") long user, @Param("now") Instant now);

    @Select(
            "SELECT COUNT(*) FROM auth_user_role ur JOIN auth_role r ON r.id=ur.role_id WHERE ur.user_id=#{user} AND r.kind='SCOPED' AND (ur.valid_to IS NULL OR ur.valid_to>#{now})")
    int scopedMemberships(@Param("user") long user, @Param("now") Instant now);

    @Select(
            "SELECT COUNT(DISTINCT u.id) FROM id_user u JOIN auth_user_role ur ON ur.user_id=u.id JOIN auth_role r ON r.id=ur.role_id JOIN auth_role_permission rp ON rp.role_id=r.id JOIN auth_permission p ON p.id=rp.permission_id JOIN auth_application a ON a.id=p.application_id LEFT JOIN id_employee e ON e.id=u.employee_id WHERE u.enabled=TRUE AND r.enabled=TRUE AND p.enabled=TRUE AND a.enabled=TRUE AND (e.id IS NULL OR (e.enabled=TRUE AND NOT EXISTS(SELECT 1 FROM id_organization_path z JOIN id_organization o ON o.id=z.ancestor_id WHERE z.descendant_id=e.organization_id AND o.enabled=FALSE))) AND (r.kind='LEGACY' OR (r.kind='PLATFORM' AND r.application_id=p.application_id)) AND p.code='admin:platform:manage' AND (ur.valid_from IS NULL OR ur.valid_from<=#{now}) AND (ur.valid_to IS NULL OR ur.valid_to>#{now}) AND (rp.valid_from IS NULL OR rp.valid_from<=#{now}) AND (rp.valid_to IS NULL OR rp.valid_to>#{now})")
    int platformCount(Instant now);

    String PERSON_SCOPE =
            " (#{root}=TRUE OR (pr.state='CURRENT' AND pr.delegation_id=#{delegation} AND (#{all}=TRUE OR EXISTS(SELECT 1 FROM auth_delegation_organization dn JOIN id_organization_path z ON z.ancestor_id=dn.organization_id AND z.descendant_id=e.organization_id AND (dn.include_descendants=TRUE OR z.depth=0) WHERE dn.delegation_id=#{delegation}))))";
    String PERSON_FROM =
            " FROM id_user u LEFT JOIN id_employee e ON e.id=u.employee_id LEFT JOIN auth_person_version pv ON pv.user_id=u.id AND pv.application_id=#{app} LEFT JOIN auth_person_responsibility pr ON pr.user_id=u.id AND pr.application_id=#{app}";
    String PERSON_SEARCH =
            " AND (#{q}='' OR u.username LIKE CONCAT('%',#{q},'%') ESCAPE '!' OR e.display_name LIKE CONCAT('%',#{q},'%') ESCAPE '!')";

    @Select(
            "SELECT u.id user_id,u.username,u.employee_id,COALESCE(e.display_name,u.username) display_name,e.organization_id,u.enabled,COALESCE(pv.version,0) grant_version"
                    + PERSON_FROM
                    + " WHERE "
                    + PERSON_SCOPE
                    + PERSON_SEARCH
                    + " ORDER BY u.id LIMIT #{size} OFFSET #{offset}")
    List<PersonRow> people(Map<String, Object> v);

    @Select("SELECT COUNT(*)" + PERSON_FROM + " WHERE " + PERSON_SCOPE + PERSON_SEARCH)
    long peopleCount(Map<String, Object> v);

    @Select(
            "SELECT u.id user_id,u.username,ur.role_id,ur.valid_from,ur.valid_to,ur.granted_by,ur.granted_at,COALESCE(pv.version,0) grant_version"
                    + PERSON_FROM
                    + " JOIN auth_user_role ur ON ur.user_id=u.id AND ur.role_id=#{role} WHERE "
                    + PERSON_SCOPE
                    + PERSON_SEARCH
                    + " ORDER BY u.id LIMIT #{size} OFFSET #{offset}")
    List<RoleMember> members(Map<String, Object> v);

    @Select(
            "SELECT COUNT(*)"
                    + PERSON_FROM
                    + " JOIN auth_user_role ur ON ur.user_id=u.id AND ur.role_id=#{role} WHERE "
                    + PERSON_SCOPE
                    + PERSON_SEARCH)
    long memberCount(Map<String, Object> v);

    @Insert(
            "INSERT INTO auth_change_preview(token,actor_user_id,kind,target_id,application_id,target_version,tree_version,namespace_json,payload_json,expires_at) VALUES(#{token},#{actorUserId},#{kind},#{targetId},#{applicationId,jdbcType=BIGINT},#{targetVersion},#{treeVersion},#{namespaceJson},#{payloadJson},#{expiresAt})")
    int preview(PreviewRow row);

    @Select(
            "SELECT token,actor_user_id,kind,target_id,application_id,target_version,tree_version,namespace_json,payload_json,expires_at,consumed FROM auth_change_preview WHERE token=#{token} FOR UPDATE")
    PreviewRow previewForUpdate(String token);

    @Update("UPDATE auth_change_preview SET consumed=TRUE WHERE token=#{token} AND consumed=FALSE")
    int consume(String token);

    @Select(
            "SELECT d.application_id FROM auth_delegation d JOIN auth_delegation_action da ON da.delegation_id=d.id JOIN auth_permission p ON p.id=da.permission_id AND p.enabled=TRUE JOIN auth_application a ON a.id=d.application_id AND a.enabled=TRUE JOIN id_user u ON u.id=d.administrator_user_id AND u.enabled=TRUE LEFT JOIN id_employee e ON e.id=u.employee_id WHERE u.id=#{user} AND p.code=#{operation} AND d.enabled=TRUE AND (e.id IS NULL OR (e.enabled=TRUE AND NOT EXISTS(SELECT 1 FROM id_organization_path z JOIN id_organization o ON o.id=z.ancestor_id WHERE z.descendant_id=e.organization_id AND o.enabled=FALSE))) AND (d.valid_from IS NULL OR d.valid_from<=#{now}) AND (d.valid_to IS NULL OR d.valid_to>#{now})")
    Set<Long> managedApplications(
            @Param("user") long user,
            @Param("operation") String operation,
            @Param("now") Instant now);
}
