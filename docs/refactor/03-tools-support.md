# 改造点 3：OllamaNativeChatModel / StreamingChatClient 增加 tools 支持

> 5 个改造点执行顺序：**改造点2 → 改造点4 → 改造点3 → 改造点5 → 改造点1**
> 本文档：第 3 步（改造点 3）

## 一、问题背景

现状两个 LLM 客户端**完全不支持原生 function calling**：
- `OllamaNativeChatModel`：请求体只塞 `model/messages/options`，无 `tools` 字段；响应只读 `message.content`，忽略 `tool_calls`
- `StreamingChatClient`：请求体同样无 `tools`；响应解析只取文本 token，忽略 `tool_calls`

这导致项目无法使用原生工具调用，只能靠提示词手搓（改造点 1 要做的事现在做不了）。本改造为两个客户端**补齐"传 tools 参数、解析 tool_calls 响应"的能力**，为改造点 1（工具调用驱动的 Agent 循环）打基础。

## 二、协议差异（Ollama vs OpenAI）

理解差异是本改造的核心，因为两套协议在 tools 上有微妙不同：

| 维度 | Ollama `/api/chat` | OpenAI `/chat/completions` |
|------|--------------------|-----------------------------|
| tools 请求格式 | `[{type:function, function:{name,description,parameters}}]` | 相同 ✅ |
| 非流式响应 tool_calls 位置 | `message.tool_calls[]` | `choices[0].message.tool_calls[]` |
| arguments 数据类型 | **JSON 对象**（结构化） | **JSON 字符串**（需二次解析） |
| arguments.id 字段 | 无 | 有（`call_xxx`，结果回填用） |
| 流式 tool_calls | 通常整块在一个 chunk | 按 `index` 分片累积 arguments |
| 流式格式 | NDJSON（每行一个 JSON） | SSE（`data: {...}`） |

**统一方案**：`ToolCall.arguments` 始终用 **JSON 字符串**表示；Ollama 的对象在解析时转成字符串。流式一律按 `index` 累积（兼容两种分片行为）。

## 三、新增数据结构

新建 `core/llm` 包，放 3 个通用结构（两个客户端共用）：

### ToolSpec（工具定义）
```java
public record ToolSpec(String name, String description, JsonNode parameters) {
    public static ToolSpec of(String name, String description); // 无参数便捷构造
}
```

### ToolCall（模型发起的工具调用）
```java
public record ToolCall(int index, String id, String name, String arguments) {}
// index：流式累积索引；id：OpenAI 有/Ollama 无；arguments：统一 JSON 字符串
```

### LlmResult（统一调用结果）
```java
public record LlmResult(String content, List<ToolCall> toolCalls) {
    public boolean hasToolCalls();
    public static LlmResult text(String content);
    public static LlmResult toolCalls(List<ToolCall> toolCalls);
}
```
模型响应有两种互斥形态：纯文本（content 非空）或工具调用（toolCalls 非空）。

## 四、现状实现（改造前）

### 4.1 OllamaNativeChatModel

```java
public class OllamaNativeChatModel implements ChatModel {
    @Override
    public ChatResponse doChat(ChatRequest request) {
        // 请求体：只有 model / stream / messages / options
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);
        body.put("stream", false);
        ArrayNode msgArr = body.putArray("messages");
        // ... 序列化 messages ...
        // ❌ 无 tools 字段

        // 响应：只读 content
        JsonNode root = mapper.readTree(resp.body());
        String content = root.path("message").path("content").asText("");
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).build();
        // ❌ 忽略 message.tool_calls
    }
}
```

### 4.2 StreamingChatClient

```java
public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) {
    // 请求体：只有 model / messages / stream / options
    // ❌ 无 tools 字段
    // 响应：逐行只取 content token
    // ❌ 忽略 tool_calls
}
```

**问题清单**：
- 请求体无 `tools`：模型不知道有哪些工具可调
- 响应无 `tool_calls` 解析：模型即使发起了工具调用也被丢弃
- 流式无增量拼接：OpenAI 分片的 arguments 无法重组
- 返回类型 `String`/`ChatResponse`：无法表达"工具调用请求"这种形态

## 五、改造后实现

### 5.1 OllamaNativeChatModel —— 新增 chatWithTools

