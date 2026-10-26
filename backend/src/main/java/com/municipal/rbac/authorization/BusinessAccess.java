/** 功能：OA/PMIS唯一公开的身份组织/数据范围入口；内部锁与来源解析不泄漏到业务Mapper或HTTP。 */
package com.municipal.rbac.authorization;

import static com.municipal.rbac.identity.IdentityData.*;

import com.municipal.rbac.authorization.AccessData.*;
import com.municipal.rbac.authorization.internal.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class BusinessAccess {
    public record Context(String applicationId, String organizationId, List<String> permissions) {}

    public record Approver(String id, String username) {}

    public record Range(
            long subjectId,
            Long organizationId,
            boolean all,
            boolean self,
            Set<Long> organizationIds) {
        public boolean contains(long owner, long organization) {
            return all || self && subjectId == owner || organizationIds.contains(organization);
        }
    }

    private final AccessManager manager;
    private final AccessMapper data;
    private final BusinessScopeMapper scopes;
    private final Clock clock;

    public BusinessAccess(
            AccessManager manager, AccessMapper data, BusinessScopeMapper scopes, Clock clock) {
        this.manager = manager;
        this.data = data;
        this.scopes = scopes;
        this.clock = clock;
    }

    public long application(String code) {
        return data.apps().stream()
                .filter(a -> a.code().equals(code) && a.enabled())
                .findFirst()
                .orElseThrow(() -> failure(404, "APPLICATION_NOT_FOUND", "业务应用未启用"))
                .id();
    }

    private AccessRows.User subject(long id) {
        var u = data.user(id);
        if (u == null || !u.enabled() || !u.employeeEnabled() || !u.organizationEnabled())
            throw failure(403, "BUSINESS_IDENTITY_UNAVAILABLE", "业务账号或组织不可用");
        return u;
    }

    private void actor(Actor a) {
        var u = data.user(a.id());
        if (u == null
                || !u.enabled()
                || !u.employeeEnabled()
                || !u.organizationEnabled()
                || u.authVersion() != a.authVersion()
                || a.sessionId() == null) throw failure(401, "IDENTITY_REVOKED", "身份已变化，请重新登录");
    }

    public Context context(Actor a, String app) {
        actor(a);
        long id = application(app);
        var u = subject(a.id());
        return new Context(
                Long.toString(id),
                u.organizationId() == null ? null : u.organizationId().toString(),
                manager.effective(a.id(), id).permissions().stream()
                        .map(EffectivePermission::code)
                        .sorted()
                        .toList());
    }

    public Range read(Actor a, String app, String operation) {
        actor(a);
        return range(a.id(), application(app), operation);
    }

    private Range range(long user, long app, String op) {
        var u = subject(user);
        var effective =
                manager.effective(user, app).permissions().stream()
                        .filter(p -> p.code().equals(op))
                        .findFirst()
                        .orElseThrow(() -> failure(403, "BUSINESS_PERMISSION_DENIED", "缺少当前业务权限"));
        var c = effective.capabilities();
        return new Range(
                user,
                u.organizationId(),
                c.all(),
                c.self(),
                scopes.organizations(c.organizationIds(), c.subtreeRootIds()));
    }

    /** 敏感写与权限变更锁同一命名空间；主体按ID排序加锁，防止提交时身份/指定审批人变化。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Range write(Actor a, String app, String op, Long other) {
        long id = application(app);
        data.lockTree();
        data.lockPlatform();
        data.lockNamespace(id);
        TreeSet<Long> users = new TreeSet<>();
        users.add(a.id());
        if (other != null) users.add(other);
        for (long user : users) data.lockUser(user);
        actor(a);
        return range(a.id(), id, op);
    }

    public List<Approver> approvers(Actor a, String app, long owner, long org) {
        actor(a);
        String resource = app.equals("oa") ? "document" : "project";
        return scopes.approvers(
                application(app),
                owner,
                org,
                app + ":" + resource + ":view",
                app + ":" + resource + ":approve",
                clock.instant());
    }

    public void approver(Actor a, String app, long owner, long org, long target) {
        if (target == owner) throw failure(422, "SELF_APPROVAL", "不能选择本人审批");
        String prefix = app + ":" + (app.equals("oa") ? "document" : "project") + ":";
        try {
            long application = application(app);
            if (!range(target, application, prefix + "view").contains(owner, org)
                    || !range(target, application, prefix + "approve").contains(owner, org))
                throw failure(422, "APPROVER_UNAVAILABLE", "审批人当前权限或范围不足");
        } catch (com.municipal.rbac.shared.DomainFailure e) {
            if (e.status() == 403) throw failure(422, "APPROVER_UNAVAILABLE", "审批人当前不可用");
            throw e;
        }
    }
}
