# pomelo-ai

一个从 0 到能问答的 **Java RAG 脚手架**：知识库构建 + Self-RAG 智能问答。

完整链路：解析(PDF/Word/TXT) → 切块 → 向量化 → 入 Milvus → 检索 → LLM 重排 → Self-RAG → 多轮问答 + 引用来源。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | |
| Spring Boot | 4.1.2 | Web + JPA + Redis |
| langchain4j | 1.22.0 | Ollama ChatModel + EmbeddingModel |
| Milvus Java SDK | 3.0.12 | MilvusClientV2（服务端 v3.0.4）|
| MySQL | 8.0 | 知识库/文档/会话元数据 |
| Redis | 7 | 会话历史 + 鉴权 token |
| 解析 | MinerU 4.0（外部 HTTP）/ PDFBox 3.0.8 / POI 6.0.0 | |
| 嵌入模型 | `nomic-embed-text`（768 维）| Ollama |
| 对话模型 | `qwen2.5:7b` | Ollama（生成 + 重排 + Self-RAG 判断）|

## 快速开始

> 📖 完整步骤见 [docs/启动指南.md](docs/启动指南.md)(含环境要求、端口一览、端到端 curl 示例、常见问题排查)

### 1. 启动基础设施
```bash
docker compose up -d   # Milvus + etcd + minio + MySQL + Redis
```

### 2. 启动 Ollama 并拉取模型
```bash
ollama serve
ollama pull qwen2.5:7b
ollama pull nomic-embed-text
```

### 3. （可选）启动 MinerU 4.0 解析服务
```bash
pip install "mineru>=4.0,<5"
mineru server start          # 默认 http://localhost:8000
```
然后在 `application.yml` 中设 `pomelo.mineru.enabled: true`。未启用时自动回退 PDFBox/POI 本地解析。

### 4. 构建运行
```bash
mvn spring-boot:run
```
访问 http://localhost:8080  （默认账号 `admin` / `admin123`）

## 核心流程

```
上传文档 → 异步入库流水线（解析→切块→nomic 向量化→入 Milvus，状态机）
提问 → Self-RAG 编排：
   ① Query 改写（多轮改写为独立检索查询）
   ② 检索必要性判断（闲聊直接答）
   ③ 向量 Top-K 检索
   ④ 相关性判断（不相关则改写重检索，最多 3 次迭代）
   ⑤ LLM 重排打分取 Top-K
   ⑥ 生成答案（标注引用序号）+ SSE 流式输出
   ⑦ 引用来源（文档名/页码/片段）
```

## API 速览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 登录获取 token |
| POST | `/api/kb` | 创建知识库（自动建 Milvus Collection）|
| GET  | `/api/kb` | 知识库列表 |
| DELETE | `/api/kb/{id}` | 删除知识库（连带删 Collection）|
| POST | `/api/doc/upload/{kbId}` | 上传文档（异步入库）|
| GET  | `/api/doc/list/{kbId}` | 文档列表（含状态）|
| DELETE | `/api/doc/{docId}` | 删除文档（连带删向量）|
| POST | `/api/chat/stream` | **SSE 流式问答**（token→done 事件）|
| POST | `/api/chat/ask` | 非流式问答 |
| GET  | `/api/chat/history/{sessionId}` | 会话历史 |

## 目录结构（显式水平分层）

依赖方向单一向下：`api → service → repository → domain`，`core` 与 `common` 被各层复用。

```
org.pomelo
├── PomeloAiApplication           启动类
├── common/                        通用层（横切）：统一响应 / 异常 / 枚举
├── domain/                        领域层：JPA 实体 + 值对象（纯数据）
│   ├── kb/  doc/  chat/           知识库 / 文档 / 会话实体
│   ├── rag/                       RetrievedChunk / Citation
│   ├── chunk/                     TextChunk
│   └── parser/                    ParsedBlock / ParsedDocument
├── repository/                   数据访问层：JPA Repositories
├── core/                          底层支持设施：配置 + 外部系统适配器
│   ├── config/                    Spring 配置（Milvus / Ollama / Async / Web）
│   ├── milvus/                    MilvusVectorStore（Milvus 封装）
│   ├── ollama/                    EmbeddingService / OllamaStreamingClient
│   ├── parser/                    MineruHttpParser / LocalParser / Dispatcher / SPI
│   ├── chunk/                     RecursiveCharChunker
│   └── security/                  AuthService / AuthTokenFilter / RbacService
├── service/                       服务/应用层：业务编排
│   ├── rag/                       RetrievalService / RerankService / SelfRagOrchestrator / Prompts / LlmJudge
│   ├── chat/                      ChatService / ChatHistoryService
│   ├── KnowledgeBaseService
│   └── DocumentService            （异步入库流水线）
└── api/                           表现层：Controllers + DTO
    ├── kb/                         Controller + req/（请求入参）+ vo/（响应视图）
    ├── doc/                       Controller + vo/
    ├── chat/                       Controller + req/ + vo/
    └── auth/                       Controller + req/ + vo/
```

> 各 `api` 模块下：Controller 留在模块顶层，请求入参放 `req/` 子包，响应视图对象放 `vo/` 子包，入参与出参分离。

## TODO（后续优化）
- [ ] 混合检索：向量 + BM25 关键词（当前纯向量 Top-K）
- [ ] RBAC 多用户权限体系（当前简单单用户）
- [ ] MinerU 富文本/表格/公式深度集成
- [ ] Vue3 前端工程（当前为极简测试页 `static/index.html`）

## License
MIT
