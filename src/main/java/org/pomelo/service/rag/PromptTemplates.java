package org.pomelo.service.rag;

import org.pomelo.domain.rag.RetrievedChunk;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Self-RAG 与重排用提示词模板。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /** Query 改写：多轮/口语化查询改写为独立、利于检索的查询。 */
    public static String queryRewrite(String conversationContext, String userQuery) {
        return """
                你是一个查询改写助手。请根据对话历史，将用户最新的问题改写为一个独立、完整、利于向量检索的查询语句。
                要求：只输出改写后的查询，不要解释，不要加引号。

                对话历史：
                %s

                用户最新问题：%s

                改写后的查询：
                """.formatted(
                conversationContext == null || conversationContext.isBlank() ? "(无)" : conversationContext,
                userQuery);
    }

    /** 检索必要性判断：返回 yes 或 no。 */
    public static String retrievalNecessity(String userQuery) {
        return """
                判断以下用户问题是否需要从知识库检索文档才能回答。
                - 需要事实/数据/特定文档内容 → yes
                - 闲聊、打招呼、通用常识、对回答本身提问 → no
                只输出 yes 或 no。

                用户问题：%s
                """.formatted(userQuery);
    }

    /** 相关性判断：给定检索片段，判断是否与问题相关，输出 yes 或 no。 */
    public static String relevance(String userQuery, List<RetrievedChunk> chunks) {
        return """
                判断以下检索片段是否与用户问题相关、可用于回答。
                只输出 yes 或 no。

                用户问题：%s

                检索片段：
                %s
                """.formatted(userQuery, formatChunks(chunks));
    }

    /** 答案支撑性判断：判断答案是否被检索片段支撑（是否需要重新检索）。返回 supported 或 unsupported。 */
    public static String supportCheck(String userQuery, String answer, List<RetrievedChunk> chunks) {
        return """
                判断以下回答是否被提供的检索片段所支撑（事实正确，无幻觉）。
                只输出 supported 或 unsupported。

                用户问题：%s

                回答：%s

                检索片段：
                %s
                """.formatted(userQuery, answer, formatChunks(chunks));
    }

    /**
     * 重排打分（LLM-as-judge）：对候选片段按与查询的相关性打分。
     * 要求模型对每个片段输出一行：id<TAB>score(0-100)
     */
    public static String rerank(String userQuery, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append(i + 1).append("|\t").append(chunks.get(i).text()).append("\n");
        }
        return """
                你是一个重排打分器。请根据与用户问题的相关性，对下列每个片段打分（0-100，越高越相关）。
                严格按如下格式逐行输出，不要任何额外文字：
                序号|分数

                用户问题：%s

                片段：
                %s
                """.formatted(userQuery, sb);
    }

    /** 最终生成回答：基于检索片段 + 历史生成答案，并要求标注引用序号。 */
    public static String generate(String userQuery, String conversationContext, List<RetrievedChunk> chunks) {
        return """
                你是一个知识库问答助手。请仅依据下方提供的检索片段回答用户问题。
                规则：
                1. 若检索片段不足以回答，请明确说明"根据现有知识库内容无法回答"，不要编造。
                2. 回答中引用到的内容，在句末用 [1][2] 形式标注来源序号。
                3. 用中文回答，简洁准确。

                检索片段：
                %s

                对话历史：
                %s

                用户问题：%s

                回答：
                """.formatted(formatNumberedChunks(chunks),
                conversationContext == null || conversationContext.isBlank() ? "(无)" : conversationContext,
                userQuery);
    }

    /** 不需检索时的直接回答。 */
    public static String directAnswer(String conversationContext, String userQuery) {
        return """
                你是一个知识库问答助手。请回答用户问题。用中文。

                对话历史：
                %s

                用户问题：%s

                回答：
                """.formatted(conversationContext == null || conversationContext.isBlank() ? "(无)" : conversationContext,
                userQuery);
    }

    static String formatChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                    .append(chunks.get(i).text())
                    .append("\n---\n");
        }
        return sb.toString();
    }

    static String formatNumberedChunks(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] (文档: ")
                    .append(c.docName())
                    .append(", 页: ").append(c.page())
                    .append(") ")
                    .append(c.text())
                    .append("\n---\n");
        }
        return sb.toString();
    }
}
