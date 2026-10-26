/** 功能：本轮RBAC0真实数据库解析；通过半开时间区间和来源并集合并支持到期及未来开始。 */
package com.municipal.rbac.authorization.internal;

import com.municipal.rbac.authorization.PermissionResolver;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DatabasePermissionResolver implements PermissionResolver {
    private final GrantMapper mapper;

    public DatabasePermissionResolver(GrantMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<Long> managedApplications(long subjectId, String operation, Instant now) {
        return mapper.managedApplications(subjectId, operation, now);
    }

    @Override
    public Set<String> resolve(long subjectId, Instant now) {
        LocalDateTime utc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        // 不先去重来源：任何一个有效来源均可授予该权限。
        return mapper.findSources(subjectId).stream()
                .filter(
                        g ->
                                within(utc, g.userFrom(), g.userTo())
                                        && within(utc, g.roleFrom(), g.roleTo()))
                .map(Grant::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean within(LocalDateTime now, LocalDateTime from, LocalDateTime to) {
        return (from == null || !now.isBefore(from)) && (to == null || now.isBefore(to));
    }
}
