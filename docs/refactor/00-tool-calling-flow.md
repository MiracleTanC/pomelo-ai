# 改造后工具调用流程详解

> 本文档是对 5 个改造点落地后的工具调用全流程梳理，基于实际代码，便于理解"一次问答从头到尾发生了什么"。
> 配合各改造点对比文档阅读：`02-llm-judge-json-parsing.md` / `04-supportcheck-feedback-loop.md` / `03-tools-support.md` / `05-tool-registry.md` / `01-agent-loop.md`

## 一、整体调用链路

```
ChatController.chatStream(req, onToken)        ← SSE 接口
   │
   ▼
ChatService.chatStream(req, onToken)
   │  ① 持久化用户消息 + 写历史
   │  ② 取对话历史 context
   │
   ▼
SelfRagOrchestrator.orchestrate(kbId, userQuery, context, onToken)   ← 编排全权接管
   │
   ├─ 主路径：Agent 循环（原生 function calling）
   │     │
   │     │  构建 ToolContext + Agent system prompt
   │     │
   │     for round in 1..maxIterations:
   │       │
   │       ▼
   │     StreamingChatClient.streamChat(messages, tools, onToken) → LlmResult
   │       │  （改造点③：请求体带 tools，响应解析 tool_calls）
   │       │
   │       ├─ 无 tool_calls → 最终答案（已流式推送 onToken）→ 返回 ✅
   │       │
   │       └─ 有 tool_calls ↓
   │             │
   │             ▼
   │       ToolRegistry.execute(toolName, args, ctx) → 结果文本    （改造点⑤）
   │             │
   │             ▼
   │       SearchKbTool.execute()                                  （改造点⑤）
   │         ├─ RetrievalService.retrieve()  混合检索（向量+BM25+RRF）
   │         ├─ RerankService.rerank()        LLM 重排
   │         ├─ collectChunks() 回写 ctx.attributes   ← 供编排器回收 Citation
   │         └─ 返回格式化片段文本
   │             │
   │             ▼
   │       回填 messages：assistant(tool_calls) + tool(结果)
   │             │
   │             └─ 继续 next round ↑
   │
   └─ 降级路径：静态编排（改造点④逻辑 + 流式生成）
         改写→必要性→检索→相关性→重排→草稿→支撑性→streamGenerate
   │
   ▼
ChatService 拿到 Result.draftAnswer()（已流式推送完）
   │  ③ 从 Result.contextChunks() 生成 Citation
   │  ④ 持久化助手消息 + 写历史
   │
   ▼
返回 ChatAnswer(sessionId, answer, effectiveQuery, citations)
```

## 二、Agent 循环的核心机制

对应 `SelfRagOrchestrator.orchestrateWithTools()`。本质是 ReAct（Reason + Act）循环的工程实现：

### 每一轮做什么

```
轮次开始：messages = [system(agent), user, ...之前轮次回填的 tool 消息]
    │
    ▼
streamChat(messages, tools, onToken)   ← 把工具清单随请求发给模型
    │
    │  模型两种可能：
    │
    ├─ 可能 A：模型觉得"信息够了，可以直接答"
    │     → 输出纯文本（不带 tool_calls）
    │     → 流式过程逐 token 调 onToken 推给前端
    │     → LlmResult.hasToolCalls() == false
    │     → 循环结束，返回最终答案 ✅
    │
    └─ 可能 B：模型觉得"我需要先查资料"
          → 输出 tool_calls: [{name:"search_kb", arguments:{query:"..."}}]
          → LlmResult.hasToolCalls() == true
          → onToken 此时不会推送（工具调用片段非文本）
          │
          ▼
      回填 assistant 消息：{role:"assistant", tool_calls:[...]}
          │
          ▼
      对每个 tool_call：
          ToolRegistry.execute("search_kb", args, ctx)
            → SearchKbTool 执行检索+重排，返回片段文本
            → 顺便把 chunks 存进 ctx.attributes
          │
          ▼
      回填 tool 消息：{role:"tool", content:"检索到3个片段：[1]...[2]..."}
          │
          └─ 进入下一轮 ↑（模型现在能看到检索结果了）
```

**关键点：模型自己决定**——要不要调工具、调几次、什么时候停止调工具直接给答案。Java 代码只负责"执行工具 + 把结果喂回去"，不干预决策。

### 循环终止条件

- 模型不再发起 tool_calls（正常结束）
- 达到 `max-iterations` 上限（防死循环，返回最后一轮内容）

## 三、具体例子走一遍

假设用户问："pomelo-ai 用了什么向量数据库？"

### 第 1 轮

- messages = `[system(agent), user("pomelo-ai 用了什么向量数据库？")]`
- tools = `[{search_kb}]`
- 模型判断：这需要查知识库 → 输出 `tool_calls: [{search_kb, {query:"pomelo-ai 向量数据库"}}]`
- onToken：无推送（工具调用阶段）
- 执行：`SearchKbTool` 检索 → 命中 README 片段"Milvus Java SDK" → 回填 tool 消息

### 第 2 轮

- messages = `[system, user, assistant(tool_calls), tool(检索结果)]`
- 模型看到检索结果里有"Milvus" → 觉得信息够了 → 输出纯文本答案
- onToken：逐 token 推送 "pomelo-ai 使用的是 Milvus..." 给前端
- `hasToolCalls() == false` → 循环结束

