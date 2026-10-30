package org.pomelo.domain.kb;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeBase {

    private String id;

    private String name;

    private String description;

    private String collectionName;

    // ---- 检索参数 ----
    @Builder.Default
    private int topK = 8;

    @Builder.Default
    private int rerankTopK = 4;

    @Builder.Default
    private double similarityThreshold = 0.3;

    // ---- 切片参数 ----
    @Builder.Default
    private int chunkSize = 500;

    @Builder.Default
    private int chunkOverlap = 50;

    // ---- 自动向量化开关（默认关闭，手动触发） ----
    @Builder.Default
    private boolean autoVectorize = false;

    // ---- 切片策略: general(通用) / heading(按标题层级) / qa(QA问答对) ----
    @Builder.Default
    private String chunkStrategy = "general";

    // ---- 统计 ----
    @Builder.Default
    private int docCount = 0;

    @Builder.Default
    private int chunkCount = 0;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
