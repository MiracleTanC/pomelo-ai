package org.pomelo.api.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.api.chat.vo.ChatAnswer;
import org.pomelo.api.chat.vo.ChatMessageVo;
import org.pomelo.api.chat.req.ChatRequest;
import org.pomelo.domain.chat.ChatSession;
import org.pomelo.service.chat.ChatService;
import org.pomelo.common.api.Result;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.pomelo.api.chat.req.ChatRequest;
import org.pomelo.api.chat.vo.ChatAnswer;
import org.pomelo.api.chat.vo.ChatMessageVo;

/**
 * 聊天控制器：普通问答 + SSE 流式问答。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * SSE 流式问答。
     * 事件流：token（逐 token）、done（最终答案+引用）、error。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest req) {
        // 超时设长一点，覆盖流式生成时长
        SseEmitter emitter = new SseEmitter(0L);

        new Thread(() -> {
            try {
                ChatAnswer answer = chatService.chatStream(req, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (IOException e) {
                        // 客户端断开等
                        log.debug("SSE 发送 token 失败: {}", e.getMessage());
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(mapper.writeValueAsString(answer)));
                emitter.complete();
            } catch (Exception e) {
                log.error("流式问答失败", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            Map.of("message", "问答失败: " + e.getMessage())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        }, "chat-stream").start();

        return emitter;
    }

    /**
     * 非流式问答（直接返回完整答案）。
     */
    @PostMapping("/ask")
    public Result<ChatAnswer> ask(@Valid @RequestBody ChatRequest req) throws Exception {
        return Result.ok(chatService.chatStream(req, t -> {
        }));
    }

    @GetMapping("/sessions")
    public Result<List<ChatSession>> sessions() {
        return Result.ok(chatService.sessions());
    }

    @GetMapping("/history/{sessionId}")
    public Result<List<ChatMessageVo>> history(@PathVariable String sessionId) {
        return Result.ok(chatService.history(sessionId));
    }

    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return Result.ok();
    }
}
