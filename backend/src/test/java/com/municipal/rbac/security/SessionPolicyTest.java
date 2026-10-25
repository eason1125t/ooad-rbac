/** 功能：明确验证绝对截止和未来/缺失时间标记，独立于Servlet空闲TTL与数据库。 */
package com.municipal.rbac.security;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class SessionPolicyTest {
    private final Instant start = Instant.parse("2026-10-25T00:00:00Z");

    @Test
    void absoluteExpiryDoesNotSlideWithRequests() {
        var before =
                new SessionPolicy(
                        Clock.fixed(start.plusSeconds(7199), ZoneOffset.UTC), Duration.ofHours(2));
        var boundary =
                new SessionPolicy(
                        Clock.fixed(start.plusSeconds(7200), ZoneOffset.UTC), Duration.ofHours(2));
        assertThat(before.valid(start.toEpochMilli())).isTrue();
        assertThat(before.valid(start.toEpochMilli())).isTrue();
        assertThat(boundary.valid(start.toEpochMilli())).isFalse();
        assertThat(boundary.valid(null)).isFalse();
        assertThat(boundary.valid("invalid")).isFalse();
        assertThat(before.valid(start.plusSeconds(8000).toEpochMilli())).isFalse();
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new SessionPolicy(Clock.systemUTC(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionPolicy(Clock.systemUTC(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
