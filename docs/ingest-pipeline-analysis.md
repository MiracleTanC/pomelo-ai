# Pomelo-AI 文档入库（知识库构建）流水线源码分析报告

> 分析对象：`C:\work\git\pomelo-ai` 项目中文档从上传到入 Milvus 的完整知识库构建流水线。
> 技术栈基线（来自 `pom.xml`）：Spring Boot **4.1.2** / Java **21** / langchain4j **1.23.0** / Milvus SDK Java **3.0.12**（`MilvusClientV2`）/ Apache PDFBox **3.0.8** / Apache POI **6.0.0**（poi-ooxml）/ MyBatis Spring Boot Starter **4.1.0**（MyBatis 3.5.19）/ 阿里云 OSS SDK **3.18.5** / Jackson。
> 说明：本报告所有类名、方法名、参数值、库名均来自实际源码，未作推测。

---

## 一、流水线整体概述（从上传到入 Milvus 的完整流程）

Pomelo-AI 的文档入库流水线由 `org.pomelo.service.DocumentService` 统一编排，采用**四阶段解耦**设计：上传 → 解析 → 切片 → 向量化，每一步可独立触发，也可通过 `ingest()` 一步串联执行。

完整数据流如下：

```
HTTP 上传 (MultipartFile)
   │  DocumentController.upload()  →  DocumentService.upload()
   ▼
[1] 上传阶段
   │  ① 上传源文件到 OSS（OssStorageService）
   │  ② 计算文件 SHA-256
   │  ③ INSERT document 记录，状态=UPLOADED
   │  ④ 若知识库 autoVectorize=true → 异步触发 self.ingest(docId)
   ▼
[2] 解析阶段（@Async，OSS 下载临时文件 → ParserDispatcher）
   │  ① 状态 PARSING
   │  ② ParserDispatcher.parse()：MinerU 优先（失败回退 LocalParser）
   │     · MineruHttpParser → MineruClient（提交任务→轮询→提取 Markdown/ContentList）
   │     · LocalParser：PDFBox 解析 PDF / POI 解析 DOCX / 直接读 TXT·MD
   │  ③ 拼接纯文本存入 document.parsed_content
   │  ④ MetadataExtractor 提取元数据（SYSTEM/DOC_PROPERTY/REGEX/LLM 四种方式）
   │  ⑤ 状态 PARSED
   ▼
[3] 切片阶段（@Async，ChunkerDispatcher）
   │  ① 状态 CHUNKING
   │  ② 按知识库 chunkStrategy 选择：general(RecursiveCharChunker) / heading(HeadingChunker) / qa(QaChunker)
   │  ③ 参数来自知识库：chunkSize（默认 500）、chunkOverlap（默认 50）
   │  ④ 持久化 doc_chunk 记录，状态=PENDING
   │  ⑤ 状态 CHUNKED
   ▼
[4] 向量化阶段（@Async，EmbeddingService + MilvusVectorStore）
   │  ① 状态 VECTORIZING
   │  ② EmbeddingService.embedBatch()：langchain4j EmbeddingModel（OllamaNativeEmbeddingModel 调 /api/embed）
   │  ③ MilvusVectorStore.insertWithChunkIds()：写入知识库对应的 Milvus Collection
   │  ④ 切片状态置 VECTORIZED，文档状态置 INDEXED
   │  ⑤ 副流程：vectorizeMetadata()（摘要/关键词向量化）+ bm25Indexer.rebuild()
   ▼
可检索状态 INDEXED（向量入 Milvus + BM25 倒排索引就绪）
```

控制入口在 `DocumentController`（`@RequestMapping("/api/doc")`），提供细粒度 REST 端点：`upload`、`parse`、`chunk`、`vectorize`、`ingest`（完整流水线）、`reparse`、`vectorize-incremental`（增量）、`vectorize-batch`、`vectorize-all` 等。

---

## 二、每一步详细说明

### 步骤 1：上传

| 项 | 内容 |
|---|---|
| 负责类 | `DocumentController.upload()`（`POST /api/doc/upload/{kbId}`）、`DocumentService.upload(String kbId, MultipartFile file)` |
| 技术/库 | Spring Web `MultipartFile`；阿里云 OSS SDK（`OssStorageService`）；`java.security.MessageDigest`（SHA-256）；`HexFormat`（Java 17+，十六进制编码） |
| 关键逻辑 | ① 校验文件名非空；② `OssStorageService.buildObjectPath(kbId, original)` 生成对象路径 `{kbId}/{timestamp}{扩展名}`（不含原始文件名，文件名存数据库 `doc.name`）；③ `ossStorage.upload(objectPath, file.getInputStream(), file.getSize())` 上传 OSS；④ 用临时文件计算 SHA-256（`sha256(Path)`，`Files.readAllBytes` + `MessageDigest.getInstance("SHA-256")`）；⑤ 构建 `Document` 实体（`UUID.randomUUID().toString()` 作 id，`filePath` 存 OSS 对象路径，`status=DocStatus.UPLOADED`）；⑥ `docRepository.insert(doc)` 单条插入（刻意**不加 `@Transactional`**，确保 insert 立即提交，异步 ingest 能查到该记录）；⑦ 若 `kbService.getEntity(kbId).isAutoVectorize()` 为真，通过 `self.ingest(doc.getId())` 异步触发完整流水线 |
| 输出 | `Document` 实体（状态 `UPLOADED`），返回 `DocVo`；OSS 中已存源文件 |
| 批量上传 | `uploadBatch(kbId, MultipartFile[])` 逐个调用 `upload`，单个失败不影响其他文件 |

