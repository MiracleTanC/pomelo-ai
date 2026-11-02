# 自主性辨析：for 循环无条件执行，为什么还是 Agent

> 一个常见困惑：改造后的 `SelfRagOrchestrator.orchestrateWithTools()` 里，工具调用是 for 循环"无条件全部执行"的，那"自主调用"体现在哪？本文档讲清楚。

## 一、困惑的代码

```java
for (int round = 1; round <= maxIterations; round++) {
    LlmResult result = streamingClient.streamChat(messages, toolRegistry.specs(), onToken);

    if (!result.hasToolCalls()) {
        return new Result(..., result.content());   // 模型不调工具 → 直接给答案
    }

    // ★ 困惑点：这里无条件全部执行，代码没有"判断该不该调"
    for (ToolCall tc : result.toolCalls()) {
        log.info("Agent 第 {} 轮调用工具: {} args={}", round, tc.name(), tc.arguments());
        String toolResult = toolRegistry.execute(tc.name(), tc.arguments(), ctx);
        messages.add(buildToolMessage(tc, toolResult));
    }
}
```

观察正确：**一旦模型发起 tool_calls，代码确实全部执行，不做挑选。** 但这恰恰是正确的 Agent 设计。

## 二、"自主"体现在三个地方，不在 for 循环里

### 自主点 ①：调不调工具，模型说了算

```java
if (!result.hasToolCalls()) { return ... }   // ← 模型可以选"不调"
```

- 用户问"你好" → 模型不发起 tool_calls → 直接走这里返回答案，**一次工具都不调**
- 用户问"pomelo 用了什么向量库" → 模型发起 tool_calls → 进入 for 循环

**"调不调"是模型决策的，不是代码判断的。** 这就是自主。

### 自主点 ②：调什么、参数是什么，模型说了算

```java
for (ToolCall tc : result.toolCalls()) {
    registry.execute(tc.name(), tc.arguments(), ctx)   // ← name 和 args 都是模型给的
}
```

模型决定调 `search_kb`、决定 `query="向量数据库"`。代码没有说"我觉得应该用这个 query"——那是改造前 Pipeline 干的事。代码只执行模型给的决定。

### 自主点 ③：调几次、何时停，模型说了算

每一轮结束，**又回到循环顶部的 `streamChat`，把决定权交回模型**：

- 第 1 轮：模型看了检索结果，觉得不够 → 再发起 tool_calls → 进入 for 循环再来一次
- 第 2 轮：模型觉得够了 → 不发起 tool_calls → 走 `hasToolCalls()==false` 返回

**"调几次、什么时候停"是模型每一轮重新决策的，不是代码写死的。**

## 三、对比改造前，理解为什么 for 是"无条件"的

### 改造前：代码替模型判断（Pipeline）

```java
// 改造前：代码问模型一个个 yes/no，代码根据答案决定下一步
boolean needRetrieval = judge.parseYesNo(judge.ask(retrievalNecessity));  // 代码问 yes/no
if (!needRetrieval) return ...;                                            // 代码决定不检索

boolean relevant = judge.parseYesNo(judge.ask(relevance));                 // 代码问 yes/no
if (!relevant && i < max) { 改写query; continue; }                        // 代码决定重检索

List<RetrievedChunk> reranked = rerankService.rerank(...);                 // 代码直接调
```

改造前是：**代码问模型一个个 yes/no，代码根据答案决定下一步做什么。** 模型是"被审问的证人"，代码是法官。

### 改造后：模型自己决定（Agent）

改造后是：**代码把工具摆出来，模型自己决定调不调、调什么、调几次。** 模型是司机，代码是车。

那个 for 循环"无条件全部执行"，正是因为**决策已经在上一行（模型发起 tool_calls）做完了**。代码再判断一次"该不该执行这个调用"，就等于代码又抢回了决策权——那就不是 Agent 了，又退回 Pipeline。

## 四、模型乱调工具怎么办？安全网在哪

担忧本质是"没有代码把关，模型乱来怎么办"。有三个安全网：

| 安全网 | 位置 | 作用 |
|--------|------|------|
| `max-iterations` | for 循环条件 | 模型一直调不停？最多 N 轮强制停 |
| `ToolRegistry.execute` 捕获异常 | `ToolRegistry.java:78` | 工具执行失败？转文本回填，不崩，模型能据此调整 |
| system prompt 引导 | `buildAgentSystemPrompt` | 告诉模型"闲聊别调、检索结果够就别再调" |

Agent 的哲学是：**信任模型的决策，但设边界（轮次上限、异常兜底）。** 不是"不信任模型所以每步都代码把关"——那是 Pipeline。

## 五、一句话回答

"自主"**不体现在 for 循环里**（那只是忠实执行模型已做出的决定），**体现在 for 循环上面的 `hasToolCalls` 判断 + 每轮回顶部的重新决策**。

模型决定调不调、调什么、调几次、何时停；代码只负责"模型决定调了，我就执行，并把结果喂回去让它继续决定"。那个 for 无条件执行，正是"把决策权交给模型"的体现，不是缺陷。

## 六、Pipeline 与 Agent 的决策权对比

```
Pipeline（改造前）：
  代码：要不要检索？ → 模型：yes/no → 代码：那我检索
  代码：相关吗？     → 模型：yes/no → 代码：那我重排
  代码：支撑吗？     → 模型：yes/no → 代码：那我重查
  ↑ 代码是决策者，模型只回答 yes/no

Agent（改造后）：
  模型：我需要检索 → 调 search_kb → 看结果 → 还不够 → 再调 → 够了 → 给答案
  ↑ 模型是决策者，代码只执行 + 喂回结果
```
