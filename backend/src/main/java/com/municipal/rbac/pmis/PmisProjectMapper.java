/** 功能：PMIS立项独立表及历史的参数化SQL；列表/count/详情/写均在数据库过滤业务范围。 */
package com.municipal.rbac.pmis;

import com.municipal.rbac.pmis.PmisProjectData.Record;
import com.municipal.rbac.shared.BusinessData.Event;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PmisProjectMapper {
    String COLUMNS =
            "r.id,r.number,r.name,r.description,r.planned_start,r.planned_end,CAST(r.budget AS CHAR) budget,r.owner_user_id,r.organization_id,r.approver_user_id,r.status,r.version,r.created_at,r.updated_at";
    String SCOPE =
            "(#{scope.all}=TRUE OR (#{scope.self}=TRUE AND r.owner_user_id=#{scope.subjectId}) <if test='scope.organizationIds.size > 0'> OR r.organization_id IN <foreach collection='scope.organizationIds' item='org' open='(' separator=',' close=')'>#{org}</foreach></if>)";
    String FILTER =
            " WHERE "
                    + SCOPE
                    + " AND (#{q}='' OR r.name LIKE CONCAT('%',#{q},'%') ESCAPE '!') <if test='status.length() > 0'> AND r.status=#{status}</if><if test='mine'> AND r.owner_user_id=#{scope.subjectId}</if><if test='pending'> AND r.status='SUBMITTED' AND r.approver_user_id=#{scope.subjectId}</if>";

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + " FROM pmis_project r"
                    + FILTER
                    + " ORDER BY r.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Record> page(Map<String, Object> values);

    @Select("<script>SELECT COUNT(*) FROM pmis_project r" + FILTER + "</script>")
    long count(Map<String, Object> values);

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + " FROM pmis_project r WHERE r.id=#{id} AND "
                    + SCOPE
                    + "<if test='locked'> FOR UPDATE</if></script>")
    Record find(Map<String, Object> values);

    @Select("SELECT " + COLUMNS + " FROM pmis_project r WHERE r.id=#{id}")
    Record saved(long id);

    @Insert(
            "INSERT INTO pmis_project(number,name,description,planned_start,planned_end,budget,owner_user_id,organization_id) VALUES(#{number},#{name},#{description},#{start},#{end},#{budget},#{owner},#{org})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int create(Map<String, Object> values);

    @Update(
            "UPDATE pmis_project SET name=#{name},description=#{description},planned_start=#{start},planned_end=#{end},budget=#{budget},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{expectedVersion}")
    int update(Map<String, Object> values);

    @Update(
            "UPDATE pmis_project SET status=#{status},approver_user_id=#{approver,jdbcType=BIGINT},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{expectedVersion}")
    int transition(Map<String, Object> values);

    @Insert(
            "INSERT INTO pmis_project_event(record_id,actor_user_id,action,from_status,to_status,comment) VALUES(#{id},#{actor},#{action},#{from,jdbcType=VARCHAR},#{to},#{comment})")
    int event(Map<String, Object> values);

    @Select(
            "SELECT id,record_id,actor_user_id,action,from_status,to_status,comment,created_at FROM pmis_project_event WHERE record_id=#{id} ORDER BY id")
    List<Event> history(long id);
}
