/** 功能：可控Clock上的绝对会话截止规则；与SessionValidityFilter共用，不把空闲TTL当绝对时长。 */
package com.municipal.rbac.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SessionPolicy {
    public static final String AUTHENTICATED_AT = "authenticatedAt";
    private final Clock clock;
    private final Duration absoluteTimeout;

    public SessionPolicy(
            Clock clock, @Value("${app.session.absolute-timeout:2h}") Duration absoluteTimeout) {
        if (absoluteTimeout.isNegative() || absoluteTimeout.isZero())
            throw new IllegalArgumentException("positive timeout required");
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
    }

    public long nowMillis() {
        return clock.millis();
    }

    public boolean valid(Object since) {
        if (!(since instanceof Long millis)) return false;
        Instant start = Instant.ofEpochMilli(millis);
        Instant now = clock.instant();
        return !now.isBefore(start) && now.isBefore(start.plus(absoluteTimeout));
    }
}