> 设计要点：`self` 是通过 `@Autowired @Lazy private DocumentService self` 注入的自代理对象，用于解决 `@Async`/`@Transactional` 在同类内部自调用时失效的问题（Spring AOP 代理绕过）。

### 步骤 2：解析（Parse）

| 项 | 内容 |
|---|---|
| 负责类 | `DocumentService.parse(String docId)`（`@Async("ingestExecutor")`）、`ParserDispatcher`、`MineruHttpParser`、`LocalParser` |
| 技术/库 | MinerU 远程解析（HTTP 客户端 `MineruClient`，JDK `java.net.http.HttpClient`）；Apache PDFBox 3.0.8（`Loader.loadPDF`、`PDFTextStripper`）；Apache POI 6.0.0（`XWPFDocument`、`XWPFParagraph`）；Jackson（解析 MinerU JSON）；`ZipInputStream`（解压结果 ZIP） |
| 关键逻辑 | ① `updateStatus(doc, DocStatus.PARSING, null)`；② `ossStorage.downloadToTemp(doc.getFilePath())` 从 OSS 下载到本地临时文件；③ `parser.parse(tempFile, doc.getName(), doc.getFilePath(), doc.getId())` 分发解析；④ 解析结果 `ParsedDocument.blocks()` 用 `\n\n` 拼接为纯文本，写入 `doc.setParsedContent(...)`、`doc.setParsedAt(Instant.now())`，`docRepository.update(doc)`；⑤ 通过 `self.extractAndSaveMetadata(doc)`（`@Transactional`）提取元数据，失败仅 `log.warn` 不阻断；⑥ `updateStatus(doc, DocStatus.PARSED, null)`；⑦ `finally` 删除临时文件 |
| 异常处理 | 捕获后 `updateStatus(doc, DocStatus.PARSE_FAILED, truncate(e.getMessage(), 900))` |
| 输出 | `Document.parsedContent` 填充纯文本；状态 `PARSED`；同时落库 `DocMetadata` 记录 |

`ParserDispatcher.parse(file, fileName, objectPath, docId)` 的分发逻辑：
```java
if (mineruEnabled && mineruParser.supports(fileName)) {
    try { return mineruParser.parse(file, fileName, objectPath, docId); }
    catch (Exception e) { log.warn("MinerU 解析失败，回退本地解析器: {}", e.getMessage()); }
}
return localParser.parse(file, fileName);  // 失败则抛 RuntimeException
```

### 步骤 3：切片（Chunk）

| 项 | 内容 |
|---|---|
| 负责类 | `DocumentService.chunk(String docId)`（`@Async("ingestExecutor")`）、`ChunkerDispatcher`、`RecursiveCharChunker`/`HeadingChunker`/`QaChunker` |
| 技术/库 | 纯 Java 字符串处理（递归分隔符切分、正则 `Pattern`）；QA 策略使用 langchain4j `ChatModel` |
| 关键逻辑 | ① `updateStatus(doc, DocStatus.CHUNKING, null)`；② 重建 `ParsedDocument`：将 `doc.getParsedContent()` 按 `\n\n` 拆段为 `ParsedBlock`；③ `doChunk(doc)`：读取知识库配置 `kb.getChunkStrategy()`、`kb.getChunkSize()`、`kb.getChunkOverlap()`，调用 `chunkerDispatcher.chunk(...)`；④ `chunkRepository.deleteByDocId(docId)` 删旧切片；⑤ 持久化：每个 `TextChunk` → `DocChunk`（UUID id、`chunkIndex`、`content`、`page`、`charCount`、`contentHash=sha256String(text)`、`status=ChunkStatus.PENDING`），`chunkRepository.batchInsert(docChunks)`；⑥ `doc.setChunkCount(chunks.size())`、`vectorizedChunks=0`，状态 `CHUNKED` |
| 输出 | `doc_chunk` 表记录；`Document.chunkCount`；状态 `CHUNKED` |

### 步骤 4：向量化（Vectorize）+ 入 Milvus

