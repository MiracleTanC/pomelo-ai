package org.pomelo.domain.chat;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSession {

    private String id;

    private String kbId;

    private String title;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();
}
