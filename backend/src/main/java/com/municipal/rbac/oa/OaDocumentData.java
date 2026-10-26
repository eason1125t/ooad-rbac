/** 功能：OA公文请求与视图；与唯一OpenAPI、OaDocumentService和OaDocumentMapper字段保持对应。 */
package com.municipal.rbac.oa;

import jakarta.validation.constraints.*;
import java.time.*;

public final class OaDocumentData {
    private OaDocumentData() {}

    public record Create(
            @NotBlank @Size(max = 160) String title, @NotNull @Size(max = 10000) String body) {}

    public record Update(
            @NotBlank @Size(max = 160) String title,
            @NotNull @Size(max = 10000) String body,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Record(
            String id,
            String number,
            String title,
            String body,
            String ownerUserId,
            String organizationId,
            String approverUserId,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt) {}
}
