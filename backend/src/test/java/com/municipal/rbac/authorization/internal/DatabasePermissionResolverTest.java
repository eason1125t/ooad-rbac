/** 功能：用可控时间验证RBAC0半开区间和多来源并集；Mapper为合成桩，不冒充MySQL集成。 */
package com.municipal.rbac.authorization.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.municipal.rbac.authorization.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class DatabasePermissionResolverTest {
    private final GrantMapper mapper = mock(GrantMapper.class);
    private final DatabasePermissionResolver resolver = new DatabasePermissionResolver(mapper);
    private final Instant now = Instant.parse("2026-10-25T00:00:00Z");
    private final LocalDateTime utc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

    @Test
    void includesStartAndExcludesEndOfBothRelations() {
        when(mapper.findSources(1))
                .thenReturn(
                        List.of(
                                new Grant("oa:start:view", utc, null, utc, null),
                                new Grant("oa:user-end:view", null, utc, null, null),
                                new Grant("oa:role-end:view", null, null, null, utc),
                                new Grant(
                                        "oa:future-user:view",
                                        utc.plusSeconds(1),
                                        null,
                                        null,
                                        null),
                                new Grant(
                                        "oa:future-role:view",
                                        null,
                                        null,
                                        utc.plusSeconds(1),
                                        null)));
        assertThat(resolver.resolve(1, now)).containsExactly("oa:start:view");
    }

    @Test
    void oneExpiredSourceDoesNotRemoveAnotherAndFutureBoundaryIsReevaluated() {
        when(mapper.findSources(1))
                .thenReturn(
                        List.of(
                                new Grant("oa:document:view", null, utc, null, null),
                                new Grant("oa:document:view", null, null, utc, utc.plusSeconds(2)),
                                new Grant(
                                        "pmis:project:view",
                                        utc.plusSeconds(2),
                                        null,
                                        null,
                                        null)));
        assertThat(resolver.resolve(1, now)).containsExactly("oa:document:view");
        assertThat(resolver.resolve(1, now.plusSeconds(2))).containsExactly("pmis:project:view");
    }

    @Test
    void checkerRejectsCrossApplicationAndMissingSession() {
        PermissionChecker checker =
                new PermissionChecker(resolver, Clock.fixed(now, ZoneOffset.UTC));
        when(mapper.findSources(1))
                .thenReturn(List.of(new Grant("oa:document:view", null, null, null, null)));
        assertThat(
                        checker.allows(
                                new PermissionContext(
                                        1, "session", "oa", "oa:document:view", Map.of())))
                .isTrue();
        assertThat(
                        checker.allows(
                                new PermissionContext(
                                        1, "session", "pmis", "oa:document:view", Map.of())))
                .isFalse();
        assertThat(
                        checker.allows(
                                new PermissionContext(1, null, "oa", "oa:document:view", Map.of())))
                .isFalse();
        assertThat(
                        checker.allows(
                                new PermissionContext(
                                        0, "session", "oa", "oa:document:view", Map.of())))
                .isFalse();
    }
}
