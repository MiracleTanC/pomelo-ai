package org.pomelo.service.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史管理（Redis）：滑动窗口保留最近若干轮，超长时截断以压缩上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private static final int MAX_MESSAGES = 20;
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    private String key(String sessionId) {
        return "pomelo:chat:history:" + sessionId;
    }

    public void append(String sessionId, String role, String content) {
        try {
            List<HistoryEntry> list = load(sessionId);
            list.add(new HistoryEntry(role, content));
            // 滑动窗口：保留最近 MAX_MESSAGES 条
            if (list.size() > MAX_MESSAGES) {
                list = new ArrayList<>(list.subList(list.size() - MAX_MESSAGES, list.size()));
            }
            redis.opsForValue().set(key(sessionId), mapper.writeValueAsString(list), TTL);
        } catch (Exception e) {
            log.warn("写入会话历史失败: {}", e.getMessage());
        }
    }

    public List<HistoryEntry> load(String sessionId) {
        try {
            String json = redis.opsForValue().get(key(sessionId));
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("读取会话历史失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void clear(String sessionId) {
        redis.delete(key(sessionId));
    }

    /** 把历史拼成纯文本上下文，供 prompt 使用。 */
    public String asContext(String sessionId) {
        List<HistoryEntry> list = load(sessionId);
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (HistoryEntry e : list) {
            sb.append("user".equals(e.role) ? "用户：" : "助手：")
                    .append(e.content).append("\n");
        }
        return sb.toString();
    }

    public record HistoryEntry(String role, String content) {
    }
}
