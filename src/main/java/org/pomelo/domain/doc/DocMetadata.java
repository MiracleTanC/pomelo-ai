package org.pomelo.domain.doc;

import lombok.*;

import java.time.Instant;

/**
 * 文档元数据值：按 MetaFieldConfig 配置提取后的实际值。
 * 一个文档对一个字段一行记录。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocMetadata {

    private String id;

    private String docId;

    private String kbId;

    private String fieldKey;

    private String fieldName;

    /** 提取到的值（list/json 类型用 JSON 字符串存） */
    private String fieldValue;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
