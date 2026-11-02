# MCP 概念辨析与本项目的应用建议

> 本文档回答：①"MCP 是什么、什么原理"；②"本项目哪里用到了 MCP"；③"建议哪里用"。
> 配合工具调用流程文档阅读：`00-tool-calling-flow.md`、`10-agent-concept.md`、`20-new-tools.md`

## 一、MCP 是什么

### 全称与来历

- **MCP = Model Context Protocol（模型上下文协议）**
- **Anthropic（Claude 的公司）2024 年 11 月开源**的一套标准协议
- 一句话定位：**"AI 应用的 USB-C 接口"**——标准化 LLM 与外部数据源/工具的连接方式

### 解决什么问题

MCP 出现前的乱象：

```
LLM 应用要接 N 个外部能力（数据库/文件/API/搜索...）：
  接 GitHub    → 学 GitHub 的工具格式
  接 Slack     → 学 Slack 的工具格式
  接 数据库    → 自己写一套对接
  接 本地文件  → 自己写一套对接

每个应用 × 每个数据源 = M × N 次对接工作（雪花状耦合）
换个 LLM 应用，全部重写
```

MCP 要做的事：

```
MCP 定义一套标准协议：
  - 任何"能力提供方"按 MCP 标准暴露 → 成为 MCP Server（插件）
  - 任何"LLM 应用"按 MCP 标准消费   → 成为 MCP Client（宿主）

结果：M 个 Server + N 个 Client，只需 M + N 次实现，可任意组合（星型解耦）
```

这就是"USB-C"的比喻——你不用为每个设备配专用线，统一一个接口。

## 二、MCP 的核心原理

### 三个角色

| 角色 | 谁来当 | 职责 |
|------|--------|------|
| **MCP Server** | 能力提供方（数据库连接器、GitHub 连接器、文件系统连接器） | 暴露 tools / resources / prompts |
| **MCP Client** | LLM 应用（Claude Desktop、Cursor、IDE 插件） | 连接 Server，拉取能力，把结果喂给 LLM |
| **Host（宿主）** | 通常就是 Client 所在的应用 | 管理多个 Client 连接，协调 LLM 与工具 |

### MCP Server 暴露的三类能力

| 能力 | 类比本项目 | 说明 |
|------|-----------|------|
| **Tools** | 你的 `search_kb` / `list_documents` | 可被 LLM 调用的函数（有副作用，如查询、写入） |
| **Resources** | 文档原文、知识库元数据 | 可读取的数据（只读，无副作用） |
| **Prompts** | 你的 `PromptTemplates` | 预设的提示词模板 |

### 通信流程

```
① Host 启动 → Client 连接 MCP Server（stdio / SSE / HTTP）
② Client 调 list_tools() → 拿到 Server 暴露的工具清单（JSON Schema 格式）
③ 用户提问 → Host 把工具清单发给 LLM
④ LLM 决定调某工具 → Host 让 Client 调 call_tool(name, args)
⑤ Server 执行 → 返回结果 → Client 回传 → Host 喂给 LLM → 继续
```

**关键：MCP 协议本身不调用 LLM**。它只标准化"工具清单怎么描述"和"工具怎么被调用"。调用 LLM、决定调哪个工具，是 Host 的事。

### 传输层

MCP 用 **JSON-RPC 2.0** 做消息格式，传输方式：
- **stdio**：本地子进程（Server 是个程序，Host 启动它，通过标准输入输出通信）——最常见的本地工具
- **SSE / HTTP**：远程 Server（如云端数据库连接器）

## 三、MCP 和"原生 function calling"的关系

这是最容易被混淆的点。它俩**不是替代关系，是分层关系**：

```
┌─────────────────────────────────────┐
│  LLM 的 function calling            │  ← 模型能力层
│  （模型决定调哪个工具、给什么参数）   │     协议：各家厂商 API
└──────────────┬──────────────────────┘
               │ tool_calls
               ▼
┌─────────────────────────────────────┐
│  MCP（可选的中间层）                │  ← 连接协议层
│  （标准化工具清单描述 + 调用格式）   │     协议：JSON-RPC
└──────────────┬──────────────────────┘
               │ call_tool
               ▼
┌─────────────────────────────────────┐
│  实际工具实现（数据库/文件/API）    │  ← 能力层
└─────────────────────────────────────┘
```

- **function calling**：模型 ↔ 应用之间，模型"怎么说要调工具"
- **MCP**：应用 ↔ 工具提供方之间，工具"怎么被标准化地暴露和调用"

**没有 MCP 也能用 function calling**（本项目就是这样——工具直接写在 Java 里）。MCP 的价值在于**让工具可以跨应用复用、跨进程/跨机器分布**。

## 四、本项目哪里用到了 MCP

**诚实回答：完全没用到。** 全项目无任何 MCP 痕迹（grep `mcp|MCP|McpClient|ModelContextProtocol` 零命中）。

