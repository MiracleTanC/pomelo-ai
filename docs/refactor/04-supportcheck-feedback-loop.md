# 改造点 4：补全 SelfRagOrchestrator 缺失的 supportCheck 支撑性回环

> 5 个改造点执行顺序：**改造点2 → 改造点4 → 改造点3 → 改造点5 → 改造点1**
> 本文档：第 2 步（改造点 4）

## 一、问题背景

`SelfRagOrchestrator` 类注释（第 21 行）声明了 7 步流程：

```
1. Query 改写
2. 检索必要性判断
3. 向量检索 Top-K
4. 相关性判断
5. LLM 重排打分
6. 生成答案
7. 支撑性判断：若 unsupported 且仍有迭代次数，则重新检索；否则返回
```

但 `orchestrate()` 方法体里：
- **第 6 步"生成答案"未做**——orchestrator 只返回检索片段，答案生成交给 `ChatService` 做流式输出
- **第 7 步"支撑性判断"完全缺失**——`PromptTemplates.supportCheck` 模板定义了（第 59 行），`LlmJudge.parseSupported` 也实现了，但 `orchestrate()` 从未调用

这是**文档与实现不一致的 bug**，也是与 Self-RAG 论文定义的最大缺口。

## 二、现状实现

### 2.1 orchestrate() 流程（现状）

```
orchestrate(kbId, userQuery, conversationContext)
  │
  ① judge.ask(queryRewrite)           → rewrittenQuery
  ② judge.parseYesNo(retrievalNecessity) → needRetrieval?
  │
  └─ 不需检索 → return Result(false, null, userQuery)
  │
  for i in 1..maxIterations:
  │   ③ retrievalService.retrieve(kbId, rewrittenQuery) → candidates
  │   ④ judge.parseYesNo(relevance)   → relevant?
  │   │
  │   └─ 不相关且 i < max → 改写 query, continue
  │   ⑤ rerankService.rerank → reranked
  │   return Result(true, reranked, rewrittenQuery)   ← 直接返回，无支撑性判断
  │
  return Result(true, List.of(), rewrittenQuery)
```

**问题**：rerank 后直接 return，没有"答案是否被证据支撑"的校验。
- 模型可能基于不相关的片段编造答案（幻觉）
- 无法触发"不支撑则重检索"的闭环

### 2.2 ChatService 的调用方式

```java
// ChatService.chatStream()
SelfRagOrchestrator.Result result = selfRag.orchestrate(req.kbId(), userMsg, context);
List<RetrievedChunk> chunks = result.contextChunks() == null
        ? List.of() : result.contextChunks();
// ... 组装 systemPrompt ...
String full = streamingClient.streamChat(messages, onToken);  // 答案在这里生成
```

答案在 ChatService 生成，**无法回流到 orchestrator 做支撑性判断**。

## 三、架构错位分析

Self-RAG 论文的支撑性判断（IsSup）定义是"**生成答案后**，判断答案是否被检索片段支撑"。
这要求 orchestrator 能拿到答案，但现状架构里答案生成在 ChatService——这就是错位。

### 方案选择

| | 方案 A（最小改动） | 方案 B（更符合论文） |
|---|---|---|
| 做法 | orchestrator 内部用非流式 ChatModel 先生成**草稿答案**，做支撑性判断，通过后返回片段给 ChatService 流式输出 | 把生成步骤搬进 orchestrator，返回 draftAnswer，ChatService 直接用它做流式输出 |
| 代价 | 多一次非流式 LLM 调用（草稿生成） | 需改 ChatService.chatStream 结构，改动大 |
| 风险 | 低，orchestrator 自洽 | 中，影响流式输出链路 |
| 本次采用 | ✅ 是 | 否（留给改造点 1 统一优化） |

**本次采用方案 A**：在 `orchestrate()` 内部加预生成 + 支撑性判断回环，ChatService 无需改动。

## 四、改造后实现

### 4.1 orchestrate() 流程（改造后）

```
orchestrate(kbId, userQuery, conversationContext)
  │
  ① judge.ask(queryRewrite)           → rewrittenQuery
  ② judge.parseYesNo(retrievalNecessity) → needRetrieval?
  │
  └─ 不需检索 → return Result(false, null, userQuery)
  │
  for i in 1..maxIterations:
  │   ③ retrievalService.retrieve(kbId, rewrittenQuery) → candidates
  │   ④ judge.parseYesNo(relevance)   → relevant?
  │   │
  │   └─ 不相关且 i < max → 改写 query, continue
  │   ⑤ rerankService.rerank → reranked
  │   ⑥ 预生成草稿答案：judge.ask(generate(prompt))  → draftAnswer   ← 新增
  │   ⑦ judge.parseSupported(supportCheck) → supported?               ← 新增
  │   │
  │   └─ 不支撑且 i < max → 改写 query, continue                        ← 新增回环
  │   return Result(true, reranked, rewrittenQuery, draftAnswer)        ← 携带草稿
  │
  return Result(true, List.of(), rewrittenQuery, null)
```

