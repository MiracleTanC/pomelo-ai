package org.pomelo.api.doc.vo;

/**
 * 文档元数据值 VO。
 */
public record DocMetadataVo(
        String fieldKey,
        String fieldName,
        String fieldValue
) {
}
