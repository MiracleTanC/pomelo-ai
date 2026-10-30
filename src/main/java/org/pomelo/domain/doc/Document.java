package org.pomelo.domain.doc;

import lombok.*;
import org.pomelo.common.enums.DocStatus;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    private String id;

    private String kbId;

    private String name;

    private String filePath;

    private long fileSize;

    private String mimeType;

    /** 文件 SHA-256 哈希，用于去重检测 */
    private String sha256;

    /** 解析后提取的纯文本内容 */
    private String parsedContent;

    @Builder.Default
    private DocStatus status = DocStatus.UPLOADED;

    @Builder.Default
    private int chunkCount = 0;

    /** 已向量化的切片数（用于进度展示） */
    @Builder.Default
    private int vectorizedChunks = 0;

    private String errorMsg;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    private Instant parsedAt;

    private Instant chunkedAt;

    private Instant vectorizedAt;

    /** 所属文件夹（null=根目录） */
    private String folderId;

    /** 是否启用（停用的文档不参与检索） */
    @Builder.Default
    private boolean enabled = true;
}
