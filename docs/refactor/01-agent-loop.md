# 改造点 1：SelfRagOrchestrator 改为原生工具调用驱动的 Agent 循环

> 5 个改造点执行顺序：**改造点2 → 改造点4 → 改造点3 → 改造点5 → 改造点1**
> 本文档：第 5 步（收官）

## 一、问题背景

前 4 个改造点分别解决了：JSON 解析（②）、支撑性回环（④）、客户端 tools 能力（③）、工具注册调度（⑤）。
本改造是收官——把所有能力串起来，让 `SelfRagOrchestrator` 从"Java 写死的串行 LLM 调用"变成"模型自主编排的 Agent 循环"。

## 二、现状实现（改造前）

### 2.1 编排流程

```
SelfRagOrchestrator.orchestrate(kbId, userQuery, context)   ← 3 参数，无 onToken
  │
  ① judge.ask(queryRewrite)           LLM 调用 #1（改写）
  ② judge.parseYesNo(retrievalNecessity)  LLM 调用 #2（必要性判断）
  ③ retrievalService.retrieve()       检索（Java 直接调）
  ④ judge.parseYesNo(relevance)       LLM 调用 #3（相关性判断）
  ⑤ rerankService.rerank()            LLM 调用 #4（重排）
  ⑥ judge.ask(generate)               LLM 调用 #5（草稿生成）
  ⑦ judge.parseSupported(supportCheck) LLM 调用 #6（支撑性判断）
  │
  return Result(retrievalUsed, chunks, effectiveQuery, draftAnswer)
```

ChatService 拿到 chunks 后自己组装 messages + 调 `streamChat` 做最终流式生成。

### 2.2 核心问题

| 问题 | 说明 |
|------|------|
| **编排逻辑写死在 Java** | 检索/判断/重排的调度顺序硬编码，模型只被动回答 yes/no |
| **LLM 调用次数多** | 最坏 6 次串行 LLM 调用 + ChatService 再 1 次流式 = 7 次往返 |
| **模型无自主性** | 不能根据检索结果质量自己决定要不要再查 |
| **架构错位** | 编排在 orchestrator，生成在 ChatService，支撑性判断要靠"预生成草稿"绕路 |
| **流式与编排割裂** | onToken 在 ChatService，orchestrate 无法流式推送 |

## 三、改造后实现

### 3.1 新架构：orchestrate 接管全流程

```
SelfRagOrchestrator.orchestrate(kbId, userQuery, context, onToken)   ← 4 参数，带 onToken
  │
  ├─ 主路径：Agent 循环（原生 function calling）
  │   │
  │   │  构建 system prompt（告诉模型有 search_kb 工具）
  │   │
  │   for round in 1..maxIterations:
  │   │   ① streamingClient.streamChat(messages, tools, onToken) → LlmResult
  │   │   ② 无 tool_calls → 最终答案（已流式推送），返回 ✅
  │   │   ③ 有 tool_calls → 执行工具 → 回填结果 → 继续
  │   │
  │   └─ 超过最大轮次 → 返回最后一轮内容
  │
  └─ 降级路径：静态编排（改造点 4 逻辑 + 流式生成）
      │
      │  改写→必要性→检索→相关性→重排→草稿→支撑性
      │  + streamGenerate（流式生成最终答案）
      │
      └─ return Result(retrievalUsed, chunks, effectiveQuery, answer)
```

### 3.2 关键变化

**① orchestrate 签名增加 onToken**
```java
// 改造前
public Result orchestrate(String kbId, String userQuery, String conversationContext)

// 改造后
public Result orchestrate(String kbId, String userQuery, String conversationContext,
                          Consumer<String> onToken)
```
这让 orchestrate 能在 Agent 循环的每一轮流式推送 token，**实现真流式**。

**② ChatService 变薄——不再自己组装 messages**
```java
// 改造前：ChatService 自己组装 messages + 调 streamChat
SelfRagOrchestrator.Result result = selfRag.orchestrate(kbId, userMsg, context);
// 组装 systemPrompt、messages...
String full = streamingClient.streamChat(messages, onToken);

// 改造后：orchestrate 全权接管
SelfRagOrchestrator.Result result = selfRag.orchestrate(kbId, userMsg, context, onToken);
String answer = result.draftAnswer();  // 已流式推送，直接用
```
ChatService 不再注入 StreamingChatClient，不再依赖 PromptTemplates 组装。

