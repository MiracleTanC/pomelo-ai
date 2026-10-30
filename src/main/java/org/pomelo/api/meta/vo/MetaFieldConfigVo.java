package org.pomelo.api.meta.vo;

import java.time.Instant;

public record MetaFieldConfigVo(
        String id,
        String kbId,
        String fieldKey,
        String fieldName,
        String fieldType,
        String extractType,
        String extractRule,
        boolean required,
        boolean searchable,
        boolean vectorized,
        boolean displayInList,
        int sortOrder,
        boolean enabled,
        Instant createdAt
) {
}
