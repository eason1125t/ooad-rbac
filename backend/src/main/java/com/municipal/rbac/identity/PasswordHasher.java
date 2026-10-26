/** 功能：身份密码算法端口；security提供BCrypt适配，identity不依赖Spring Security或HTTP。 */
package com.municipal.rbac.identity;

public interface PasswordHasher {
    String encode(String password);

    boolean matches(String password, String hash);
}