**③ Agent 循环用流式 + 工具调用**
每轮都用 `streamingClient.streamChat(messages, tools, onToken)`（改造点 3 的能力）：
- 模型调工具 → onToken 不推送（工具调用片段非文本）→ 执行工具 → 回填 → 继续
- 模型给答案 → onToken 流式推送 → 返回

**④ chunks 回收机制**
SearchKbTool 执行时把检索到的 RetrievedChunk 回写到 `ToolContext.attributes`（改造点 5 的 ToolContext）。
Agent 循环结束后，orchestrate 从 attributes 回收 chunks → 生成结构化 Citation。

**⑤ 降级路径保留**
Agent 循环失败（模型不支持 tools / 异常）时，自动降级到静态编排（改造点 4 逻辑）+ 流式生成。
通过配置 `pomelo.rag.tool-calling.enabled`（默认 true）控制。

### 3.3 LLM 调用次数对比

| 场景 | 改造前 | 改造后（Agent 模式） |
|------|--------|---------------------|
| 闲聊（无需检索） | 2 次（改写+必要性）+ 1 次流式 = 3 | 1 次（模型直接答，不调工具） |
| 单次检索成功 | 6 次 + 1 次流式 = 7 | 2 次（调工具 + 生成答案） |
| 检索不满意重查 1 次 | ~10 次 | 3 次（调工具 + 再调 + 生成） |

## 四、改造前后对比总结

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 编排方式 | Java 写死 7 步串行 | 模型自主 Agent 循环（原生 function calling） |
| 模型角色 | 被动回答 yes/no | 自主决定何时检索、是否再查、何时给答案 |
| LLM 往返次数 | 3~7+ 次 | 1~3 次（典型场景） |
| 流式输出 | ChatService 独立做 | orchestrate 每轮流式推送（真流式） |
| 架构职责 | 编排在 orchestrator，生成在 ChatService | orchestrate 接管全流程，ChatService 只持久化 |
| 工具扩展 | 改编排器主流程 | 实现 Tool 接口 + @Component（改造点 5） |
| 降级保障 | 无 | Agent 失败自动降级到静态编排 |
| 支撑性判断 | 预生成草稿绕路 | 模型自主判断（拿到工具结果后自行评估） |
| ChatService 依赖 | StreamingChatClient + PromptTemplates | 仅 SelfRagOrchestrator |

## 五、Ollama / OpenAI 消息格式兼容

Agent 循环回填的 messages 需兼容两种协议：

| 消息类型 | Ollama 格式 | OpenAI 格式 |
|---------|-------------|-------------|
| assistant + tool_calls | `tool_calls[].function.arguments` 为 **对象** | 为 **字符串** + 需 `id` + `type` |
| tool 结果 | `{"role":"tool","content":"..."}` | `{"role":"tool","tool_call_id":"...","content":"..."}` |

orchestrate 通过 `isOllama()` 判断 provider，据此构造消息（`buildAssistantToolCallMessage` / `buildToolMessage`）。

## 六、改动文件清单

| 文件 | 改动内容 |
|------|---------|
| `core/llm/ToolContext.java` | 新增 `attributes` 可变属性袋 + `put`/`get` 方法（供工具回写产出） |
| `service/rag/tools/SearchKbTool.java` | 执行后把 chunks 回写到 attributes（`collectChunks`，去重累积） |
| `service/rag/SelfRagOrchestrator.java` | **重写**：新增 `orchestrate(...,onToken)` 四参数版；Agent 循环主路径 + 静态编排降级路径；接管流式生成 |
| `service/chat/ChatService.java` | **适配**：移除 StreamingChatClient/PromptTemplates 依赖；orchestrate 接管生成，ChatService 只持久化 |

## 七、风险评估

- **影响范围**：SelfRagOrchestrator（重写）+ ChatService（适配）+ ToolContext/SearchKbTool（小改）
- **降级保障**：Agent 循环失败自动降级到静态编排（改造点 4 逻辑），且可通过 `pomelo.rag.tool-calling.enabled=false` 强制走静态
- **向后兼容**：Result 结构不变（改造点 4 已有 draftAnswer）；ChatService 接口不变（chatStream 签名不变）
- **流式保证**：两条路径都通过 onToken 流式推送；Agent 模式下工具调用轮次不推送（正常），生成轮次推送
- **模型兼容性**：qwen2.5 / DeepSeek 均支持原生 function calling；若模型不支持，降级路径兜底
