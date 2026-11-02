package org.pomelo.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.LlmResult;
import org.pomelo.core.llm.ToolCall;
import org.pomelo.core.llm.ToolContext;
import org.pomelo.core.llm.ToolRegistry;
import org.pomelo.core.ollama.StreamingChatClient;
import org.pomelo.domain.rag.RetrievedChunk;
import org.pomelo.service.rag.tools.SearchKbTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Self-RAG 编排器（工具调用驱动版）。
 * <p>
 * <b>主路径——Agent 循环（原生 function calling）</b>：
 * 模型自主决定何时调用 {@code search_kb} 工具检索，检索结果不好时自己改写 query 再查，
 * 满意后直接生成答案。编排器只负责：下发 tools → 流式对话 → 执行 tool_calls → 回填结果 → 循环。
 * <p>
 * <b>降级路径——静态编排（改造点 4 逻辑）</b>：
 * 当工具调用被关闭、或 Agent 循环出错时，回退到 Java 写死的 Self-RAG 流程（改写→必要性→
 * 检索→相关性→重排→草稿→支撑性），并自行完成流式生成。
 * <p>
 * 两条路径都接管"从问题到答案"的全流程（含流式生成），调用方（ChatService）只需持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfRagOrchestrator {

    private final LlmJudge judge;
    private final RetrievalService retrievalService;
    private final RerankService rerankService;
    private final StreamingChatClient streamingClient;
    private final ToolRegistry toolRegistry;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${pomelo.rag.max-iterations}")
    private int maxIterations;

    @Value("${pomelo.llm.provider}")
    private String provider;

    @Value("${pomelo.rag.tool-calling.enabled:true}")
    private boolean toolCallingEnabled;

    private boolean isOllama() {
        return "ollama".equalsIgnoreCase(provider);
    }

    // ==================== 公开入口 ====================

    /**
     * 执行 Self-RAG（含流式生成）。
     * <p>
     * 优先走 Agent 循环（工具调用），失败则降级到静态编排。两条路径都将最终答案通过
     * {@code onToken} 流式推送，并在 {@link Result#draftAnswer()} 返回完整文本。
     *
     * @param kbId                知识库 ID
     * @param userQuery           用户原始问题
     * @param conversationContext 多轮对话历史文本
     * @param onToken             流式 token 回调
     * @return 编排结果（含完整答案 + 检索片段 + 生效查询）
     */
    public Result orchestrate(String kbId, String userQuery, String conversationContext,
                              Consumer<String> onToken) {
        if (toolCallingEnabled && !toolRegistry.specs().isEmpty()) {
            try {
                return orchestrateWithTools(kbId, userQuery, conversationContext, onToken);
            } catch (Exception e) {
                log.warn("Agent 循环失败，降级到静态编排: {}", e.getMessage());
            }
        }
        return orchestrateLegacy(kbId, userQuery, conversationContext, onToken);
    }

    // ==================== 主路径：Agent 循环（原生 function calling） ====================

    /**
     * Agent 循环：模型自主编排检索与生成。
     * <pre>
     * 每轮：
     *   ① streamChat(messages, tools, onToken)  → LlmResult
     *   ② 无 tool_calls → 最终答案（已流式推送），返回
     *   ③ 有 tool_calls → 执行工具，回填 assistant(tool_calls) + tool(结果)，继续
     * </pre>
     */
    private Result orchestrateWithTools(String kbId, String userQuery, String conversationContext,
                                        Consumer<String> onToken) throws Exception {
        ToolContext ctx = new ToolContext(kbId, conversationContext, userQuery, new LinkedHashMap<>());

        // 构建 Agent 系统提示
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildAgentSystemPrompt(conversationContext)));
        messages.add(Map.of("role", "user", "content", userQuery));

        log.info("Agent 循环开始: query='{}' tools={}", userQuery, toolRegistry.specs().size());

        String lastContent = "";
        for (int round = 1; round <= maxIterations; round++) {
            LlmResult result = streamingClient.streamChat(messages, toolRegistry.specs(), onToken);
            lastContent = result.content();

            // 模型给出最终答案（不再调工具）→ 已流式推送，直接返回
            if (!result.hasToolCalls()) {
                log.info("Agent 循环完成（{} 轮），模型给出最终答案", round);
                List<RetrievedChunk> chunks = collectChunks(ctx);
                return new Result(!chunks.isEmpty(), chunks, userQuery, result.content());
            }

            // 模型发起工具调用 → 回填 assistant(tool_calls) 消息
            messages.add(buildAssistantToolCallMessage(result.content(), result.toolCalls()));

            // 执行每个工具调用，回填 tool 结果消息
            for (ToolCall tc : result.toolCalls()) {
                log.info("Agent 第 {} 轮调用工具: {} args={}", round, tc.name(), tc.arguments());
                String toolResult = toolRegistry.execute(tc.name(), tc.arguments(), ctx);
                messages.add(buildToolMessage(tc, toolResult));
            }
        }

        // 超过最大轮次：用最后一轮的内容作为答案（可能不完整）
        log.warn("Agent 循环超过最大轮次 {}，使用最后一轮内容", maxIterations);
        List<RetrievedChunk> chunks = collectChunks(ctx);
        return new Result(!chunks.isEmpty(), chunks, userQuery, lastContent);
    }

    // ==================== 降级路径：静态编排（改造点 4 逻辑 + 流式生成） ====================

    /**
     * 静态编排：Java 写死的 Self-RAG 流程 + 流式生成最终答案。
     * 流程：改写 → 必要性 → 检索 → 相关性 → 重排 → 草稿 → 支撑性 → 流式生成。
     */
    private Result orchestrateLegacy(String kbId, String userQuery, String conversationContext,
                                     Consumer<String> onToken) {
        // 1. Query 改写
        String rewrittenQuery = userQuery;
        try {
            String rw = judge.ask(PromptTemplates.queryRewrite(conversationContext, userQuery));
            if (rw != null && !rw.isBlank()) {
                rewrittenQuery = rw.split("\\n")[0].strip();
            }
        } catch (Exception e) {
            log.warn("Query 改写失败，使用原查询: {}", e.getMessage());
        }

        // 2. 检索必要性判断
        boolean needRetrieval;
        try {
            needRetrieval = judge.parseYesNo(judge.ask(PromptTemplates.retrievalNecessity(rewrittenQuery)));
        } catch (Exception e) {
            log.warn("检索必要性判断失败，默认检索: {}", e.getMessage());
            needRetrieval = true;
        }

        List<RetrievedChunk> finalChunks = List.of();
        if (needRetrieval) {
            // 3~7. 迭代检索
            for (int i = 1; i <= maxIterations; i++) {
                List<RetrievedChunk> candidates;
                try {
                    candidates = retrievalService.retrieve(kbId, rewrittenQuery);
                } catch (Exception e) {
                    log.error("第 {} 次检索失败: {}", i, e.getMessage());
                    break;
                }
                if (candidates.isEmpty()) {
                    log.info("第 {} 次检索无结果", i);
                    break;
                }

                // 4. 相关性判断
                boolean relevant;
                try {
                    relevant = judge.parseYesNo(judge.ask(PromptTemplates.relevance(rewrittenQuery, candidates)));
                } catch (Exception e) {
                    log.warn("相关性判断失败，默认相关: {}", e.getMessage());
                    relevant = true;
                }
                if (!relevant && i < maxIterations) {
                    log.info("第 {} 次判定不相关，尝试改写后重检索", i);
                    try {
                        String rw2 = judge.ask(PromptTemplates.queryRewrite(
                                conversationContext + "\n当前查询：" + rewrittenQuery + "检索结果不理想，请换个角度改写。",
                                userQuery));
                        if (rw2 != null && !rw2.isBlank()) {
                            rewrittenQuery = rw2.split("\\n")[0].strip();
                        }
                    } catch (Exception e) {
                        log.warn("迭代改写失败: {}", e.getMessage());
                    }
                    continue;
                }

                // 5. 重排
                List<RetrievedChunk> reranked = rerankService.rerank(rewrittenQuery, candidates);

                // 6. 预生成草稿（非流式，用于支撑性判断）
                String draftAnswer = null;
                try {
                    draftAnswer = judge.ask(PromptTemplates.generate(userQuery, conversationContext, reranked));
                } catch (Exception e) {
                    log.warn("第 {} 次草稿生成失败，跳过支撑性判断: {}", i, e.getMessage());
                }

                // 7. 支撑性判断
                if (draftAnswer != null) {
                    boolean supported;
                    try {
                        supported = judge.parseSupported(
                                judge.ask(PromptTemplates.supportCheck(userQuery, draftAnswer, reranked)));
                    } catch (Exception e) {
                        log.warn("第 {} 次支撑性判断失败，默认支撑: {}", i, e.getMessage());
                        supported = true;
                    }
                    if (!supported && i < maxIterations) {
                        log.info("第 {} 次判定答案未被证据支撑，尝试改写后重检索", i);
                        try {
                            String rw2 = judge.ask(PromptTemplates.queryRewrite(
                                    conversationContext + "\n当前查询：" + rewrittenQuery + "答案缺乏证据支撑，请换个角度改写。",
                                    userQuery));
                            if (rw2 != null && !rw2.isBlank()) {
                                rewrittenQuery = rw2.split("\\n")[0].strip();
                            }
                        } catch (Exception e) {
                            log.warn("支撑性迭代改写失败: {}", e.getMessage());
                        }
                        continue;
                    }
                }

                finalChunks = reranked;
                break;
            }
        } else {
            log.info("判定为无需检索（闲聊/常识类），直接回答");
        }

        // 流式生成最终答案
        String answer = streamGenerate(userQuery, conversationContext, finalChunks, onToken);
        return new Result(needRetrieval && !finalChunks.isEmpty(), finalChunks, rewrittenQuery, answer);
    }

    // ==================== 流式生成（降级路径用） ====================

    /** 组装 messages 并流式生成最终答案。 */
    private String streamGenerate(String userQuery, String conversationContext,
                                  List<RetrievedChunk> chunks, Consumer<String> onToken) {
        String systemPrompt;
        if (chunks != null && !chunks.isEmpty()) {
            systemPrompt = PromptTemplates.generate(userQuery, conversationContext, chunks);
        } else {
            systemPrompt = PromptTemplates.directAnswer(conversationContext, userQuery);
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userQuery));

        try {
            return streamingClient.streamChat(messages, onToken);
        } catch (Exception e) {
            log.error("流式生成失败: {}", e.getMessage());
            return "生成失败: " + e.getMessage();
        }
    }

    // ==================== Agent 循环辅助方法 ====================

    /**
     * Agent 模式的系统提示：根据已注册工具动态描述能力清单 + 使用规则。
     * 工具描述随 {@link ToolRegistry#specs()} 变化，加工具无需改本提示。
     */
    private String buildAgentSystemPrompt(String conversationContext) {
        // 动态生成工具清单概要（名称 + 一句话用途），帮助模型按问题类型选对工具
        String toolOverview = toolRegistry.specs().stream()
                .map(s -> "- " + s.name() + "：" + briefDesc(s.description()))
                .reduce("", (a, b) -> a + b + "\n");

        return """
                你是一个知识库问答助手。你可以调用以下工具：

                %s
                工具选用规则：
                1. 查文档正文内容/事实/数据 → search_kb。
                2. 查知识库有哪些文档/上传了哪些文件 → list_documents。
                3. 查知识库本身的配置/规模/参数 → get_kb_info。
                4. 查文档处理/向量化状态 → get_doc_status。
                5. 查当前时间 → get_current_time。
                6. 闲聊、打招呼、通用常识 → 直接回答，无需调用工具。
                7. 如果第一次检索结果不够，可以改写查询再次调用 search_kb。
                8. 回答中引用到的内容，在句末用 [1][2] 形式标注来源序号。
                9. 若检索片段不足以回答，请明确说明"根据现有知识库内容无法回答"，不要编造。
                10. 用中文回答，简洁准确。

                对话历史：
                %s
                """.formatted(toolOverview,
                conversationContext == null || conversationContext.isBlank()
                        ? "(无)" : conversationContext);
    }

    /** 取描述的第一句作为概要，避免 system prompt 过长。 */
    private String briefDesc(String desc) {
        if (desc == null) return "";
        int sep = desc.indexOf('。');
        return sep > 0 ? desc.substring(0, sep + 1) : desc;
    }

    /**
     * 构建 assistant 工具调用消息（回填到 messages 供下一轮对话）。
     * 兼容 Ollama（arguments 为对象）与 OpenAI（arguments 为字符串 + id + type）格式。
     */
    private Map<String, Object> buildAssistantToolCallMessage(String content, List<ToolCall> toolCalls) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content == null ? "" : content);

        List<Map<String, Object>> tcList = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Map<String, Object> tcEntry = new LinkedHashMap<>();
            // OpenAI 需要 id 和 type；Ollama 会忽略多余字段
            if (tc.id() != null) {
                tcEntry.put("id", tc.id());
            }
            tcEntry.put("type", "function");
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tc.name());
            // Ollama 要对象，OpenAI 要字符串 → 按 provider 区分
            if (isOllama()) {
                fn.put("arguments", parseArgsToObject(tc.arguments()));
            } else {
                fn.put("arguments", tc.arguments() != null ? tc.arguments() : "{}");
            }
            tcEntry.put("function", fn);
            tcList.add(tcEntry);
        }
        msg.put("tool_calls", tcList);
        return msg;
    }

    /**
     * 构建 tool 结果消息（回填到 messages 供模型继续生成）。
     * OpenAI 需要 tool_call_id；Ollama 只需 role + content。
     */
    private Map<String, Object> buildToolMessage(ToolCall tc, String toolResult) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        if (!isOllama() && tc.id() != null) {
            msg.put("tool_call_id", tc.id());
        }
        msg.put("content", toolResult);
        return msg;
    }

    /** 从 ToolContext attributes 回收检索到的片段（去重后的列表）。 */
    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> collectChunks(ToolContext ctx) {
        Map<String, RetrievedChunk> collected = ctx.get(SearchKbTool.CHUNKS_KEY);
        if (collected == null || collected.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(collected.values());
    }

    /** 将 JSON 字符串参数解析为 JsonNode（Ollama 要对象形式）。 */
    private Object parseArgsToObject(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(argsJson);
        } catch (Exception e) {
            log.warn("解析工具参数失败，使用空对象: {}", argsJson);
            return mapper.createObjectNode();
        }
    }

    // ==================== 结果记录 ====================

    /**
     * Self-RAG 编排结果。
     *
     * @param retrievalUsed  是否使用了检索（false 表示闲聊直接回答）
     * @param contextChunks  用于生成答案的检索片段（重排后），可能为空
     * @param effectiveQuery 实际生效的查询
     * @param draftAnswer    最终答案完整文本（已通过 onToken 流式推送）
     */
    public record Result(
            boolean retrievalUsed,
            List<RetrievedChunk> contextChunks,
            String effectiveQuery,
            String draftAnswer
    ) {
    }
}
