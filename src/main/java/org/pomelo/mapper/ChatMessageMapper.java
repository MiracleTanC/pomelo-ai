package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.pomelo.domain.chat.ChatMessageEntity;

@Mapper
public interface ChatMessageMapper {

    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    int insert(ChatMessageEntity message);

    void deleteBySessionId(String sessionId);

    void deleteById(String id);
}
