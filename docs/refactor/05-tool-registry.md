# 改造点 5：建立统一 ToolRegistry，工具注册与调度

> 5 个改造点执行顺序：**改造点2 → 改造点4 → 改造点3 → 改造点5 → 改造点1**
> 本文档：第 4 步（改造点 5）

## 一、问题背景

改造点 3 让 LLM 客户端"具备"了传 tools、解析 tool_calls 的能力，但**尚无工具可传、无调度机制**。
现状检索/重排逻辑硬编码在 `SelfRagOrchestrator` 里，加一个新工具要改编排器主流程。

本改造建立统一 `ToolRegistry`：
- 定义统一 `Tool` 接口（name + description + parameters schema + execute）
- 所有工具 Bean 自动注册，编排器通过 registry 动态获取 specs + 执行
- 把现有 `RetrievalService.retrieve` + `RerankService.rerank` 封装成 `search_kb` 工具

这是连接"客户端能力（改造点 3）"和"编排器使用（改造点 1）"的桥梁。

## 二、设计要点

### 2.1 为什么需要 ToolContext

工具执行需要会话级信息（kbId、对话历史等）。两种放法对比：

| 方案 | 做法 | 问题 |
|------|------|------|
| A：全做工具参数 | kbId 放 arguments，模型每次输出 | 模型啰嗦、可能出错、对话历史不适合序列化 |
| B：会话上下文 ✅ | kbId 等放 ToolContext，工具参数只放 query | 模型只需输出变化的参数，干净 |

采用 B：`ToolContext(kbId, conversationContext, userQuery)` 在 Agent 循环开始时构建，传给 `registry.execute`。

### 2.2 自动注册机制

```java
@Component
public class ToolRegistry {
    public ToolRegistry(List<Tool> registered) {   // Spring 注入所有 Tool Bean
        for (Tool t : registered) tools.put(t.name(), t);
    }
}
```
新增工具只需：实现 `Tool` 接口 + 加 `@Component`，**无需改 Registry 或编排器**。

### 2.3 分层合规

```
core/llm/Tool.java            ← 接口（core 层）
core/llm/ToolContext.java     ← 上下文（core 层）
core/llm/ToolRegistry.java    ← 注册表（core 层，依赖 Tool 接口）
service/rag/tools/SearchKbTool.java  ← 具体工具（service 层，依赖 RetrievalService）
```
依赖方向：service → core（向下），符合 README 的"依赖方向单一向下"。

## 三、现状实现（改造前）

```java
// SelfRagOrchestrator.orchestrate() —— 调用硬编码
boolean needRetrieval = judge.parseYesNo(judge.ask(retrievalNecessity));  // 判断
List<RetrievedChunk> candidates = retrievalService.retrieve(kbId, query); // 直接调
boolean relevant = judge.parseYesNo(judge.ask(relevance));                // 判断
List<RetrievedChunk> reranked = rerankService.rerank(query, candidates);  // 直接调
```

**问题**：
- 检索/重排是编排器直接调 Service，没有"工具"抽象
- 加新能力（如查元数据、跨库检索）要改 orchestrate() 主流程
- 无法被原生 function calling 复用（改造点 3 的能力闲置）

## 四、改造后实现

### 4.1 Tool 接口（core/llm）

```java
public interface Tool {
    String name();                          // 工具名（模型引用）
    String description();                   // 描述（模型理解何时调用）
    JsonNode parameters();                  // 参数 JSON Schema
    String execute(String argumentsJson, ToolContext ctx);  // 执行，返回结果文本
}
```

### 4.2 ToolContext（core/llm）

```java
public record ToolContext(String kbId, String conversationContext, String userQuery) {
    public static ToolContext of(String kbId);  // 便捷构造
}
```

### 4.3 ToolRegistry（core/llm）