| 项 | 内容 |
|---|---|
| 负责类 | `DocumentService.doVectorize(Document doc)`（私有，被 `ingest`/`vectorize` 调用）、`EmbeddingService`、`MilvusVectorStore` |
| 技术/库 | langchain4j `EmbeddingModel`（`OllamaNativeEmbeddingModel` 继承 `DimensionAwareEmbeddingModel`）；Milvus SDK `MilvusClientV2`；Gson（序列化向量行） |
| 关键逻辑 | ① `updateStatus(doc, DocStatus.VECTORIZING, null)`；② 从 `chunkRepository` 取全部切片，过滤出 `PENDING/EDITED/FAILED` 的脏切片 `pending`；③ 若 `pending` 为空直接置 `INDEXED` 返回；④ `embeddingService.embedBatch(textChunks)` 批量嵌入；⑤ `vectorStore.insertWithChunkIds(kbId, docId, docName, chunkIds, textChunks, vectors)` 入 Milvus；⑥ 逐条 `chunkRepository.update` 置切片状态 `VECTORIZED`；⑦ `doc.setVectorizedChunks(chunks.size())`、`status=INDEXED`、`vectorizedAt=now`；⑧ 副流程：`vectorizeMetadata(doc.getId())`（失败仅 warn）、`bm25Indexer.rebuild(doc.getKbId())`（失败仅 warn） |
| 输出 | Milvus Collection 中新增向量行；切片状态 `VECTORIZED`；文档状态 `INDEXED` |

`ingest(String docId)`（`@Async("ingestExecutor")`）是完整流水线入口，串行执行：解析（若未解析）→ 切片（若未切片）→ `doVectorize`，异常时根据 message 是否含"解析"判定为 `PARSE_FAILED` 或 `VECTOR_FAILED`。

---

## 三、状态机：文档状态流转

状态枚举 `org.pomelo.common.enums.DocStatus`（注意：实际无 `PENDING`，初始态为 `UPLOADED`）：

```
UPLOADED ──► PARSING ──► PARSED ──► CHUNKING ──► CHUNKED ──► VECTORIZING ──► INDEXED
               │                       │                        │
               ▼                       ▼                        ▼
         PARSE_FAILED           CHUNK_FAILED              VECTOR_FAILED
```

各状态及中文标签：
- `UPLOADED`（已上传）— 上传后初始态
- `PARSING`（解析中）— 处理中
- `PARSED`（已解析）— 解析成功
- `PARSE_FAILED`（解析失败）— 终态失败
- `CHUNKING`（切片中）— 处理中
- `CHUNKED`（已切片）— 切片成功
- `CHUNK_FAILED`（切片失败）— 终态失败
- `VECTORIZING`（向量化中）— 处理中
- `INDEXED`（已索引）— 可检索终态
- `VECTOR_FAILED`（向量化失败）— 终态失败
- `DELETED`（已删除）— 终态

辅助判定方法：
- `isTerminal()`：`INDEXED || PARSE_FAILED || CHUNK_FAILED || VECTOR_FAILED || DELETED`
- `isSearchable()`：仅 `INDEXED`
- `isProcessing()`：`PARSING || CHUNKING || VECTORIZING`

**切片级状态机**（`DocChunk.ChunkStatus`）：`PENDING → VECTORIZED`（成功）/ `FAILED`（失败）/ `EDITED`（内容已编辑，需重新向量化）/ `STALE`（重新切片后旧切片标记）。增量向量化时 `incrementalVectorize()` 只重嵌 `EDITED/FAILED/PENDING` 切片。

状态更新通过 `updateStatus(Document doc, DocStatus status, String errorMsg)`（直接调用，不经代理，单条 UPDATE 天然原子），同时写入 `errorMsg`（异常信息截断至 900 字符，由 `truncate(s, 900)` 处理）。

**重置路径**：`reParse(docId)` 在重新解析前，若已 `INDEXED` 先 `vectorStore.deleteByDoc` 删 Milvus 向量，删旧切片，清空 `parsedContent/chunkCount/vectorizedChunks/parsedAt/chunkedAt/vectorizedAt`，状态重置回 `UPLOADED`，再走 `self.ingest()`。

---

## 四、异步机制

### 4.1 线程池配置
`org.pomelo.core.config.AsyncConfig`（`@Configuration @EnableAsync`）定义名为 `ingestExecutor` 的 `ThreadPoolTaskExecutor`：

| 参数 | 值 |
|---|---|
| corePoolSize | 2 |
| maxPoolSize | 4 |
| queueCapacity | 50 |
| threadNamePrefix | `ingest-` |
| rejectedExecutionHandler | `ThreadPoolExecutor.CallerRunsPolicy`（队列满时由调用线程执行，提供背压） |

### 4.2 @Async 标注的方法
`DocumentService` 中所有耗时入库方法均标注 `@Async("ingestExecutor")`，在 `ingestExecutor` 线程池执行：
- `parse(String docId)`
- `chunk(String docId)`
- `ingest(String docId)` — 完整流水线
- `vectorize(String docId)`
- `incrementalVectorize(String docId)`
- `vectorizeMetadata(String docId)`
- `reParse(String docId)`
- `reExtractMetadata(String kbId)`

