/** 功能：PMIS立项请求与视图；与唯一OpenAPI、PmisProjectService和PmisProjectMapper字段保持对应。 */
package com.municipal.rbac.pmis;

import jakarta.validation.constraints.*;
import java.time.*;

public final class PmisProjectData {
    private PmisProjectData() {}

    public record Create(
            @NotBlank @Size(max = 160) String name,
            @NotNull @Size(max = 2000) String description,
            @NotNull java.time.LocalDate plannedStart,
            @NotNull java.time.LocalDate plannedEnd,
            @NotBlank @Pattern(regexp = "(0|[1-9][0-9]{0,13})\\.[0-9]{2}") String budget) {}

    public record Update(
            @NotBlank @Size(max = 160) String name,
            @NotNull @Size(max = 2000) String description,
            @NotNull java.time.LocalDate plannedStart,
            @NotNull java.time.LocalDate plannedEnd,
            @NotBlank @Pattern(regexp = "(0|[1-9][0-9]{0,13})\\.[0-9]{2}") String budget,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Record(
            String id,
            String number,
            String name,
            String description,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            String budget,
            String ownerUserId,
            String organizationId,
            String approverUserId,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {}
}