本项目的工具调用链路是"**内嵌式**"的：

```
SelfRagOrchestrator（Host + Client 合一）
  └─ ToolRegistry（本地的、进程内的工具注册表）
       └─ SearchKbTool / ListDocumentsTool / ...（Java 类，同进程）
```

所有工具都是**同进程的 Java Bean**，通过 Spring `@Component` 注册到 `ToolRegistry`。没有跨进程、没有 JSON-RPC、没有 MCP Server。这完全够用，因为所有工具都在本项目内部。

## 五、本项目哪里建议用 MCP

**诚实评估：当前阶段不建议引入 MCP，但有一个场景值得考虑。**

### 当前为什么不需要

| 维度 | 本项目现状 | MCP 的价值点 |
|------|-----------|-------------|
| 工具位置 | 全在同进程 Java 里 | MCP 解决跨进程/跨机器——不需要 |
| 工具复用 | 工具只服务本项目 | MCP 解决跨应用复用——暂时不需要 |
| 工具数量 | 5 个 | MCP 在工具多、动态增减时才显优势 |
| 部署复杂度 | 单体 Spring Boot | MCP 要额外起 Server 进程——增加运维负担 |

为本项目现在的规模引入 MCP，是**杀鸡用牛刀**。

### 唯一值得考虑的场景：把知识库能力对外开放

如果有一天你想：**"我建好了知识库和检索能力，想让别的 AI 应用（Claude Desktop、Cursor、别的 LLM 项目）也能用我的 search_kb"**——这时 MCP 就有价值了。

做法是把 `SearchKbTool` / `ListDocumentsTool` / `GetKbInfoTool` 等包装成一个 **MCP Server**：

```
本项目作为 MCP Server 暴露：
  tools: [search_kb, list_documents, get_kb_info, get_doc_status]

Claude Desktop / Cursor / 其他 LLM 应用 作为 MCP Client：
  连接本项目 → 自动拿到这些工具 → 它们的 LLM 也能检索你的知识库
```

好处：**你的知识库变成一个"标准插件"，任何支持 MCP 的 AI 应用都能即插即用**。不需要别的项目专门写 Java 对接你的 API。

### 如果要做，改造量评估

| 改造项 | 工作量 | 说明 |
|--------|--------|------|
| 引入 MCP SDK（Java） | 低 | Anthropic 有官方 Java SDK |
| 把 Tool 接口适配为 MCP tools | 中 | 本项目 `Tool` 接口和 MCP tool schema 很像，适配层薄 |
| 起 MCP Server 端点（stdio 或 SSE） | 中 | 要加一个入口（独立 main 或 Spring endpoint） |
| 配置/鉴权 | 中 | 跨进程要考虑 token、kbId 路由 |
| **ToolRegistry 复用** | **低** | 现有 `specs()`/`execute()` 几乎能直接喂给 MCP 适配层 |

**关键利好：本项目改造点 5 的 `Tool` 接口设计，和 MCP 的 tool 概念几乎一一对应**（name + description + parameters schema + execute）。如果真要做 MCP Server，`ToolRegistry` 可以直接作为 MCP tools 的后端实现，适配成本很低。这是改造点 5 设计的"意外红利"。

### 改造点 5 接口与 MCP tool 的对应关系

| 本项目 Tool 接口 | MCP tool 字段 | 对应关系 |
|-----------------|--------------|---------|
| `name()` | `name` | 直接映射 |
| `description()` | `description` | 直接映射 |
| `parameters()`（JsonNode schema） | `inputSchema`（JSON Schema） | 直接映射 |
| `execute(argsJson, ctx)` | `call_tool(name, args)` | args 格式一致（都是 JSON 字符串/对象） |

这意味着如果未来要做 MCP Server，适配层只需：
1. 把 `toolRegistry.specs()` 转成 MCP 的 tools 数组
2. 把 MCP 的 `call_tool` 请求转发给 `toolRegistry.execute(name, args, ctx)`
3. 处理跨进程的 `kbId` 路由（MCP 调用时传 kbId，本项目从 ToolContext 取）

## 六、一句话总结

- **MCP** = Anthropic 提的标准化协议，解决"LLM 应用 × 外部能力"的 M×N 对接问题，像"AI 的 USB-C"
- **原理**：Server 暴露 tools/resources/prompts，Client 拉取并调用，JSON-RPC 通信，Host 协调 LLM
- **和 function calling 的关系**：分层不冲突——function calling 是模型↔应用层，MCP 是应用↔工具层
- **本项目**：完全没用 MCP，工具是同进程内嵌式，当前规模不需要
- **建议**：当前不引入；若未来要把知识库能力对外开放给其他 AI 应用，把现有 `ToolRegistry` 适配成 MCP Server 成本很低（改造点 5 的接口设计与 MCP 天然对齐）