### 4.3 自代理绕过 AOP 失效
由于 `@Async` 基于 Spring AOP 代理，同类内部 `this.ingest()` 自调用会绕过代理导致异步失效。本类采用 **自注入代理对象** 解决：
```java
@Autowired @Lazy
private DocumentService self;
```
所有内部异步触发均走 `self.ingest(docId)` / `self.extractAndSaveMetadata(doc)`。`updateStatus()` 刻意不走代理（单条 UPDATE 无需事务）。`extractAndSaveMetadata()` 标注 `@Transactional`，也通过 `self.` 调用以确保事务生效。

### 4.4 批量提交
- `batchVectorize(List<String> docIds)`：循环 `self.ingest(docId)` 逐篇提交到线程池
- `vectorizeAll(String kbId)`：查 `findByKbIdAndStatusIn(kbId, [UPLOADED, PARSED, CHUNKED, PARSE_FAILED, CHUNK_FAILED, VECTOR_FAILED])` 全部待处理文档，逐篇 `self.ingest()`

上传 `upload()` 方法**同步执行**（无 `@Async`），保证 INSERT 立即提交，异步入库由后续 `self.ingest()` 触发。

---

## 五、解析器 SPI / 分发机制（MinerU vs Local 选择与回退）

### 5.1 SPI 接口
`org.pomelo.core.parser.DocumentParser` 接口定义：
- `ParsedDocument parse(Path file, String fileName)` — 基础抽象方法
- `default parse(Path, String, String objectPath)` — 带文件来源信息（MinerU 云端生成预签名 URL 用），默认忽略 objectPath
- `default parse(Path, String, String objectPath, String docId)` — 带文档 ID（MinerU 结果 ZIP 图片上传 OSS 路径隔离用），默认调用三参数版
- `boolean supports(String fileName)` — 是否支持该文件类型

### 5.2 分发器
`ParserDispatcher.parse(file, fileName, objectPath, docId)` 决策逻辑：
1. 读 `@Value("${pomelo.mineru.enabled}")`（默认 `pomelo.mineru.enabled=true`）
2. 若 `mineruEnabled && mineruParser.supports(fileName)`：优先用 MinerU；**捕获异常后仅 `log.warn`，回退到 LocalParser**
3. 否则直接用 `localParser.parse(file, fileName)`；失败抛 `RuntimeException("文档解析失败: ...")`

注意：**不是真正的 Java SPI（`ServiceLoader`）**，而是 Spring `@Component` 注入 + 分发器 if/else 的策略分发，两个解析器均为 Spring Bean（`MineruHttpParser`、`LocalParser`）。

### 5.3 MinerU 深度解析器 `MineruHttpParser`
支持文件类型（`supports()`）：`.pdf / .docx / .doc / .ppt / .pptx / .xls / .xlsx`。

两种模式（由 `pomelo.mineru.mode` 配置，默认 `cloud`）：
- **cloud 模式**（官方云端 `https://mineru.net/api/v4`，`model-version=vlm`）
- **local 模式**（自建 MinerU API Server，`base-url` 默认 `http://localhost:8000`）

解析流程（`parse(file, fileName, objectPath, docId)`）：
1. `mineruClient.submitTask(file, fileName, objectPath)` 提交任务，返回 `TaskHandle(id, isBatch)`
2. `pollTask(handle, fileName)` 轮询：`POLL_INTERVAL=3` 秒，`MAX_POLLS=200` 次（最大约 600 秒），终态判断 `done/success/completed`（成功）或 `failed/error`（失败）
3. `mineruClient.extractResult(result, handle)` 提取状态与结果
4. 结果优先级：`Content List`（结构化，`parseContentList`，按 type 标注 `[表格]/[图片]/[公式]`）→ `Markdown`（`splitMarkdownEnhanced`，按标题分块、正则标注表格/公式）→ `resultUrl`（`downloadResultMarkdown` 下载 ZIP 取 `full.md`）

### 5.4 MinerU HTTP 客户端 `MineruClient`
配置项：`mode`、`local.base-url`、`cloud.base-url`、`cloud.api-key`、`cloud.model-version`、`tier`、`timeout-seconds=600`。`HttpClient` 连接超时 30 秒，cloud 模式请求带 `Authorization: Bearer {api-key}`。

cloud 模式两种提交方式：
- **URL 模式**：文件已有公网 URL。`POST /extract/task {url, model_version}` → `task_id`；查询 `GET /extract/task/{task_id}`。前提是 `objectPath` 非空且能生成非 localhost 的 OSS 预签名 URL（`ossStorage.getPresignedUrl(objectPath, 7*24*3600)`，有效期 7 天）
- **上传模式**：无公网 URL 时。`POST /file-urls/batch {files:[{name}], model_version}` → `batch_id + file_urls[]`；`PUT {file_urls[0]}` 上传文件字节（系统自动提交解析）；查询 `GET /extract-results/batch/{batch_id}`，结果在 `data.extract_result[0]`

local 模式（4.1 Tasks API）：`POST /v1/tasks {tier, files:[{file_name, bytes}], output_formats:[default]}` → `task_id` + `files[0].upload.url`；`PUT` 上传文件（`application/octet-stream`）；查询 `GET /v1/tasks/{task_id}`，结果在 `files[0].result_url`。

