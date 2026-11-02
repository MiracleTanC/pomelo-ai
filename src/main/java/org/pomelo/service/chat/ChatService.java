package org.pomelo.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.api.chat.vo.ChatAnswer;
import org.pomelo.api.chat.vo.ChatMessageVo;
import org.pomelo.api.chat.req.ChatRequest;
import org.pomelo.domain.chat.ChatMessageEntity;
import org.pomelo.domain.chat.ChatSession;
import org.pomelo.mapper.ChatMessageMapper;
import org.pomelo.mapper.ChatSessionMapper;
import org.pomelo.common.exception.BizException;
import org.pomelo.domain.rag.Citation;
import org.pomelo.domain.rag.RetrievedChunk;
import org.pomelo.service.rag.SelfRagOrchestrator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天服务：会话管理 + Self-RAG 编排委托 + 持久化。
 * <p>
 * 改造后编排（含流式生成）由 {@link SelfRagOrchestrator#orchestrate} 全权接管，
 * 本类只负责会话/历史/持久化，不再自行组装 messages 或调用 StreamingChatClient。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionRepo;
    private final ChatMessageMapper messageRepo;
    private final ChatHistoryService historyService;
    private final SelfRagOrchestrator selfRag;

    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional
    public ChatSession ensureSession(ChatRequest req) {
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            ChatSession existing = sessionRepo.findById(req.sessionId());
            if (existing == null) throw new BizException("会话不存在: " + req.sessionId());
            return existing;
        }
        String title = req.message();
        if (title != null && title.length() > 30) {
            title = title.substring(0, 30) + "…";
        }
        ChatSession session = ChatSession.builder()
                .kbId(req.kbId())
                .title(title == null ? "新会话" : title)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        session.setId(UUID.randomUUID().toString());
        sessionRepo.insert(session);
        return session;
    }

    /**
     * 流式问答。编排 + 流式生成由 SelfRagOrchestrator 全权接管，本方法只负责持久化。
     */
    public ChatAnswer chatStream(ChatRequest req, Consumer<String> onToken) throws Exception {
        ChatSession session = ensureSession(req);
        String sessionId = session.getId();
        String userMsg = req.message();

        // 持久化用户消息 + 写入历史
        persistMessage(sessionId, "user", userMsg, null);
        historyService.append(sessionId, "user", userMsg);

        String context = historyService.asContext(sessionId);

        // Self-RAG 编排（含流式生成）——orchestrate 通过 onToken 推送 token
        SelfRagOrchestrator.Result result = selfRag.orchestrate(req.kbId(), userMsg, context, onToken);
        String answer = result.draftAnswer() != null ? result.draftAnswer() : "";

        // 生成引用
        List<RetrievedChunk> chunks = result.contextChunks() == null
                ? List.of() : result.contextChunks();
        List<Citation> citations = chunks.stream()
                .map(c -> Citation.from(c, 120))
                .toList();

        // 持久化助手消息 + 写入历史
        String citationsJson = citations.isEmpty() ? null : mapper.writeValueAsString(citations);
        persistMessage(sessionId, "assistant", answer, citationsJson);
        historyService.append(sessionId, "assistant", answer);

        // 更新会话时间
        session.setUpdatedAt(Instant.now());
        sessionRepo.update(session);

        return new ChatAnswer(sessionId, answer, result.effectiveQuery(), citations);
    }

    public List<ChatMessageVo> history(String sessionId) {
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toVo)
                .toList();
    }

    public List<ChatSession> sessions() {
        return sessionRepo.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional
    public void deleteSession(String sessionId) {
        messageRepo.deleteBySessionId(sessionId);
        sessionRepo.deleteById(sessionId);
        historyService.clear(sessionId);
    }

    private void persistMessage(String sessionId, String role, String content, String citationsJson) {
        ChatMessageEntity msg = ChatMessageEntity.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .citations(citationsJson)
                .createdAt(Instant.now())
                .build();
        msg.setId(UUID.randomUUID().toString());
        messageRepo.insert(msg);
    }

    private ChatMessageVo toVo(ChatMessageEntity e) {
        List<Citation> citations = null;
        if (e.getCitations() != null && !e.getCitations().isBlank()) {
            try {
                citations = mapper.readValue(e.getCitations(), new TypeReference<>() {
                });
            } catch (Exception ex) {
                log.warn("解析引用 JSON 失败: {}", ex.getMessage());
            }
        }
        return new ChatMessageVo(e.getId(), e.getSessionId(), e.getRole(),
                e.getContent(), citations, e.getCreatedAt());
    }
}
