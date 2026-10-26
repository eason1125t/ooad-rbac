/** 功能：身份公开数据与校验规则；供IdentityDirectory和管理适配使用，ID始终输出字符串。 */
package com.municipal.rbac.identity;

import com.municipal.rbac.shared.DomainFailure;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

public final class IdentityData {
    private IdentityData() {}

    public record Organization(
            String id,
            String code,
            String name,
            String nodeType,
            String parentId,
            String managerEmployeeId,
            boolean enabled,
            long version) {}

    public record Employee(
            String id,
            String employeeCode,
            String displayName,
            String organizationId,
            String positionName,
            String phone,
            boolean enabled,
            long version) {}

    public record UserAccount(
            String id,
            String username,
            String employeeId,
            boolean enabled,
            boolean forcePasswordChange,
            long version) {}

    public record AccountState(long id, boolean enabled, long authVersion, long version) {}

    public record Membership(String roleId, Instant validFrom, Instant validTo) {}

    public record Preview(
            String token,
            String organizationId,
            String parentId,
            long expectedVersion,
            long treeVersion,
            long affectedEmployees,
            Instant expiresAt) {}

    public record PreviewState(
            String token,
            long actorUserId,
            long organizationId,
            long newParentId,
            long targetVersion,
            long treeVersion,
            Instant expiresAt,
            boolean consumed) {}

    public record TemporaryCredential(UserAccount account, String temporaryPassword) {
        @Override
        public String toString() {
            return "TemporaryCredential[redacted]";
        }
    }

    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}"))
            throw failure(400, "INVALID_ID", "标识格式无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw failure(400, "INVALID_ID", "标识超出范围");
        }
    }

    public static String code(String value) {
        if (value == null) throw failure(400, "INVALID_CODE", "编码格式无效");
        String normalized =
                Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,63}"))
            throw failure(400, "INVALID_CODE", "编码须为3至64位小写字母、数字、点、下划线或短横线");
        return normalized;
    }

    public static String text(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw failure(400, "INVALID_TEXT", "文本长度无效");
        return value.trim();
    }

    public static String nameKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    public static String phone(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.trim().matches("\\+?[0-9 ()-]{5,32}"))
            throw failure(400, "INVALID_PHONE", "联系电话格式无效");
        return value.trim();
    }

    public static int offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE)
            throw failure(400, "INVALID_PAGE", "分页参数无效");
        return (page - 1) * size;
    }

    public static String search(String q) {
        if (q == null || q.length() > 100) throw failure(400, "INVALID_SEARCH", "搜索词过长");
        return q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static void version(long actual, long expected) {
        if (expected < 0 || expected > 9007199254740991L)
            throw failure(400, "INVALID_VERSION", "版本无效");
        if (actual != expected) throw failure(409, "VERSION_CONFLICT", "记录已变化，请重新加载后确认");
    }

    public static DomainFailure failure(int status, String code, String message) {
        return new DomainFailure(status, code, message);
    }
}
