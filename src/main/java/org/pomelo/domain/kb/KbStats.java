package org.pomelo.domain.kb;

import lombok.Getter;
import lombok.Setter;

/**
 * 知识库统计 VO：用于 KB 详情页统计面板。
 */
@Getter
@Setter
public class KbStats {
    /** 知识库配置的文档数（实时聚合） */
    private int docCount;
    /** 知识库配置的切片总数（实时聚合） */
    private int chunkCount;
    /** 切片大小 */
    private int chunkSize;
    /** Top-K */
    private int topK;
    /** 是否自动向量化 */
    private boolean autoVectorize;
    /** 切片策略 */
    private String chunkStrategy;
    /** 文档总数（含所有状态） */
    private int totalDocs;
    /** 已索引文档数 */
    private int indexedDocs;
    /** 待处理文档数 */
    private int pendingDocs;
    /** 失败文档数 */
    private int failedDocs;
    /** 切片总数 */
    private int totalChunks;
    /** 已向量化切片数 */
    private int totalVectorized;
    /** 元数据字段数 */
    private int metaFieldCount;
}