```java
@Slf4j
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    // Spring 自动注入所有 Tool Bean
    public ToolRegistry(List<Tool> registered) {
        for (Tool t : registered) tools.put(t.name(), t);
        log.info("ToolRegistry 注册 {} 个工具: {}", tools.size(), tools.keySet());
    }

    public Tool get(String name);              // 按名获取
    public List<ToolSpec> specs();             // 导出 specs（下发给模型）
    public ToolSpec spec(String name);         // 单个 spec

    // 执行（异常捕获转文本，不中断 Agent 循环）
    public String execute(String name, String argsJson, ToolContext ctx) {
        Tool tool = tools.get(name);
        if (tool == null) return "错误：未知工具 '" + name + "'...";
        try {
            return tool.execute(argsJson, ctx);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();  // 回填给模型，让其自适应
        }
    }
}
```

### 4.4 SearchKbTool（service/rag/tools）

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKbTool implements Tool {
    private final RetrievalService retrievalService;
    private final RerankService rerankService;

    public String name() { return "search_kb"; }
    public String description() {
        return "从当前知识库检索相关文档片段，返回重排后的 Top-K。"
             + "需要事实/数据/文档内容时调用；闲聊无需调用。";
    }
    public JsonNode parameters() { /* {query: string, required} */ }

    public String execute(String argumentsJson, ToolContext ctx) {
        String query = parseQuery(argumentsJson);
        // 1. 混合检索（向量+BM25+RRF）
        List<RetrievedChunk> candidates = retrievalService.retrieve(ctx.kbId(), query);
        // 2. LLM 重排（失败降级原顺序）
        List<RetrievedChunk> reranked = rerankService.rerank(query, candidates);
        // 3. 格式化输出（带序号，供模型引用 [1][2]）
        return formatChunks(reranked);
    }
}
```

## 五、改造前后对比总结

| 维度 | 现状 | 改造后 |
|------|------|--------|
| 工具抽象 | 无（编排器直接调 Service） | 统一 `Tool` 接口 + `ToolRegistry` |
| 检索能力复用 | 仅编排器内部用 | 封装为 `search_kb` 工具，可被 function calling 用 |
| 新增工具成本 | 改编排器主流程 | 实现 `Tool` + `@Component`，自动注册 |
| 会话上下文传递 | 编排器方法参数层层传 | `ToolContext` 统一携带 |
| 工具失败处理 | 编排器 try-catch 各自处理 | Registry 统一捕获转文本，不中断循环 |
| 对编排器影响 | — | 无（本改造不改编排器，改造点 1 才接入） |

## 六、改造点 1 将如何使用（预览）

```java
// 改造点 1 的 Agent 循环（预览，本改造不实现）
ToolContext ctx = new ToolContext(kbId, conversationContext, userQuery);
List<ToolSpec> tools = registry.specs();                        // 导出工具描述
LlmResult result = chatModel.chatWithTools(messages, tools);    // 改造点3的能力
if (result.hasToolCalls()) {
    for (ToolCall tc : result.toolCalls()) {
        String toolResult = registry.execute(tc.name(), tc.arguments(), ctx);  // 调度执行
        messages.add(toolMessage(tc.id(), toolResult));         // 回填结果
    }
    // 继续下一轮 chatWithTools...
}
```

## 七、改动文件清单

| 文件 | 改动内容 |
|------|---------|
| `core/llm/Tool.java` | **新增**：统一工具接口 |
| `core/llm/ToolContext.java` | **新增**：会话级上下文 record |
| `core/llm/ToolRegistry.java` | **新增**：注册表（自动发现 + specs 导出 + execute 调度） |
| `service/rag/tools/SearchKbTool.java` | **新增**：封装 retrieve + rerank 为 `search_kb` 工具 |

## 八、风险评估

- **影响范围**：纯新增，不改动任何现有文件（编排器/客户端/Service 均不变）
- **向后兼容**：ToolRegistry 是新 Bean，SearchKbTool 是新 Bean，对现有流程零影响
- **自动注册**：Spring 注入 `List<Tool>`，无 Tool Bean 时为空列表（不报错），有则自动注册
- **未接线**：本改造只建框架 + 封装工具，尚无调用方（改造点 1 接入）
- **分层合规**：Tool 接口在 core，SearchKbTool 在 service，依赖方向向下
