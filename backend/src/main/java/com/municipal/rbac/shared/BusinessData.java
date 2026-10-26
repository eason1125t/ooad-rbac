/** 功能：OA/PMIS共用的请求与历史契约；仅共享值类型，两个业务的表、服务和状态机独立。 */
package com.municipal.rbac.shared;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class BusinessData {
    private BusinessData() {}

    public record Version(@NotNull @PositiveOrZero Long expectedVersion) {}

    public record Submit(
            @NotNull @PositiveOrZero Long expectedVersion, @NotBlank String approverUserId) {}

    public record Decision(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(max = 500) String comment) {}

    public record Event(
            String id,
            String recordId,
            String actorUserId,
            String action,
            String fromStatus,
            String toStatus,
            String comment,
            Instant createdAt) {}

    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new DomainFailure(400, "INVALID_ID", "标识格式或范围无效");
        }
    }

    public static int offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE)
            throw new DomainFailure(400, "INVALID_PAGE", "分页参数无效");
        return (page - 1) * size;
    }

    public static String search(String value) {
        if (value == null) return "";
        if (value.length() > 100) throw new DomainFailure(400, "INVALID_SEARCH", "检索条件最多100字");
        return value.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static void version(long actual, Long expected) {
        if (expected == null || expected < 0)
            throw new DomainFailure(400, "INVALID_VERSION", "版本参数无效");
        if (actual != expected) throw new DomainFailure(409, "VERSION_CONFLICT", "记录已变化，请重新加载");
    }
}
