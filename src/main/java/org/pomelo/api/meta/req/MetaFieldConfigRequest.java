package org.pomelo.api.meta.req;

import jakarta.validation.constraints.NotBlank;

public record MetaFieldConfigRequest(
        @NotBlank String fieldKey,
        @NotBlank String fieldName,
        String fieldType,
        @NotBlank String extractType,
        String extractRule,
        Boolean required,
        Boolean searchable,
        Boolean vectorized,
        Boolean displayInList,
        Integer sortOrder,
        Boolean enabled
) {
}
