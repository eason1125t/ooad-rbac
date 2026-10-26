/** 功能：管理审计公开入口；调用方提供脱敏ID/状态，和管理写入共用数据库事务。 */
package com.municipal.rbac.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.municipal.rbac.identity.IdentityData;
import com.municipal.rbac.shared.PageResult;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditLog {
    public record Row(
            String id,
            String actorUserId,
            String action,
            String targetType,
            String targetId,
            String beforeJson,
            String afterJson,
            String traceId,
            Instant createdAt) {}

    public record Event(
            String id,
            String actorUserId,
            String action,
            String targetType,
            String targetId,
            JsonNode beforeState,
            JsonNode afterState,
            String traceId,
            Instant createdAt) {}

    private final Mapper mapper;
    private final ObjectMapper json;

    public AuditLog(Mapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            long actor,
            String trace,
            String action,
            String kind,
            long target,
            Map<String, ?> before,
            Map<String, ?> after) {
        mapper.append(actor, trace, action, kind, target, encode(before), encode(after));
    }

    private String encode(Map<String, ?> value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit encoding failed", e);
        }
    }

    private JsonNode decode(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Audit decoding failed", e);
        }
    }

    public PageResult<Event> page(int page, int size) {
        int offset = IdentityData.offset(page, size);
        var items =
                mapper.page(size, offset).stream()
                        .map(
                                r ->
                                        new Event(
                                                r.id(),
                                                r.actorUserId(),
                                                r.action(),
                                                r.targetType(),
                                                r.targetId(),
                                                decode(r.beforeJson()),
                                                decode(r.afterJson()),
                                                r.traceId(),
                                                r.createdAt()))
                        .toList();
        return new PageResult<>(items, page, size, mapper.count());
    }

    @org.apache.ibatis.annotations.Mapper
    public interface Mapper {
        @Insert(
                "INSERT INTO audit_event(actor_user_id,trace_id,action,target_type,target_id,before_json,after_json) VALUES(#{actor},#{trace},#{action},#{kind},#{target},#{before,jdbcType=VARCHAR},#{after,jdbcType=VARCHAR})")
        int append(
                @Param("actor") long actor,
                @Param("trace") String trace,
                @Param("action") String action,
                @Param("kind") String kind,
                @Param("target") long target,
                @Param("before") String before,
                @Param("after") String after);

        @Select(
                "SELECT id,actor_user_id,action,target_type,target_id,before_json,after_json,trace_id,created_at FROM audit_event ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
        List<Row> page(@Param("size") int size, @Param("offset") int offset);

        @Select("SELECT COUNT(*) FROM audit_event")
        long count();
    }
}
