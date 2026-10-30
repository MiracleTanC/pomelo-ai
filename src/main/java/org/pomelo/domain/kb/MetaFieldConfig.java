package org.pomelo.domain.kb;

import lombok.*;

import java.time.Instant;

/**
 * 知识库级元数据字段配置：可动态增删，每个字段带独立的提取规则（描述词）。
 * <p>
 * 提取方式：
 * <ul>
 *   <li>SYSTEM — 系统自动获取（file_name / create_time / vector_status）</li>
 *   <li>DOC_PROPERTY — 从文档元数据提取（title / author / publish_date）</li>
 *   <li>REGEX — 正则匹配正文内容</li>
 *   <li>LLM — 大模型生成（keywords / summary / topic 等，P1 实现）</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetaFieldConfig {

    private String id;

    private String kbId;

    private String fieldKey;

    private String fieldName;

    /** string / text / number / date / datetime / list / json / enum */
    @Builder.Default
    private String fieldType = "string";

    /** SYSTEM / DOC_PROPERTY / REGEX / LLM */
    private String extractType;

    /** 提取描述词/规则：LLM 时为提示词模板；REGEX 时为正则；DOC_PROPERTY 时为属性名 */
    private String extractRule;

    @Builder.Default
    private boolean required = false;

    @Builder.Default
    private boolean searchable = false;

    /** 是否将字段值向量化参与检索（如摘要/关键词） */
    @Builder.Default
    private boolean vectorized = false;

    @Builder.Default
    private boolean displayInList = false;

    @Builder.Default
    private int sortOrder = 0;

    @Builder.Default
    private boolean enabled = true;

    private Instant createdAt = Instant.now();
}