结果 ZIP 处理（`downloadResultMarkdown(resultUrl, docId)`）：解压取 `full.md`（备选任意 `.md`），同时将 ZIP 内图片（`.jpg/.jpeg/.png/.gif/.svg/.webp/.bmp`）上传到 OSS 路径 `{docId}/images/{filename}`，并用正则 `!\[[^\]]*\]\(([^)]+)\)` 把 Markdown 中图片相对路径替换为代理 URL `/api/doc/image/{docId}/{filename}`（由 `DocumentController.getImage` 提供图片代理，带 86400 秒缓存）。

### 5.5 本地兜底解析器 `LocalParser`
支持文件类型（`supports()`）：`.pdf / .docx / .txt / .md / .markdown`。
- **PDF**：`Apache PDFBox 3.0.8`，`Loader.loadPDF(file)`，逐页 `PDFTextStripper`（`setStartPage/setEndPage`），按空行 `\n\s*\n` 切段，每段为 `ParsedBlock(text, page)`（page 为 1-based 页码）
- **DOCX**：`Apache POI 6.0.0`，`XWPFDocument` 遍历 `getParagraphs()`，按段落提取文本（page=0）
- **TXT/MD**：`Files.readString` 后按空行切段（page=0）
- 不支持类型抛 `IllegalArgumentException`

---

## 六、切块策略（三种切块器选择与参数）

### 6.1 策略接口与分发
`ChunkStrategy` 接口：`String name()` + `List<TextChunk> chunk(ParsedDocument doc, int chunkSize, int overlap)`。

`ChunkerDispatcher.chunk(doc, strategy, chunkSize, overlap)` 用 switch 表达式按 `strategy` 字符串选择：
- `"heading"` → `HeadingChunker`
- `"qa"` → `QaChunker`
- 默认（`"general"` 或 null）→ `RecursiveCharChunker`

参数来源：知识库实体 `KnowledgeBase` 的 `chunkStrategy`、`chunkSize`、`chunkOverlap`。`KnowledgeBaseService.create()` 默认值：`chunkSize=500`、`chunkOverlap=50`、`chunkStrategy="general"`、`topK=8`、`rerankTopK=4`、`similarityThreshold=0.3`、`autoVectorize=false`。`application.yml` 中 `pomelo.chunk.size=500`、`pomelo.chunk.overlap=50`（作为 `RecursiveCharChunker` 的 `@Value` 兜底默认，但实际切片用的是知识库配置传入的值）。

### 6.2 递归字符切块器 `RecursiveCharChunker`（general）
分隔符层次（优先级从高到低）：
```java
SEPARATORS = {"\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", " ", ""}
```
算法 `splitRecursive(text, size, overlap, sepIdx)`：
- 若 `text.length() <= size`，直接作为一块返回
- 否则用 `SEPARATORS[sepIdx]` 分割，贪心拼接直到超过 `size`，保留 `overlap`（把上一块尾部 `overlap` 字符带入下一块开头）
- 若当前分隔符切不开且仍超长，递归用下一级分隔符 `sepIdx+1`
- 所有分隔符用尽（`sepIdx >= SEPARATORS.length`）后按字符硬切：步长 `size - overlap`
- 输出 `TextChunk(text, block.page(), index)`，index 为全局递增序号

### 6.3 标题切块器 `HeadingChunker`（heading）
标题识别正则（`HEADING`）：
```
^(#{1,6}\s.+|第[\d一二三四五六七八九十百]+[章节条部分]|\d+\.\s|\([一二三四五六七八九十]+\)|[一二三四五六七八九十]+、)
```
识别 Markdown `#`~`######` 标题、中文编号（第X章/节、一、（一）、1.）。遇新标题先保存上一段；单段超过 `chunkSize` 时调 `splitLong()` 按字符步长 `size - overlap` 兜底切分，并保留标题前缀 `currentHeading + "\n"`。

### 6.4 QA 切块器 `QaChunker`（qa）
使用 langchain4j `ChatModel`（构造注入），将全文按 `BATCH_SIZE=4000` 字符分批，每批调用一次 LLM 生成 `MAX_QA_PER_BATCH=8` 个问答对。Prompt 要求返回 JSON 数组 `[{q, a}]`，解析后每个 QA 对格式化为 `"Q: 问题\nA: 答案"` 作为一个切片。防无限生成：`result.size() >= MAX_QA_PER_BATCH * 5`（40）时停止。成本高，适合 FAQ/客服知识库。

### 6.5 数据结构
- `ParsedBlock(text, page)` record — 解析最小单元，page 为 1-based（0 表示无分页）
- `ParsedDocument(totalPages, blocks)` record — `of(blocks)` 自动计算 totalPages
- `TextChunk(text, page, index)` record — 切块结果

---

## 七、向量化与入 Milvus 的细节

