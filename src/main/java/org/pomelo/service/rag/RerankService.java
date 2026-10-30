package org.pomelo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.domain.rag.RetrievedChunk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 重排：以 qwen2.5:7b 作为判官，对候选片段打分后取 Top-K。
 * 打分格式：每行 "序号|分数"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    private final LlmJudge judge;

    @Value("${pomelo.rag.rerank-top-k}")
    private int rerankTopK;

    private static final Pattern SCORE_LINE = Pattern.compile("(\\d+)\\s*\\|\\s*(\\d+(?:\\.\\d+)?)");

    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        String prompt = PromptTemplates.rerank(query, candidates);
        String resp;
        try {
            resp = judge.ask(prompt);
        } catch (Exception e) {
            log.warn("重排 LLM 调用失败，按原顺序截断: {}", e.getMessage());
            return candidates.size() > rerankTopK ? candidates.subList(0, rerankTopK) : candidates;
        }
        List<RetrievedChunk> scored = new ArrayList<>();
        Matcher m = SCORE_LINE.matcher(resp);
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                double score = Double.parseDouble(m.group(2));
                if (idx >= 1 && idx <= candidates.size()) {
                    RetrievedChunk c = candidates.get(idx - 1);
                    scored.add(RetrievedChunk.of(c.chunkId(), c.docId(), c.docName(),
                            c.kbId(), c.page(), c.chunkIndex(), c.text(), (float) score));
                }
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的行
            }
        }
        if (scored.isEmpty()) {
            // LLM 未按格式输出，回退原顺序
            log.warn("重排未解析到有效分数，回退原顺序");
            return candidates.size() > rerankTopK ? candidates.subList(0, rerankTopK) : candidates;
        }
        scored.sort(Comparator.comparingDouble((RetrievedChunk c) -> c.score()).reversed());
        return scored.size() > rerankTopK ? scored.subList(0, rerankTopK) : scored;
    }
}
