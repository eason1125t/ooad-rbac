/** 功能：Redis原子登录限流，键仅存账号/IP摘要；SecurityConfig成功/失败回调维护连续失败计数。 */
package com.municipal.rbac.security;

import com.municipal.rbac.shared.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class LoginThrottle {
    private static final DefaultRedisScript<Long> INCREMENT =
            new DefaultRedisScript<>(
                    "local n=redis.call('INCR',KEYS[1]); if n==1 or n==tonumber(ARGV[2]) then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",
                    Long.class);
    private final StringRedisTemplate redis;

    public LoginThrottle(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String kind, String value) {
        String input =
                value == null
                        ? ""
                        : Normalizer.normalize(value, Normalizer.Form.NFKC)
                                .trim()
                                .toLowerCase(Locale.ROOT);
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8));
            return "municipal:login:" + kind + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public void check(String username, String ip) {
        Long attempts = redis.execute(INCREMENT, List.of(key("ip", ip)), "60", "0");
        if (attempts == null || attempts < 1)
            throw new RedisConnectionFailureException("Login limit state unavailable");
        String failed = redis.opsForValue().get(key("account", username));
        if (failed != null && !failed.matches("[0-9]{1,10}"))
            throw new RedisConnectionFailureException("Login limit state invalid");
        if (attempts > 30 || (failed != null && Long.parseLong(failed) >= 5))
            throw new DomainFailure(429, "LOGIN_RATE_LIMITED", "登录尝试过于频繁，请稍后再试");
    }

    public void failed(String username) {
        Long result = redis.execute(INCREMENT, List.of(key("account", username)), "900", "5");
        if (result == null)
            throw new RedisConnectionFailureException("Login limit state unavailable");
    }

    public void succeeded(String username) {
        redis.delete(key("account", username));
    }
}
