/** 功能：验证数据范围结构语义；子树未来后代、SELF历史记录与正向增量必须区别处理。 */
package com.municipal.rbac.authorization;

import static com.municipal.rbac.authorization.AccessData.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class ScopePolicyTest {
    private final BiPredicate<String, String> ancestor =
            (a, b) -> a.equals(b) || a.equals("1") || a.equals("2") && b.equals("3");

    private Scope s(String kind, String... nodes) {
        return new Scope(kind, List.of(nodes));
    }

    @Test
    void allCoversEveryTemplateButExactOrganizationNeverImpliesSelf() {
        var all = ScopePolicy.union(List.of(s("ALL")));
        for (var requested : List.of(s("SELF"), s("ORG", "3"), s("ORG_SUBTREE", "2"), s("ALL")))
            assertThat(ScopePolicy.covers(all, requested, ancestor)).isTrue();
        var exact = ScopePolicy.union(List.of(s("ORG", "3")));
        assertThat(ScopePolicy.covers(exact, s("SELF"), ancestor)).isFalse();
        assertThat(ScopePolicy.covers(exact, s("ORG_SUBTREE", "3"), ancestor)).isFalse();
    }

    @Test
    void unionRetainsSelfAndStructuralSubtreeCoverage() {
        var union = ScopePolicy.union(List.of(s("SELF"), s("ORG", "4"), s("ORG_SUBTREE", "2")));
        assertThat(ScopePolicy.covers(union, s("ORG", "3", "4"), ancestor)).isTrue();
        assertThat(ScopePolicy.covers(union, s("ORG_SUBTREE", "3"), ancestor)).isTrue();
        assertThat(ScopePolicy.covers(union, s("ORG_SUBTREE", "1"), ancestor)).isFalse();
    }

    @Test
    void intersectionChoosesNarrowerSubtreeAndOnlySharedSelf() {
        var a = ScopePolicy.union(List.of(s("SELF"), s("ORG_SUBTREE", "1")));
        var b = ScopePolicy.union(List.of(s("ORG_SUBTREE", "2"), s("ORG", "4")));
        var c = ScopePolicy.intersect(a, b, ancestor);
        assertThat(c.self()).isFalse();
        assertThat(c.subtreeRootIds()).containsExactly("2");
        assertThat(c.organizationIds()).containsExactly("4");
        assertThat(ScopePolicy.intersect(ScopePolicy.union(List.of(s("ALL"))), b, ancestor))
                .isEqualTo(b);
    }

    @Test
    void additionsRequireOnlyNewNodesAndTreatTemplateExpansionAsPositive() {
        assertThat(ScopePolicy.additions(s("ORG", "2"), s("ORG", "2", "3"), ancestor))
                .containsExactly(s("ORG", "3"));
        assertThat(ScopePolicy.additions(s("ORG_SUBTREE", "2"), s("ORG", "3"), ancestor)).isEmpty();
        assertThat(ScopePolicy.additions(s("ORG", "3"), s("ORG_SUBTREE", "3"), ancestor))
                .containsExactly(s("ORG_SUBTREE", "3"));
        assertThat(ScopePolicy.additions(s("ALL"), s("SELF"), ancestor)).isEmpty();
        assertThat(ScopePolicy.additions(null, s("ALL"), ancestor)).containsExactly(s("ALL"));
    }
}
