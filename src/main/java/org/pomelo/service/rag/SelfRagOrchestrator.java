package org.pomelo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.rag.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Self-RAG 编排器。
 * <p>
 * 流程（带迭代，max-iterations 上限）：
 * 1. Query 改写：结合多轮历史改写为独立检索查询。
 * 2. 检索必要性判断：闲聊类问题直接回答，不检索。
 * 3. 向量检索 Top-K。
 * 4. 相关性判断：相关才继续，否则（尝试改写或返回兜底）。
 * 5. LLM 重排打分，取 Top-K 进入上下文。
 * 6. 生成答案（要求标注引用序号）。
 * 7. 支撑性判断：若 unsupported 且仍有迭代次数，则重新检索；否则返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SelfRagOrchestrator {

    private final LlmJudge judge;
    private final RetrievalService retrievalService;
    private final RerankService rerankService;

    @Value("${pomelo.rag.max-iterations}")
    private int maxIterations;

    /**
     * 执行 Self-RAG，返回最终用于生成答案的检索片段（重排后）。
     * 生成步骤由调用方（ChatService）负责，以便复用流式输出。
     *
     * @return 上下文片段；可能为空（表示直接回答/无可用片段）。
     */
    public Result orchestrate(String kbId, String userQuery, String conversationContext) {
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
        if (!needRetrieval) {
            log.info("判定为无需检索（闲聊/常识类），直接回答");
            return new Result(false, null, userQuery);
        }

        // 3~7. 迭代检索
        for (int i = 1; i <= maxIterations; i++) {
            List<RetrievedChunk> candidates;
            try {
                candidates = retrievalService.retrieve(kbId, rewrittenQuery);
            } catch (Exception e) {
                log.error("第 {} 次检索失败: {}", i, e.getMessage());
                return new Result(true, List.of(), rewrittenQuery);
            }
            if (candidates.isEmpty()) {
                log.info("第 {} 次检索无结果", i);
                return new Result(true, List.of(), rewrittenQuery);
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
                // 尝试改写查询后重检索
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
            return new Result(true, reranked, rewrittenQuery);
        }

        // 迭代耗尽
        return new Result(true, List.of(), rewrittenQuery);
    }

    /**
     * Self-RAG 编排结果。
     */
    public record Result(
            boolean retrievalUsed,
            List<RetrievedChunk> contextChunks,
            String effectiveQuery
    ) {
    }
}
