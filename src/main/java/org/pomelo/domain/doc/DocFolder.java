package org.pomelo.domain.doc;

import lombok.*;

import java.time.Instant;

/**
 * 知识库内文件夹目录树（闭包模型存储父子关系）。
 * 文档可放入文件夹，也可不归类（folder_id 为 null 表示根目录）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocFolder {

    private String id;

    private String kbId;

    private String parentId;

    private String name;

    @Builder.Default
    private int sortOrder = 0;

    private Instant createdAt = Instant.now();
}
