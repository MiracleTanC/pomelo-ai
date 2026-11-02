# 新增工具：扩展 Agent 行动范围

> 在改造点 5（ToolRegistry 自动注册）基础上，依次接入 4 个新工具，验证"加工具零改动编排器"的设计。

## 一、背景

改造后 Agent 主路径只有 `search_kb` 一个工具——它能检索文档正文，但答不了这些：
- "知识库里有哪些文档？"（文档名/状态不是正文内容）
- "这个库多大？阈值是多少？"（知识库配置本身）
- "我上传的文档处理完了吗？"（处理状态）
- "今天是几号？"（实时信息）

补 4 个工具让 Agent 覆盖元数据类和实时类问题。

## 二、新增工具清单

| 工具名 | 封装的现有能力 | 参数 | 回答的问题 |
|--------|---------------|------|-----------|
| `list_documents` | `DocumentMapper.findByKbIdOrderByCreatedAtDesc` | 无 | 知识库有哪些文档 |
| `get_kb_info` | `KnowledgeBaseMapper.findById` + 文档/切片聚合统计 | 无 | 知识库配置/规模 |
| `get_doc_status` | `DocumentMapper.findByKbIdAndStatusIn` | `status`（可选） | 文档处理/向量化状态 |
| `get_current_time` | `ZonedDateTime.now` | 无 | 当前时间 |

全部实现 `Tool` 接口 + `@Component`，**Spring 自动收集进 ToolRegistry，无需任何注册代码**。

## 三、改造前后对比

### 改造前（仅 search_kb）

```
用户："知识库里有哪些文档？"
  → Agent 调 search_kb(query="知识库 文档 列表")
  → 检索正文 → 可能命中某文档里提到"文档"二字 → 答非所问
  → 或无命中 → "无法回答"
```

### 改造后（5 个工具）

```
用户："知识库里有哪些文档？"
  → Agent 看到 system prompt 里 list_documents 工具描述
  → 调 list_documents（无参数）
  → 返回"知识库共 5 个文档：[1] readme.pdf | 状态: 已索引..."
  → Agent 据此生成答案

用户："我上传的文档处理完了吗？"
  → Agent 调 get_doc_status（无 status → 全量，或 status="VECTORIZING"）
  → 返回每个文档的状态 + 进度
```

## 四、system prompt 动态化

`SelfRagOrchestrator.buildAgentSystemPrompt` 从硬编码"search_kb"改为**从 `toolRegistry.specs()` 动态生成工具清单**：

```java
String toolOverview = toolRegistry.specs().stream()
        .map(s -> "- " + s.name() + "：" + briefDesc(s.description()))
        .reduce("", (a, b) -> a + b + "\n");
```

好处：**以后再加工具（如 web_search），system prompt 自动包含新工具描述，无需改编排器**。

## 五、改动文件清单

| 文件 | 改动 |
|------|------|
| `service/rag/tools/ListDocumentsTool.java` | **新增**：列出知识库文档清单 |
| `service/rag/tools/GetKbInfoTool.java` | **新增**：知识库元信息 |
| `service/rag/tools/GetDocStatusTool.java` | **新增**：文档处理状态（支持按 status 过滤） |
| `service/rag/tools/GetCurrentTimeTool.java` | **新增**：当前时间 |
| `service/rag/SelfRagOrchestrator.java` | system prompt 改为从 registry.specs() 动态生成工具清单 |

## 六、验证自动注册机制

加 4 个工具后，**ToolRegistry / SelfRagOrchestrator 主流程 / ToolRegistry 构造逻辑均未改动**——
启动时 Spring 自动收集这 5 个 Tool Bean，日志将打印：
`ToolRegistry 注册 5 个工具: [search_kb, list_documents, get_kb_info, get_doc_status, get_current_time]`

这验证了改造点 5 的设计目标：**新增工具 = 实现 Tool 接口 + @Component，零改动已有代码**。

## 七、后续可扩展（B/C 类，未实现）

- `web_search`：调外部搜索 API，突破"库内才有答案"的限制（需 API key 配置）
- `calculator`：检索到数字后做计算
- `summarize_doc`：对指定文档做摘要
- `cross_kb_search`：跨知识库检索（需扩展 ToolContext 支持多 kbId）
