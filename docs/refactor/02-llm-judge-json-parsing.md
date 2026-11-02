# 改造点 2：LlmJudge 字符串解析 → JSON 结构化解析

> 5 个改造点执行顺序：**改造点2 → 改造点4 → 改造点3 → 改造点5 → 改造点1**
> 本文档：第 1 步（改造点 2）

## 一、问题背景

`LlmJudge` 是 Self-RAG 各判断步骤（检索必要性、相关性、支撑性）的薄封装。
现状通过**字符串关键词匹配**解析 LLM 输出，存在误判风险。

## 二、现状实现

### 2.1 PromptTemplates —— 要求模型"只输出 yes 或 no"

`PromptTemplates.retrievalNecessity`：
```
判断以下用户问题是否需要从知识库检索文档才能回答。
- 需要事实/数据/特定文档内容 → yes
- 闲聊、打招呼、通用常识、对回答本身提问 → no
只输出 yes 或 no。

用户问题：%s
```
`relevance`、`supportCheck` 类似，分别要求"只输出 yes 或 no" / "只输出 supported 或 unsupported"。

### 2.2 LlmJudge —— 字符串 contains 匹配

```java
public boolean parseYesNo(String text) {
    if (text == null) return false;
    String t = text.toLowerCase();
    return t.contains("yes") || t.contains("是");
}

public boolean parseSupported(String text) {
    if (text == null) return false;
    return text.toLowerCase().contains("support")
        && !text.toLowerCase().contains("unsupport");
}
```

### 2.3 误判场景分析

| 场景 | 模型实际输出 | 现状结果 | 期望 | 问题 |
|------|-------------|---------|------|------|
| 1 | `yes` | ✅ true | true | 正常 |
| 2 | `No` | ✅ false | false | 正常 |
| 3 | `not yes` | ❌ **true** | false | 含 "yes" 误判 |
| 4 | `是不是需要检索呢` | ❌ **true** | ? | 含"是"误判 |
| 5 | `Yes, because the question needs facts` | ✅ true | true | 碰巧正确，但格式不可控 |
| 6 | `This is supported by chunk [2]` | ✅ true | true | 碰巧正确 |

核心问题：
- **无结构约束**：模型自由发挥，输出格式不可控
- **contains 匹配脆弱**：否定词、中文混合、解释性文字导致误判
- **零容错**：解析失败直接返回 false，无降级策略
- **无日志**：误判无从追溯

## 三、改造后实现

### 3.1 PromptTemplates —— 要求模型输出 JSON

```
判断以下用户问题是否需要从知识库检索文档才能回答。
- 需要事实/数据/特定文档内容 → yes
- 闲聊、打招呼、通用常识、对回答本身提问 → no
只输出 JSON，格式严格如下，不要任何额外文字：
{"answer": "yes" 或 "no"}

用户问题：%s
```
`relevance` → `{"answer": "yes" 或 "no"}`
`supportCheck` → `{"answer": "supported" 或 "unsupported"}`

### 3.2 LlmJudge —— JSON 优先 + 关键词 fallback

```java
public boolean parseYesNo(String text) {
    if (text == null) return false;
    String answer = extractJsonField(text, "answer");  // ① 先解析 JSON
    if (answer != null) {
        return "yes".equalsIgnoreCase(answer);
    }
    // ② JSON 解析失败 → fallback 关键词匹配（与现状一致，绝不比现状差）
    log.debug("JSON 解析失败，回退关键词匹配: {}", text);
    String t = text.toLowerCase();
    return t.contains("yes") || t.contains("是");
}

private String extractJsonField(String text, String field) {
    // 1. 直接解析整段为 JSON
    // 2. 从文本中提取 {...} 片段再解析（兼容模型加前后文字）
    // 3. 都失败返回 null，触发 fallback
}
```

### 3.3 改造后解析效果

| 场景 | 模型实际输出 | 改造后结果 | 期望 | 说明 |
|------|-------------|-----------|------|------|
| 1 | `{"answer": "yes"}` | ✅ true | true | JSON 精确匹配 |
| 2 | `好的：{"answer": "no"}` | ✅ false | false | 提取 {...} 片段 |
| 3 | `not yes`（无 JSON） | ⚠️ fallback true | false | 降级到原逻辑，不比现状差 |
| 4 | `{"answer": "yes", "reason": "需要"}` | ✅ true | true | JSON 多字段兼容 |
| 5 | `yes`（老格式） | ⚠️ fallback true | true | 降级，仍可用 |

## 四、改造前后对比总结

| 维度 | 现状 | 改造后 |
|------|------|--------|
| 输出格式约束 | "只输出 yes 或 no"（自然语言） | `{"answer": "yes"}`（JSON 结构） |
| 解析方式 | `contains` 关键词匹配 | JSON 反序列化优先 |
| 容错策略 | 无（失败返回 false） | JSON 失败 → fallback 关键词匹配 |
| 否定词误判 | 常见（"not yes" 误判） | JSON 模式下消除 |
| 中文混合误判 | 常见（"是不是" 误判） | JSON 模式下消除 |
| 模型输出额外文字 | 破坏解析 | 提取 {...} 片段兼容 |
| 可观测性 | 无日志 | 解析失败有 debug 日志 |
| 向后兼容 | — | fallback 保证老格式仍可用 |

## 五、改动文件清单

| 文件 | 改动内容 |
|------|---------|
| `service/rag/PromptTemplates.java` | `retrievalNecessity` / `relevance` / `supportCheck` 三个 prompt 改为要求输出 JSON |
| `service/rag/LlmJudge.java` | 新增 `ObjectMapper` + `extractJsonField`；`parseYesNo` / `parseSupported` 改为 JSON 优先 + fallback |

## 六、风险评估

- **影响范围**：仅解析层，不改变 Self-RAG 编排流程
- **向后兼容**：fallback 机制保证模型未按 JSON 格式输出时，行为与现状一致
- **无破坏性变更**：方法签名不变，调用方（SelfRagOrchestrator）无需改动
