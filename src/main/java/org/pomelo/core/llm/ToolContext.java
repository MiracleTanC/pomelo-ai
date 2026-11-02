package org.pomelo.core.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行的会话级上下文。
 * <p>
 * 携带一次 Agent 循环中固定不变的会话信息，避免每个工具都把这些当 arguments 参数
 * （模型不应每次都输出 kbId，且对话历史不适合作为工具参数序列化）。
 *
 * @param kbId                当前知识库 ID（检索类工具依赖）
 * @param conversationContext 多轮对话历史文本（改写类工具可参考）
 * @param userQuery           用户原始问题（用于生成/判断时的锚点）
 * @param attributes          可变属性袋（工具可往里塞执行产出，供编排器回收）
 */
public record ToolContext(
        String kbId,
        String conversationContext,
        String userQuery,
        Map<String, Object> attributes
) {
    /** 便捷构造：仅 kbId（无对话历史的场景），attributes 自动创建空 map。 */
    public static ToolContext of(String kbId) {
        return new ToolContext(kbId, null, null, new HashMap<>());
    }

    /**
     * 完整构造（attributes 为 null 时自动创建空 map，保证非空）。
     */
    public ToolContext(String kbId, String conversationContext, String userQuery, Map<String, Object> attributes) {
        this.kbId = kbId;
        this.conversationContext = conversationContext;
        this.userQuery = userQuery;
        this.attributes = attributes != null ? attributes : new HashMap<>();
    }

    /** 往属性袋塞一个值。 */
    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    /** 从属性袋取一个值。 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }
}