### 7.1 嵌入服务 `EmbeddingService`
- 封装 langchain4j `EmbeddingModel`
- `embedBatch(List<TextChunk>)`：将每个 chunk 的 `text()` 转为 `TextSegment.from(text)`，调 `embeddingModel.embedAll(segments)`，返回 `List<float[]>`（`Embedding::vector`），与输入同序
- `embedQuery(String query)`：单条查询向量化，调 `embeddingModel.embed(query)`

### 7.2 嵌入模型实现 `OllamaNativeEmbeddingModel`
继承 langchain4j `DimensionAwareEmbeddingModel`，**直接调 Ollama 原生 `/api/embed`（input 为字符串而非数组），绕过 0.40.x runner 不支持的 `/tokenize` 端点**。
- `doEmbed(EmbeddingRequest)`：逐条 `embedSingle(input)`，但用 `Executors.newFixedThreadPool(8)` 并发（`pool.invokeAll(tasks)`），按序收集结果，兼顾可靠性与速度
- `embedSingle`：`POST {baseUrl}/api/embed`，body `{"model": modelName, "input": input}`（字符串），解析 `embeddings[0]` 为 `float[]`
- `knownDimension()` 返回构造传入的 `dimension`
- 实例化（`LlmConfig.embeddingModel()`）：`provider=ollama` 时用 `new OllamaNativeEmbeddingModel(ollamaBaseUrl, ollamaEmbedModel, ollamaEmbedDimension, Duration.ofSeconds(timeoutSeconds))`

### 7.3 模型与维度配置（`LlmConfig` + `application*.yml`）
- dev 环境：`provider=ollama`，`ollama.base-url=http://localhost:11434`，`chat-model=qwen2.5:7b`，`embed-model=bge-m3`，`embed-dimension=1024`（顶层 `pomelo.llm.embed-dimension=1024`）
- 故嵌入向量维度 = **1024 维**（bge-m3）
- 非 ollama provider 时使用 `OpenAiEmbeddingModel.builder().dimensions(embedDimension)`（默认 1024）
- `temperature=0.7`、`max-tokens=2048`、`timeout-seconds=120`

### 7.4 Milvus 客户端配置 `MilvusConfig`
`@Bean MilvusClientV2 milvusClientV2()`，连接 `http://{host}:{port}`（默认 `localhost:19530`），用 `ConnectConfig.builder().uri(uri).build()`。

### 7.5 Milvus Collection Schema（`MilvusVectorStore`）
每个知识库对应一个独立 Collection，命名 `collectionPrefix + kbId.replace("-","_")`（`pomelo_` 前缀，UUID 的 `-` 替换为 `_`，因 Collection 名只允许字母数字下划线）。

Schema 字段（`ensureCollection` 中定义）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Int64 | 主键，`autoID=true` 自增 |
| `vector` | FloatVector | 维度 = `pomelo.llm.embed-dimension`（dev=1024） |
| `kb_id` | VarChar(64) | 知识库 ID |
| `doc_id` | VarChar(64) | 文档 ID |
| `doc_name` | VarChar(255) | 文档名 |
| `page` | Int32 | 页码 |
| `chunk_index` | Int32 | 切片序号 |
| `text` | VarChar(8192) | 切片文本（超 8000 字符截断） |
| `vec_type` | VarChar(32), nullable | 向量类型：`content`/`summary`/`keywords` |
| `chunk_id` | VarChar(64), nullable | 切片 ID（对应 doc_chunk.id，用于增量删除） |

### 7.6 索引与度量
- 索引类型：**HNSW**（`IndexParam.IndexType.HNSW`）
- 距离度量：**COSINE**（`IndexParam.MetricType.COSINE`）
- HNSW 参数：`M=16`、`efConstruction=200`（`extraParams=Map.of("M",16,"efConstruction",200)`）
- 一致性级别：`ConsistencyLevel.STRONG`
- 维度自愈：`ensureCollection` 检查已有 Collection 的 `vector` 维度，若与当前配置 `dimension` 不匹配则**自动 dropCollection 重建**

### 7.7 写入与检索
- `insertWithChunkIds(kbId, docId, docName, chunkIds, chunks, vectors)`：构造 `List<JsonObject>`（Gson），`vec_type="content"`，`text` 超 8000 截断，`client.insert(InsertReq...)` 批量插入
- `insertMetadataVector(...)`：插入单条元数据向量（`page=0, chunk_index=-1, vec_type=summary/keywords`）
- `search(kbId, queryVector, topK, vecType, excludeDocIds)`：按 `kb_id == "..."` 过滤，可选 `vec_type` 过滤，可选排除停用文档 `doc_id not in [...]`，`annsField=vector`，`outputFields` 含 doc_id/doc_name/page/chunk_index/text/vec_type/chunk_id
- 删除：`deleteByChunkId`（按 `chunk_id` 过滤，增量向量化用）、`deleteByDoc`（按 `doc_id` 过滤）、`deleteByDocAndType`（按 `doc_id + vec_type` 过滤）

---

## 八、元数据提取与 OSS 存储的作用