### 对比改造前

同样这个问题，改造前要经历 改写→必要性→检索→相关性→重排→草稿→支撑性 = 6~7 次串行 LLM 调用；
改造后只要 2 次往返（调工具 + 生成）。

## 四、LLM 调用次数对比

| 场景 | 改造前 | 改造后（Agent 模式） |
|------|--------|---------------------|
| 闲聊（无需检索） | 2 次（改写+必要性）+ 1 次流式 = 3 | 1 次（模型直接答，不调工具） |
| 单次检索成功 | 6 次 + 1 次流式 = 7 | 2 次（调工具 + 生成答案） |
| 检索不满意重查 1 次 | ~10 次 | 3 次（调工具 + 再调 + 生成） |

## 五、chunks 如何变成引用（Citation）

Agent 循环里模型只拿到"文本片段"，但前端要的结构化 `Citation`（文档名/页码）从哪来？通过 `ToolContext.attributes` 侧通道传递：

```
SearchKbTool.execute()
   └─ collectChunks(ctx, reranked)   ← 把 RetrievedChunk 存进 ctx.attributes
                                         （按 chunkId 去重，模型多次检索会累积）

Agent 循环结束
   └─ collectChunks(ctx)             ← 编排器从 attributes 回收所有 chunks
        → List<RetrievedChunk>

ChatService
   └─ chunks.stream().map(Citation::from)  ← 生成结构化引用
```

**工具是"侧通道"**：给模型的是文本（供生成用），给编排器的是结构化对象（供 Citation 用），两者通过 `ToolContext.attributes` 解耦传递。

## 六、降级路径（安全网）

Agent 循环不是 100% 可靠（模型可能不支持 function calling、或返回格式异常）。改造后的降级机制：

```
orchestrate(onToken)
   │
   ├─ toolCallingEnabled == true 且 registry 非空？
   │     ├─ 是 → 走 Agent 循环
   │     │        └─ 异常？→ log.warn → 降级 ↓
   │     └─ 否 → 直接降级 ↓
   │
   ▼
静态编排（orchestrateLegacy）
   改写→必要性→检索→相关性→重排→草稿→支撑性（改造点④的完整逻辑）
   + streamGenerate(onToken)  ← 自己做流式生成
   → 返回 Result
```

两种触发方式：
- **自动**：Agent 循环抛异常 → catch 后降级
- **手动**：配置 `pomelo.rag.tool-calling.enabled=false` 强制走静态

## 七、各组件协作关系

```
┌─────────────────────────────────────────────────────────┐
│ ChatService（变薄：只管会话/持久化）                       │
│   └─ orchestrate(kbId, query, ctx, onToken)             │
└──────────────┬──────────────────────────────────────────┘
               ▼
┌─────────────────────────────────────────────────────────┐
│ SelfRagOrchestrator（编排全流程）                         │
│   ├─ 主：Agent 循环  ──┐                                │
│   └─ 降级：静态编排   ─┤                                │
│                       │ streamChat(messages, tools)     │
└───────────────────────┼─────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────────┐
│ StreamingChatClient（改造点③：tools 能力）               │
│   ├─ 请求体附加 tools 数组                               │
│   ├─ 响应解析 tool_calls（流式按 index 增量拼接）         │
│   └─ 返回 LlmResult(content, toolCalls)                 │
└───────────────────────┬─────────────────────────────────┘
                        │ tool_calls
                        ▼
┌─────────────────────────────────────────────────────────┐
│ ToolRegistry（改造点⑤：调度）                            │
│   └─ execute(name, args, ctx) → SearchKbTool            │
└───────────────────────┬─────────────────────────────────┘
                        ▼
┌─────────────────────────────────────────────────────────┐
│ SearchKbTool（改造点⑤：检索工具）                        │
│   ├─ RetrievalService.retrieve()  混合检索               │
│   ├─ RerankService.rerank()       LLM 重排               │
│   ├─ collectChunks() → ctx.attributes（侧通道）          │
│   └─ 返回片段文本                                        │
└─────────────────────────────────────────────────────────┘
```

## 八、消息格式兼容（Ollama vs OpenAI）

Agent 循环回填的 messages 需兼容两种协议，`SelfRagOrchestrator` 通过 `isOllama()` 判断 provider 据此构造：

| 消息类型 | Ollama 格式 | OpenAI 格式 |
|---------|-------------|-------------|
| assistant + tool_calls | `tool_calls[].function.arguments` 为 **对象** | 为 **字符串** + 需 `id` + `type` |
| tool 结果 | `{"role":"tool","content":"..."}` | `{"role":"tool","tool_call_id":"...","content":"..."}` |

对应代码：
- `buildAssistantToolCallMessage()`：Ollama 时 arguments 转对象，OpenAI 时保留字符串 + 带 id/type
- `buildToolMessage()`：OpenAI 时额外带 `tool_call_id`

## 九、一句话总结

改造后的工具调用流程是：**编排器把工具清单随请求发给模型 → 模型自主决定调不调、调几次 → 模型发起 tool_call 时编排器执行工具并把结果喂回去 → 循环直到模型给出最终答案（全程流式推送）**。

Java 代码从"指挥官"退化为"执行员 + 调度员"，决策权交还给模型，这就是 Agent 的本质。
