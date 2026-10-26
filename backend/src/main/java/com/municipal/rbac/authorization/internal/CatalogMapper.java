/** 功能：V4目录参数化持久化及引用影响统计；树闭包供运行时权限解析使用，不生成业务接口。 */
package com.municipal.rbac.authorization.internal;

import com.municipal.rbac.authorization.CatalogData.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CatalogMapper {
    @Select("SELECT id,code,name,description,enabled,version FROM auth_application ORDER BY id")
    List<Application> applications();

    @Select("SELECT id,code,name,description,enabled,version FROM auth_application WHERE id=#{id}")
    Application application(long id);

    @Insert(
            "INSERT INTO auth_application(code,name,description) VALUES(#{code},#{name},#{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createApplication(Map<String, Object> v);

    @Insert("INSERT INTO auth_namespace_guard(application_id) VALUES(#{id})")
    int namespace(long id);

    @Update(
            "UPDATE auth_application SET name=#{name},description=#{description},version=version+1 WHERE id=#{id}")
    int updateApplication(
            @Param("id") long id,
            @Param("name") String name,
            @Param("description") String description);

    @Update("UPDATE auth_application SET enabled=#{enabled},version=version+1 WHERE id=#{id}")
    int applicationStatus(@Param("id") long id, @Param("enabled") boolean enabled);

    @Select(
            "SELECT id,application_id,parent_id,code,name,kind,description,enabled,version FROM auth_resource WHERE application_id=#{app} ORDER BY id")
    List<Resource> resources(long app);

    @Select(
            "SELECT COUNT(*) FROM auth_resource_path p JOIN auth_resource r ON r.id=p.ancestor_id WHERE p.application_id=#{app} AND p.descendant_id=#{id} AND r.enabled=FALSE")
    int disabledAncestors(@Param("app") long app, @Param("id") long id);

    @Insert(
            "INSERT INTO auth_resource(application_id,parent_id,code,name,kind,description) VALUES(#{app},#{parent,jdbcType=BIGINT},#{code},#{name},#{kind},#{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createResource(Map<String, Object> v);

    @Update(
            "UPDATE auth_resource SET parent_id=#{parent,jdbcType=BIGINT},name=#{name},description=#{description},version=version+1 WHERE id=#{id}")
    int updateResource(
            @Param("id") long id,
            @Param("parent") Long parent,
            @Param("name") String name,
            @Param("description") String description);

    @Update("UPDATE auth_resource SET enabled=#{enabled},version=version+1 WHERE id=#{id}")
    int resourceStatus(@Param("id") long id, @Param("enabled") boolean enabled);

    @Delete("DELETE FROM auth_resource_path WHERE application_id=#{app}")
    int clearPaths(long app);

    @Insert(
            "<script>INSERT INTO auth_resource_path(application_id,ancestor_id,descendant_id,depth) VALUES <foreach collection='rows' item='r' separator=','>(#{r.app},#{r.ancestor},#{r.descendant},#{r.depth})</foreach></script>")
    int paths(@Param("rows") List<Map<String, Object>> rows);

    @Select(
            "SELECT p.id,p.application_id,p.resource_id,p.code,COALESCE(p.action,'') action,p.name,p.description,p.enabled,(p.enabled AND a.enabled AND "
                    + GrantMapper.ACTIVE_RESOURCE
                    + ") effective_enabled,p.implementation_status,p.version FROM auth_permission p JOIN auth_application a ON a.id=p.application_id WHERE p.application_id=#{app} ORDER BY p.id")
    List<Permission> permissions(long app);

    @Insert(
            "INSERT INTO auth_permission(application_id,resource_id,code,action,name,description,implementation_status) VALUES(#{app},#{resource},#{code},#{action},#{name},#{description},#{implementation})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createPermission(Map<String, Object> v);

    @Update(
            "UPDATE auth_permission SET name=#{name},description=#{description},version=version+1 WHERE id=#{id}")
    int updatePermission(
            @Param("id") long id,
            @Param("name") String name,
            @Param("description") String description);

    @Update("UPDATE auth_permission SET enabled=#{enabled},version=version+1 WHERE id=#{id}")
    int permissionStatus(@Param("id") long id, @Param("enabled") boolean enabled);

    record Impact(long roles, long users) {}

    @Select(
            "<script>SELECT COUNT(DISTINCT rp.role_id) roles,COUNT(DISTINCT ur.user_id) users FROM auth_permission p JOIN auth_role_permission rp ON rp.permission_id=p.id LEFT JOIN auth_user_role ur ON ur.role_id=rp.role_id WHERE p.application_id=#{app}<choose><when test=\"kind == 'PERMISSION'\"> AND p.id=#{target}</when><when test=\"kind == 'RESOURCE'\"> AND p.resource_id IN(SELECT descendant_id FROM auth_resource_path WHERE application_id=#{app} AND ancestor_id=#{target})</when></choose></script>")
    Impact impact(@Param("app") long app, @Param("kind") String kind, @Param("target") long target);
}
