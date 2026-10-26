/** 功能：结构化数据范围并集、覆盖和交集；保留SELF/子树模板，避免用当前叶节点推导未来权限。 */
package com.municipal.rbac.authorization;

import static com.municipal.rbac.authorization.AccessData.*;

import java.util.*;
import java.util.function.BiPredicate;

public final class ScopePolicy {
    private ScopePolicy() {}

    public static Capabilities empty() {
        return new Capabilities(false, false, Set.of(), Set.of());
    }

    public static Capabilities union(Collection<Scope> scopes) {
        boolean all = false, self = false;
        Set<String> exact = new TreeSet<>(), trees = new TreeSet<>();
        for (Scope s : scopes)
            switch (s.kind()) {
                case "ALL" -> all = true;
                case "SELF" -> self = true;
                case "ORG" -> exact.addAll(s.organizationIds());
                case "ORG_SUBTREE" -> trees.addAll(s.organizationIds());
                default -> throw new IllegalArgumentException("Invalid scope kind");
            }
        return new Capabilities(all, self, Set.copyOf(exact), Set.copyOf(trees));
    }

    public static boolean covers(
            Capabilities c, Scope requested, BiPredicate<String, String> ancestor) {
        if (c.all()) return true;
        return switch (requested.kind()) {
            case "ALL" -> false;
            case "SELF" -> c.self();
            case "ORG" ->
                    requested.organizationIds().stream()
                            .allMatch(
                                    n ->
                                            c.organizationIds().contains(n)
                                                    || c.subtreeRootIds().stream()
                                                            .anyMatch(r -> ancestor.test(r, n)));
            case "ORG_SUBTREE" ->
                    requested.organizationIds().stream()
                            .allMatch(
                                    n ->
                                            c.subtreeRootIds().stream()
                                                    .anyMatch(r -> ancestor.test(r, n)));
            default -> false;
        };
    }

    public static Capabilities intersect(
            Capabilities a, Capabilities b, BiPredicate<String, String> ancestor) {
        if (a.all()) return b;
        if (b.all()) return a;
        Set<String> exact = new TreeSet<>(), trees = new TreeSet<>();
        for (String n : a.organizationIds())
            if (covers(b, new Scope("ORG", List.of(n)), ancestor)) exact.add(n);
        for (String n : b.organizationIds())
            if (covers(a, new Scope("ORG", List.of(n)), ancestor)) exact.add(n);
        for (String r : a.subtreeRootIds())
            for (String s : b.subtreeRootIds()) {
                if (ancestor.test(r, s)) trees.add(s);
                else if (ancestor.test(s, r)) trees.add(r);
            }
        return new Capabilities(false, a.self() && b.self(), Set.copyOf(exact), Set.copyOf(trees));
    }

    /** 只比较正向增量；时间扩展另外触发完整新范围校验。 */
    public static List<Scope> additions(
            Scope old, Scope replacement, BiPredicate<String, String> ancestor) {
        Capabilities c = old == null ? empty() : union(List.of(old));
        if (covers(c, replacement, ancestor)) return List.of();
        if (replacement.kind().equals("ORG") || replacement.kind().equals("ORG_SUBTREE"))
            return replacement.organizationIds().stream()
                    .map(n -> new Scope(replacement.kind(), List.of(n)))
                    .filter(s -> !covers(c, s, ancestor))
                    .toList();
        return List.of(replacement);
    }
}
