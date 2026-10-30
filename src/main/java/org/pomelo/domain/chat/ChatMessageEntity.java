package org.pomelo.domain.chat;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageEntity {

    private String id;

    private String sessionId;

    /** user / assistant */
    private String role;

    private String content;

    /** 引用来源 JSON（assistant 消息） */
    private String citations;

    private Instant createdAt = Instant.now();
}
