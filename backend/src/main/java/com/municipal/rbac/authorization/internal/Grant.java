/** 功能：权限查询的原始有效期来源；保留每个来源，避免单个来源到期误撤销其他有效授权。 */
package com.municipal.rbac.authorization.internal;

import java.time.LocalDateTime;

public record Grant(
        String code,
        LocalDateTime userFrom,
        LocalDateTime userTo,
        LocalDateTime roleFrom,
        LocalDateTime roleTo) {}