### 8.1 OSS 存储 `OssStorageService`
阿里云 OSS 对象存储（`OSSClientBuilder`），作用：
- **源文件存储**：上传时 `upload(objectPath, inputStream, size)`；对象路径 `{kbId}/{timestamp}{扩展名}`（`buildObjectPath`），文件名不入路径（存数据库 `doc.name`）
- **下载解析**：`downloadToTemp(objectPath)` 从 OSS 下载到本地临时文件（保留扩展名后缀，调用方负责删除），供 ParserDispatcher/MetadataExtractor 使用
- **预签名 URL**：`getPresignedUrl(objectPath, expirySeconds)` 生成公网可访问下载 URL，供 MinerU 云端 URL 模式直接下载文件（dev 中 OSS 公网可访问）；支持 `custom-domain` 域名替换
- **MinerU 图片托管**：MinerU 结果 ZIP 中的图片上传到 `{docId}/images/{filename}`，前端通过 `DocumentController.getImage`（`GET /api/doc/image/{docId}/{filename}`）代理访问，按扩展名返回 Content-Type，带 86400 秒缓存
- 生命周期：`@PostConstruct init()` 检查/创建 bucket，`@PreDestroy destroy()` 关闭 client
- `delete(objectPath)` 删除时 `NoSuchKey` 静默忽略

### 8.2 元数据提取 `MetadataExtractor`
按知识库字段配置（`MetaFieldConfig`）提取文档元数据，**四种提取方式**：

| ExtractType | 机制 | 示例字段 |
|---|---|---|
| `SYSTEM` | 系统自动获取（`extractSystem`）：file_name=文档名、create_time=createdAt 格式化、vector_status=状态标签 | file_name / create_time / vector_status |
| `DOC_PROPERTY` | 从文档内嵌元数据读取（`loadDocProperties`）：PDF 用 PDFBox `PDDocumentInformation`（title/author/subject/keywords/creator/producer/creationDate/modDate）；DOCX 用 POI `XWPFDocument.getProperties().getCoreProperties()`。需从 OSS 下载临时文件 | title / author / publish_date |
| `REGEX` | 正则匹配正文（`extractRegex`，`Pattern.MULTILINE`，有捕获组取 group(1) 否则 group(0)） | 自定义 |
| `LLM` | 大模型生成（`extractByLlm`）：**多字段合并为一次 prompt 调用降低成本**，正文截取 `MAX_LLM_CONTENT=3000` 字符，要求返回 JSON，从响应中 `extractJson`（找首尾 `{}`） | keywords / summary / topic / org / terms / metrics |

**默认 13 字段配置**（`KnowledgeBaseService.initDefaultFieldConfigs`）：file_name、title、author、org(LLM)、publish_date、publish_time、create_time、keywords(LLM, **vectorized=true**)、summary(LLM, **vectorized=true**)、topic(LLM)、terms(LLM)、metrics(LLM)、vector_status。

其中 `MetaFieldConfig.vectorized=true` 的字段（keywords/summary）会在 `doVectorize` 完成后由 `vectorizeMetadata(docId)` 单独向量化入 Milvus（`vec_type=keywords/summary`），实现摘要/关键词级别的语义检索。提取通过 `self.extractAndSaveMetadata(doc)`（`@Transactional`）调用，先 `deleteByDocId` 清旧再 `batchInsert`。

**作用总结**：OSS 解决源文件公网可访问（MinerU 云端 URL 模式必需）与图片托管；元数据提取为文档建立结构化属性，支持按属性检索、列表展示，并通过向量化摘要/关键词扩展多路召回。

---

## 九、BM25 索引的作用

### 9.1 实现 `org.pomelo.core.rag.Bm25Indexer`
- **内存倒排索引**，按知识库隔离（`ConcurrentHashMap<String, Bm25Index> indexes`，kbId → 索引）
- BM25 参数：`K1=1.5`、`B=0.75`
- 分词（`tokenize`）：用正则 `[\s，。！？、；：,.!?;:\n\r]+` 切分；中文拆 bigram（相邻 2 字），英文（ASCII 占多数）整体作为 token，小写化
- BM25 公式：`score += idf(term) * (tf * (K1+1)) / (tf + K1*(1-B + B*doc.length/avgDocLength))`，其中 `idf = log(1 + N/n)`（N=文档总数，n=含该 term 文档数）
- 索引结构：`docs`（chunkId→DocInfo）、`docFreq`（term→文档频率）、`totalLength`（用于 avgDocLength）

### 9.2 生命周期与增量更新
- **懒加载**：首次查询时 `computeIfAbsent(kbId, this::buildIndex)` 全量构建，只索引 `ChunkStatus.VECTORIZED` 的切片
- **重建**：`rebuild(kbId)` 在 `doVectorize` 成功后调用（每篇文档入库完成后重建该知识库索引）
- **增量更新**：`updateChunk(kbId, chunk)`（切片编辑 `editChunk` 时调用，先 remove 再 add）；`removeChunk(kbId, chunkId)`（文档删除时调用）
- `clear(kbId)` 知识库删除时清空

