package org.pomelo.common.enums;

import lombok.Getter;

/**
 * 文档处理状态（上传与向量化解耦）。
 *
 * <pre>
 * UPLOADED → PARSING → PARSED → CHUNKING → CHUNKED → VECTORIZING → INDEXED
 *                ↘ PARSE_FAILED        ↘ CHUNK_FAILED       ↘ VECTOR_FAILED
 * </pre>
 */
@Getter
public enum DocStatus {

    UPLOADED("已上传"),
    PARSING("解析中"),
    PARSED("已解析"),
    PARSE_FAILED("解析失败"),
    CHUNKING("切片中"),
    CHUNKED("已切片"),
    CHUNK_FAILED("切片失败"),
    VECTORIZING("向量化中"),
    INDEXED("已索引"),
    VECTOR_FAILED("向量化失败"),
    DELETED("已删除");

    private final String label;

    DocStatus(String label) {
        this.label = label;
    }

    /** 判断是否为终态（可检索或最终失败） */
    public boolean isTerminal() {
        return this == INDEXED || this == PARSE_FAILED
                || this == CHUNK_FAILED || this == VECTOR_FAILED || this == DELETED;
    }

    /** 判断是否可检索 */
    public boolean isSearchable() {
        return this == INDEXED;
    }

    /** 判断是否处于处理中 */
    public boolean isProcessing() {
        return this == PARSING || this == CHUNKING || this == VECTORIZING;
    }
}
