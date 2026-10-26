/** 功能：PMIS立项独立最小状态机；通过BusinessAccess公开接口执行当前范围、指定审批及并发保护。 */
package com.municipal.rbac.pmis;

import static com.municipal.rbac.shared.BusinessData.*;

import com.municipal.rbac.audit.AuditLog;
import com.municipal.rbac.authorization.AccessData.Actor;
import com.municipal.rbac.authorization.BusinessAccess;
import com.municipal.rbac.pmis.PmisProjectData.*;
import com.municipal.rbac.pmis.PmisProjectData.Record;
import com.municipal.rbac.shared.*;
import com.municipal.rbac.shared.BusinessData.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class PmisProjectService {
    private static final String APP = "pmis", PREFIX = "pmis:project:";
    private final PmisProjectMapper data;
    private final BusinessAccess access;
    private final AuditLog audit;

    public PmisProjectService(PmisProjectMapper data, BusinessAccess access, AuditLog audit) {
        this.data = data;
        this.access = access;
        this.audit = audit;
    }

    private DomainFailure reject(int status, String code, String message) {
        return new DomainFailure(status, code, message);
    }

    private Map<String, Object> values(Object... pairs) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) v.put((String) pairs[i], pairs[i + 1]);
        return v;
    }

    public BusinessAccess.Context context(Actor a) {
        return access.context(a, APP);
    }

    public PageResult<Record> page(
            Actor a, int page, int size, String q, String status, boolean mine, boolean pending) {
        int offset = offset(page, size);
        if (!status.isEmpty()
                && !Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED")
                        .contains(status)) throw reject(400, "INVALID_STATUS", "状态筛选无效");
        var range = access.read(a, APP, PREFIX + "view");
        var v =
                values(
                        "scope", range, "q", search(q), "status", status, "mine", mine, "pending",
                        pending, "size", size, "offset", offset);
        return new PageResult<>(data.page(v), page, size, data.count(v));
    }

    private Record record(long id, BusinessAccess.Range range, boolean locked) {
        var r = data.find(values("id", id, "scope", range, "locked", locked));
        if (r == null) throw reject(404, "BUSINESS_RECORD_NOT_FOUND", "记录不存在或不在当前范围");
        return r;
    }

    public Record get(Actor a, long id) {
        return record(id, access.read(a, APP, PREFIX + "view"), false);
    }

    public List<Event> history(Actor a, long id) {
        get(a, id);
        return data.history(id);
    }

    private void owner(Actor a, Record r) {
        if (a.id() != id(r.ownerUserId())) throw reject(403, "OWNER_REQUIRED", "仅创建本人可执行此操作");
    }

    private void editable(Record r) {
        if (!Set.of("DRAFT", "REJECTED").contains(r.status()))
            throw reject(409, "BUSINESS_STATE_CONFLICT", "仅草稿或驳回记录可编辑、提交或取消");
    }

    private void dates(java.time.LocalDate start, java.time.LocalDate end) {
        if (end.isBefore(start)) throw reject(422, "PROJECT_DATES", "计划结束日期不能早于开始日期");
    }

    private void event(
            Actor a, Record before, long id, String action, String next, String comment) {
        data.event(
                values(
                        "id",
                        id,
                        "actor",
                        a.id(),
                        "action",
                        action.toUpperCase(Locale.ROOT),
                        "from",
                        before == null ? null : before.status(),
                        "to",
                        next,
                        "comment",
                        comment));
        audit.append(
                a.id(),
                a.traceId(),
                "PMIS_" + action.toUpperCase(Locale.ROOT),
                "pmis_project",
                id,
                before == null
                        ? null
                        : Map.of("status", before.status(), "version", before.version()),
                Map.of("status", next, "version", before == null ? 0 : before.version() + 1));
    }

    @Transactional
    public Record create(Actor a, Create r) {
        var range = access.write(a, APP, PREFIX + "create", null);
        if (range.organizationId() == null)
            throw reject(422, "EMPLOYEE_PROFILE_REQUIRED", "创建业务记录需先关联在职人员和组织");
        if (!range.contains(a.id(), range.organizationId()))
            throw reject(403, "BUSINESS_SCOPE_DENIED", "创建组织不在当前数据范围");
        dates(r.plannedStart(), r.plannedEnd());
        var v =
                values(
                        "number",
                        "PM-" + UUID.randomUUID().toString().replace("-", ""),
                        "name",
                        r.name().trim(),
                        "description",
                        r.description(),
                        "start",
                        r.plannedStart(),
                        "end",
                        r.plannedEnd(),
                        "budget",
                        new java.math.BigDecimal(r.budget()),
                        "owner",
                        a.id(),
                        "org",
                        range.organizationId());
        data.create(v);
        long id = ((Number) v.get("id")).longValue();
        access.read(a, APP, PREFIX + "create");
        event(a, null, id, "create", "DRAFT", "");
        return data.saved(id);
    }

    @Transactional
    public Record update(Actor a, long id, Update r) {
        var range = access.write(a, APP, PREFIX + "update", null);
        var old = record(id, range, true);
        version(old.version(), r.expectedVersion());
        owner(a, old);
        editable(old);
        dates(r.plannedStart(), r.plannedEnd());
        var v =
                values(
                        "id",
                        id,
                        "expectedVersion",
                        r.expectedVersion(),
                        "name",
                        r.name().trim(),
                        "description",
                        r.description(),
                        "start",
                        r.plannedStart(),
                        "end",
                        r.plannedEnd(),
                        "budget",
                        new java.math.BigDecimal(r.budget()));
        if (data.update(v) != 1) throw reject(409, "VERSION_CONFLICT", "记录已变化");
        access.read(a, APP, PREFIX + "update");
        event(a, old, id, "update", old.status(), "");
        return data.saved(id);
    }

    public List<BusinessAccess.Approver> approvers(Actor a, long id) {
        var r = record(id, access.read(a, APP, PREFIX + "submit"), false);
        owner(a, r);
        editable(r);
        return access.approvers(a, APP, id(r.ownerUserId()), id(r.organizationId()));
    }

    @Transactional
    public Record act(
            Actor a, long id, String action, long expected, String approver, String comment) {
        Long selected = action.equals("submit") ? id(approver) : null;
        var range = access.write(a, APP, PREFIX + action, selected);
        var old = record(id, range, true);
        version(old.version(), expected);
        String next;
        switch (action) {
            case "submit" -> {
                owner(a, old);
                editable(old);
                access.approver(a, APP, id(old.ownerUserId()), id(old.organizationId()), selected);
                next = "SUBMITTED";
            }
            case "withdraw" -> {
                owner(a, old);
                if (!old.status().equals("SUBMITTED"))
                    throw reject(409, "BUSINESS_STATE_CONFLICT", "仅待审批记录可撤回");
                next = "DRAFT";
                selected = null;
            }
            case "cancel" -> {
                owner(a, old);
                editable(old);
                next = "CANCELLED";
                selected = null;
            }
            case "approve", "reject" -> {
                if (!old.status().equals("SUBMITTED"))
                    throw reject(409, "BUSINESS_STATE_CONFLICT", "记录已处理或未提交");
                if (a.id() == id(old.ownerUserId())
                        || old.approverUserId() == null
                        || a.id() != id(old.approverUserId()))
                    throw reject(403, "DESIGNATED_APPROVER_REQUIRED", "仅当前指定的其他审批人可处理");
                if (action.equals("reject") && comment.isBlank())
                    throw reject(422, "REJECTION_COMMENT_REQUIRED", "驳回意见必填");
                next = action.equals("approve") ? "APPROVED" : "REJECTED";
                selected = id(old.approverUserId());
            }
            default -> throw reject(400, "INVALID_BUSINESS_ACTION", "业务动作无效");
        }
        if (data.transition(
                        values(
                                "id",
                                id,
                                "expectedVersion",
                                expected,
                                "status",
                                next,
                                "approver",
                                selected))
                != 1) throw reject(409, "VERSION_CONFLICT", "记录已变化");
        // 再核时间边界；命名空间锁阻止撤权穿插，过期或审计失败使状态和历史一起回滚。
        access.read(a, APP, PREFIX + action);
        if (action.equals("submit"))
            access.approver(a, APP, id(old.ownerUserId()), id(old.organizationId()), selected);
        event(a, old, id, action, next, comment);
        return data.saved(id);
    }
}