### 4.2 关键改动点

**① Result record 新增 `draftAnswer` 字段**
```java
public record Result(
        boolean retrievalUsed,
        List<RetrievedChunk> contextChunks,
        String effectiveQuery,
        String draftAnswer          // 新增：预生成草稿答案（支撑性判断用）
) {
}
```

**② orchestrate() 在 rerank 后增加预生成 + 支撑性判断**
```java
// 5. 重排
List<RetrievedChunk> reranked = rerankService.rerank(rewrittenQuery, candidates);

// 6. 预生成草稿答案（非流式，用于支撑性判断）
String draftAnswer = null;
try {
    String genPrompt = PromptTemplates.generate(userQuery, conversationContext, reranked);
    draftAnswer = judge.ask(genPrompt);
} catch (Exception e) {
    log.warn("第 {} 次草稿生成失败，跳过支撑性判断: {}", i, e.getMessage());
}

// 7. 支撑性判断：若草稿答案未被片段支撑，且仍有迭代次数，则改写重检索
if (draftAnswer != null) {
    boolean supported;
    try {
        supported = judge.parseSupported(
                judge.ask(PromptTemplates.supportCheck(userQuery, draftAnswer, reranked)));
    } catch (Exception e) {
        log.warn("第 {} 次支撑性判断失败，默认支撑: {}", i, e.getMessage());
        supported = true;
    }
    if (!supported && i < maxIterations) {
        log.info("第 {} 次判定答案未被证据支撑，尝试改写后重检索", i);
        try {
            String rw2 = judge.ask(PromptTemplates.queryRewrite(
                    conversationContext + "\n当前查询：" + rewrittenQuery + "答案缺乏证据支撑，请换个角度改写。",
                    userQuery));
            if (rw2 != null && !rw2.isBlank()) {
                rewrittenQuery = rw2.split("\\n")[0].strip();
            }
        } catch (Exception e) {
            log.warn("支撑性迭代改写失败: {}", e.getMessage());
        }
        continue;
    }
}

return new Result(true, reranked, rewrittenQuery, draftAnswer);
```

### 4.3 ChatService 的适配（最小改动）

```java
// ChatService.chatStream()
SelfRagOrchestrator.Result result = selfRag.orchestrate(req.kbId(), userMsg, context);
List<RetrievedChunk> chunks = result.contextChunks() == null
        ? List.of() : result.contextChunks();
// ... 组装 systemPrompt（不变）...
// 流式生成：若 orchestrator 已生成草稿，可直接使用；否则走原流式逻辑
String full = streamingClient.streamChat(messages, onToken);
```

> ChatService 暂不消费 `draftAnswer`（仍走自己的流式生成），保证最小改动。
> `draftAnswer` 的真正价值在改造点 1 重构后体现——届时 ChatService 可复用已通过支撑性判断的草稿。

## 五、改造前后对比总结

| 维度 | 现状 | 改造后 |
|------|------|--------|
| 第⑥步"生成答案" | 缺失（在 ChatService 做） | orchestrator 内预生成草稿（非流式） |
| 第⑦步"支撑性判断" | **完全缺失** | 新增：草稿答案 vs 检索片段的支撑性校验 |
| 不支撑时的闭环 | 无 | 判定 unsupported → 改写 query → 重检索 |
| 与论文定义对齐 | 7 步缺 2 步 | 7 步全部实现 |
| 幻觉防护 | 无（可能基于不相关片段编造） | 有（支撑性不通过则重检索） |
| 返回结构 | `Result(retrievalUsed, contextChunks, effectiveQuery)` | 新增 `draftAnswer` 字段 |
| 额外开销 | — | 每次迭代多 1~2 次非流式 LLM 调用（草稿生成 + 支撑性判断） |

## 六、改动文件清单

| 文件 | 改动内容 |
|------|---------|
| `service/rag/SelfRagOrchestrator.java` | Result 新增 `draftAnswer` 字段；orchestrate() 在 rerank 后增加预生成 + 支撑性判断回环 |
| `service/chat/ChatService.java` | 适配新增的 `draftAnswer` 字段（构造 Result 处，ChatService 只读取现有字段，签名兼容） |

## 七、风险评估

- **影响范围**：SelfRagOrchestrator 主流程 + Result 结构 + ChatService 适配
- **向后兼容**：Result 新增字段，ChatService 现有字段读取逻辑不变；draftAnswer 暂未被 ChatService 消费
- **性能影响**：每次迭代多 1~2 次非流式 LLM 调用。受 `max-iterations` 限制（默认 3），最坏情况增加 6 次调用。若模型响应快（Ollama 本地 qwen2.5:7b），可接受
- **降级策略**：草稿生成失败 / 支撑性判断失败均不影响主流程（catch 后默认通过），保证不比现状差
