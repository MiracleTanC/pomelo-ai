package org.pomelo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.pomelo.domain.chat.ChatSession;

@Mapper
public interface ChatSessionMapper {

    ChatSession findById(String id);

    List<ChatSession> findAllByOrderByUpdatedAtDesc();

    int insert(ChatSession session);

    int update(ChatSession session);

    void deleteById(String id);
}
