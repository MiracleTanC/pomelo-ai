package org.pomelo.api.rag;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.pomelo.common.api.Result;
import org.pomelo.domain.rag.RetrievedChunk;
import org.pomelo.service.rag.RetrievalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 检索测试接口：独立调试检索效果，可调 Top-K 和相似度阈值。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class SearchTestController {

    private final RetrievalService retrievalService;

    @PostMapping("/search-test")
    public Result<List<SearchResultVo>> searchTest(@RequestBody SearchTestRequest req) {
        List<RetrievedChunk> hits = retrievalService.retrieveForTest(
                req.kbId, req.message, req.topK, req.similarityThreshold);
        return Result.ok(hits.stream().map(SearchResultVo::from).toList());
    }

    // ---- DTO ----

    @Data
    public static class SearchTestRequest {
        private String kbId;
        private String message;
        private Integer topK = 8;
        private Double similarityThreshold = 0.3;
    }

    public record SearchResultVo(
            String chunkId, String docId, String docName,
            int page, int chunkIndex, String text, float score
    ) {
        static SearchResultVo from(RetrievedChunk c) {
            return new SearchResultVo(c.chunkId(), c.docId(), c.docName(),
                    c.page(), c.chunkIndex(), c.text(), c.score());
        }
    }
}
