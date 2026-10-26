/** 功能：OA公文独立表及历史的参数化SQL；列表/count/详情/写均在数据库过滤业务范围。 */
package com.municipal.rbac.oa;

import com.municipal.rbac.oa.OaDocumentData.Record;
import com.municipal.rbac.shared.BusinessData.Event;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface OaDocumentMapper {
    String COLUMNS =
            "r.id,r.number,r.title,r.body,r.owner_user_id,r.organization_id,r.approver_user_id,r.status,r.version,r.created_at,r.updated_at";
    String SCOPE =
            "(#{scope.all}=TRUE OR (#{scope.self}=TRUE AND r.owner_user_id=#{scope.subjectId}) <if test='scope.organizationIds.size > 0'> OR r.organization_id IN <foreach collection='scope.organizationIds' item='org' open='(' separator=',' close=')'>#{org}</foreach></if>)";
    String FILTER =
            " WHERE "
                    + SCOPE
                    + " AND (#{q}='' OR r.title LIKE CONCAT('%',#{q},'%') ESCAPE '!') <if test='status.length() > 0'> AND r.status=#{status}</if><if test='mine'> AND r.owner_user_id=#{scope.subjectId}</if><if test='pending'> AND r.status='SUBMITTED' AND r.approver_user_id=#{scope.subjectId}</if>";

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + " FROM oa_document r"
                    + FILTER
                    + " ORDER BY r.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Record> page(Map<String, Object> values);

    @Select("<script>SELECT COUNT(*) FROM oa_document r" + FILTER + "</script>")
    long count(Map<String, Object> values);

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + " FROM oa_document r WHERE r.id=#{id} AND "
                    + SCOPE
                    + "<if test='locked'> FOR UPDATE</if></script>")
    Record find(Map<String, Object> values);

    @Select("SELECT " + COLUMNS + " FROM oa_document r WHERE r.id=#{id}")
    Record saved(long id);

    @Insert(
            "INSERT INTO oa_document(number,title,body,owner_user_id,organization_id) VALUES(#{number},#{title},#{body},#{owner},#{org})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int create(Map<String, Object> values);

    @Update(
            "UPDATE oa_document SET title=#{title},body=#{body},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{expectedVersion}")
    int update(Map<String, Object> values);

    @Update(
            "UPDATE oa_document SET status=#{status},approver_user_id=#{approver,jdbcType=BIGINT},version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{expectedVersion}")
    int transition(Map<String, Object> values);

    @Insert(
            "INSERT INTO oa_document_event(record_id,actor_user_id,action,from_status,to_status,comment) VALUES(#{id},#{actor},#{action},#{from,jdbcType=VARCHAR},#{to},#{comment})")
    int event(Map<String, Object> values);

    @Select(
            "SELECT id,record_id,actor_user_id,action,from_status,to_status,comment,created_at FROM oa_document_event WHERE record_id=#{id} ORDER BY id")
    List<Event> history(long id);
}
