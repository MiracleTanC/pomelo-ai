package org.pomelo.domain.doc;

import lombok.*;

import java.time.Instant;

/**
 * 文档标签（多对多：一个文档可打多个标签，一个标签可关联多个文档）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocTag {

    private String id;

    private String kbId;

    private String docId;

    private String tag;

    private Instant createdAt = Instant.now();
}
