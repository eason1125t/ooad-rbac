/** 功能：RBAC权限来源的统一扩展边界；RBAC1以后替换内部解析，不改变业务权限声明。 */
package com.municipal.rbac.authorization;

import java.time.Instant;
import java.util.Set;

public interface PermissionResolver {
    Set<String> resolve(long subjectId, Instant now);
}