### 9.3 入库流水线中的触发点
- `doVectorize` 末尾：`bm25Indexer.rebuild(doc.getKbId())`（失败仅 warn，不影响主流程）
- `editChunk`：`bm25Indexer.updateChunk(...)`（切片内容编辑后增量更新）
- `delete`：`bm25Indexer.removeChunk(...)`（逐切片删除）

### 9.4 下游消费：混合检索 RRF 融成（`service.rag.RetrievalService`）
BM25 索引在**检索阶段**与向量检索结果通过 **RRF（Reciprocal Rank Fusion）** 融合，实现稀疏+稠密混合检索：
- 向量召回三路：正文 `content`（topK）、摘要 `summary`（topK/2+1）、关键词 `keywords`（topK/2+1），均按 `vec_type` 过滤、排除停用文档
- BM25 召回：`bm25Indexer.search(kbId, query, topK*2)`，过滤停用文档
- RRF 融合权重（`RRF_K=60.0`，得分 = `weight / (RRF_K + rank + 1)`）：
  - 向量正文 weight=**1.0**
  - 向量摘要 weight=**0.5**
  - 向量关键词 weight=**0.5**
  - BM25 weight=**0.8**
- 融合后按 `similarityThreshold`（默认 0.3）或 `rrfScore > 0.01` 过滤，按 rrfScore 降序取 topK

### 9.5 作用总结
BM25 索引为知识库提供**关键词级稀疏召回**能力，弥补纯向量语义检索在精确术语匹配（如编号、专有名词、代码标识符）上的不足；与向量稠密检索经 RRF 融合后，兼顾语义相似与关键词精确匹配，提升召回质量。索引构建在入库流水线末尾自动完成，对最终 `INDEXED` 状态无强依赖（失败不阻断入库）。

---

## 十、关键技术要点汇总（论文引用用）

| 维度 | 实现 |
|---|---|
| 框架 | Spring Boot 4.1.2 + Java 21 |
| 异步 | `@EnableAsync` + `ThreadPoolTaskExecutor`（ingestExecutor: core=2/max=4/queue=50/CallerRunsPolicy），自代理 `@Lazy self` 解决 AOP 自调用失效 |
| 持久化 | MyBatis 3.5.19（`*Mapper` 接口 + XML），MySQL |
| 解析（深度） | MinerU（cloud `https://mineru.net/api/v4` model-version=vlm / local `http://localhost:8000` 4.1 API），JDK HttpClient，轮询 3s×200 次 |
| 解析（兜底） | PDFBox 3.0.8（PDF 按页）、POI 6.0.0（DOCX 按段）、Files.readString（TXT/MD） |
| 切块 | 递归字符（分隔符层次 + overlap）/ 标题层级（Markdown+中文编号正则）/ QA（LLM 生成问答对），默认 chunkSize=500 overlap=50 |
| 嵌入 | langchain4j 1.23.0 `DimensionAwareEmbeddingModel`；Ollama 原生 `/api/embed`（字符串模式避 /tokenize），8 并发线程池；模型 bge-m3，维度 1024 |
| 向量库 | Milvus SDK 3.0.12 `MilvusClientV2`；每 KB 一个 Collection；HNSW(M=16, efConstruction=200) + COSINE；STRONG 一致性 |
| 对象存储 | 阿里云 OSS SDK 3.18.5（源文件 + MinerU 图片托管 + 预签名 URL） |
| 元数据 | 四方式（SYSTEM/DOC_PROPERTY/REGEX/LLM 批量合并），默认 13 字段，keywords/summary 向量化 |
| 混合检索 | BM25 内存倒排（K1=1.5, B=0.75, 中文 bigram）+ 向量三路召回 + RRF 融合（K=60，权重 content1.0/summary0.5/keywords0.5/bm250.8） |
| 去重/增量 | 文件 SHA-256、切片 contentHash；切片级状态机 PENDING/VECTORIZED/EDITED/FAILED/STALE 支持增量向量化 |

---

## 十一、流水线异常处理与解耦特性

1. **阶段解耦**：`parse`/`chunk`/`vectorize`/`ingest` 均可独立触发，`ingest` 内部用 `if (parsedContent==null)` / `if (chunkCount==0 || status==PARSED)` 判断跳过已完成阶段，支持断点续跑。
2. **失败隔离**：每个阶段 try/catch 后置对应失败状态（PARSE_FAILED/CHUNK_FAILED/VECTOR_FAILED），errorMsg 截断 900 字符；元数据提取、元数据向量化、BM25 重建失败均仅 warn 不阻断主流程。
3. **幂等性**：切片前 `deleteByDocId` 删旧切片；向量化前过滤脏切片；元数据提取前 `deleteByDocId` 清旧；Milvus 写入带 `chunk_id` 支持按切片增量删除重嵌。
4. **维度自愈**：Milvus Collection 维度不匹配时自动 drop 重建，避免切换嵌入模型后维度冲突。