```java
public class OllamaNativeChatModel implements ChatModel {
    @Override
    public ChatResponse doChat(ChatRequest request) { ... }  // 保留：ChatModel 契约，LlmJudge 纯文本用

    // ✅ 新增：带 tools 的非流式对话
    public LlmResult chatWithTools(List<Map<String, Object>> messages, List<ToolSpec> tools) {
        ObjectNode body = ...;
        body.set("messages", mapper.valueToTree(messages));
        // ✅ 附加 tools 数组（统一 OpenAI/Ollama 格式）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = body.putArray("tools");
            for (ToolSpec tool : tools) {
                ObjectNode t = toolsArr.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", tool.parameters());
            }
        }
        // ... 发请求 ...
        JsonNode message = root.path("message");
        String content = message.path("content").asText("");
        // ✅ 解析 tool_calls（Ollama：arguments 为 JSON 对象 → 转字符串）
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode tcArr = message.path("tool_calls");
        if (tcArr.isArray()) {
            for (int i = 0; i < tcArr.size(); i++) {
                JsonNode fn = tcArr.get(i).path("function");
                JsonNode argsNode = fn.path("arguments");
                String args = argsNode.isObject() ? mapper.writeValueAsString(argsNode) : "{}";
                toolCalls.add(new ToolCall(i, tc.path("id").asText(null), fn.path("name").asText(""), args));
            }
        }
        return new LlmResult(content, toolCalls);
    }
}
```

### 5.2 StreamingChatClient —— 新增带 tools 的流式重载

```java
@Service
public class StreamingChatClient {
    // 保留：纯文本流式（ChatService 现有调用，返回 String）
    public String streamChat(List<Map<String, String>> messages, Consumer<String> onToken) { ... }

    // ✅ 新增：带 tools 的流式（返回 LlmResult）
    public LlmResult streamChat(List<Map<String, Object>> messages, List<ToolSpec> tools,
                                Consumer<String> onToken) {
        return isOllama()
            ? streamChatOllamaWithTools(messages, tools, onToken)
            : streamChatOpenAiWithTools(messages, tools, onToken);
    }
}
```

### 5.3 流式 tool_calls 增量拼接（核心难点）

OpenAI 流式中 arguments 按 `index` 分片到达，必须累积：

```
帧1: delta.tool_calls=[{index:0, id:"call_x", function:{name:"search", arguments:"{\"qu"}}]
帧2: delta.tool_calls=[{index:0, function:{arguments:"ery\":\"x\"}"}}]
累积后: index=0, id="call_x", name="search", arguments="{\"query\":\"x\"}"
```

实现：`Map<Integer, ToolCallAccumulator>`，每个 accumulator 持 name/id/StringBuilder(args)：

```java
private static class ToolCallAccumulator {
    int index; String id; String name;
    final StringBuilder args = new StringBuilder();
}

private void accumulateToolCalls(Map<Integer, ToolCallAccumulator> acc, JsonNode tcNode, boolean argsIsString) {
    for (JsonNode tc : tcNode) {
        int idx = tc.path("index").asInt(0);
        ToolCallAccumulator a = acc.computeIfAbsent(idx, k -> new ToolCallAccumulator());
        // 累积 name / id
        // argsIsString=true（OpenAI）：字符串分片拼接
        // argsIsString=false（Ollama）：对象整体覆盖
    }
}
```

## 六、改造前后对比总结

| 维度 | 现状 | 改造后 |
|------|------|--------|
| OllamaNativeChatModel 能力 | 仅纯文本 `doChat` | `doChat` 保留 + 新增 `chatWithTools`（传 tools、解析 tool_calls） |
| StreamingChatClient 能力 | 仅 `streamChat` 返回 String | 保留旧重载 + 新增带 tools 流式返回 `LlmResult` |
| 请求体 tools 字段 | 无 | 统一格式追加（OpenAI/Ollama 同构） |
| 响应 tool_calls 解析 | 忽略 | 非流式直接解析；流式按 index 增量累积 |
| arguments 类型差异 | — | 统一为 JSON 字符串（Ollama 对象转字符串） |
| 流式分片重组 | 不支持 | 支持（OpenAI 分片按 index 拼接） |
| 返回结构 | String / ChatResponse | 新增 `LlmResult`（content + toolCalls） |
| 向后兼容 | — | 旧方法签名不变，ChatService/LlmJudge 零改动 |

## 七、改动文件清单

| 文件 | 改动内容 |
|------|---------|
| `core/llm/ToolSpec.java` | **新增**：工具定义 record（name + description + parameters schema） |
| `core/llm/ToolCall.java` | **新增**：工具调用 record（index + id + name + arguments） |
| `core/llm/LlmResult.java` | **新增**：统一调用结果 record（content + toolCalls） |
| `core/ollama/OllamaNativeChatModel.java` | 保留 `doChat`；新增 `chatWithTools`（传 tools、解析 Ollama tool_calls） |
| `core/ollama/StreamingChatClient.java` | 保留旧 `streamChat`；新增带 tools 流式重载 + 增量累积器 |

## 八、风险评估

- **影响范围**：仅新增方法与数据结构，**不改动任何现有调用方**
- **向后兼容**：`doChat`、旧 `streamChat` 签名与行为完全不变；ChatService、LlmJudge 零改动
- **未接线**：本改造只让客户端"具备"工具调用能力，尚无调用方使用（改造点 5/1 才接入）
- **协议鲁棒性**：tools 为空时退化为纯文本；流式无 tool_calls 时返回空列表；arguments 为空时默认 `{}`
