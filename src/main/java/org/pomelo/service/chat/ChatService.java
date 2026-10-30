package org.pomelo.service.chat;
import org.pomelo.core.ollama.OllamaStreamingClient;

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
import org.pomelo.service.rag.PromptTemplates;
import org.pomelo.service.rag.SelfRagOrchestrator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 聊天服务：编排 Self-RAG + 多轮历史 + 流式生成 + 引用来源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionRepo;
    private final ChatMessageMapper messageRepo;
    private final ChatHistoryService historyService;
    private final SelfRagOrchestrator selfRag;
    private final OllamaStreamingClient streamingClient;

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
     * 流式问答。生成完成后返回完整答案与引用（同时持久化）。
     */
    public ChatAnswer chatStream(ChatRequest req, Consumer<String> onToken) throws Exception {
        ChatSession session = ensureSession(req);
        String sessionId = session.getId();
        String userMsg = req.message();

        // 持久化用户消息 + 写入历史
        persistMessage(sessionId, "user", userMsg, null);
        historyService.append(sessionId, "user", userMsg);

        String context = historyService.asContext(sessionId);

        // Self-RAG 编排
        SelfRagOrchestrator.Result result = selfRag.orchestrate(req.kbId(), userMsg, context);
        List<RetrievedChunk> chunks = result.contextChunks() == null
                ? List.of() : result.contextChunks();
        List<Citation> citations = chunks.stream()
                .map(c -> Citation.from(c, 120))
                .toList();

        // 组装 LLM messages（system + 历史 + 当前）
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt;
        if (result.retrievalUsed() && !chunks.isEmpty()) {
            systemPrompt = PromptTemplates.generate(userMsg, context, chunks);
        } else {
            systemPrompt = PromptTemplates.directAnswer(context, userMsg);
        }
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMsg));

        // 流式生成
        StringBuilder answer = new StringBuilder();
        String full = streamingClient.streamChat(messages, onToken);
        answer.append(full);

        // 持久化助手消息 + 写入历史
        String citationsJson = citations.isEmpty() ? null : mapper.writeValueAsString(citations);
        persistMessage(sessionId, "assistant", answer.toString(), citationsJson);
        historyService.append(sessionId, "assistant", answer.toString());

        // 更新会话时间
        session.setUpdatedAt(Instant.now());
        sessionRepo.update(session);

        return new ChatAnswer(sessionId, answer.toString(), result.effectiveQuery(), citations);
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
