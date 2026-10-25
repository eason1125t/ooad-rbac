/** 功能：身份模块内部账号读取模型；与id_user映射，不对外输出密码散列。 */
package com.municipal.rbac.identity;

public record Account(
        long id, String username, String passwordHash, boolean enabled, long authVersion) {}
