# Milvus 2.3.4 → 2.5.x 升级 spike checklist

> **触发条件已成立（2026-10-14），升级进行中。**
>
> 原先的判断（2026-10-13）：*不是现在*——检索度量显示 gold 在 top-50 命中率 100%，召回无 headroom，
> 混合检索应按"排序优化"立项。**该判断的前提是"召回不足"，而实际暴露的是另一类问题：**
>
> 多跳实验（`results/multihop-bridge-20261014.md`）证明：**稠密向量对无意义 token（资产编号/工单号）
> 结构上无法精确匹配**——编号的嵌入与"含大量编号的 chunk"近乎正交，MH204 的第二跳因此失败
> （agent 已正确取出编号 `YC-HY-2026-0002`，却检索不到对应登记行）。
> 这不是排序问题（调大 K、加 reranker 都无解），而是**能力缺失**，只能靠词法/稀疏通道补齐。
>
> 因此升级目的相应修正为：**用 Milvus 原生 BM25 稀疏 + 服务端 RRF 融合，替代手写的进程内词法通道**
> （手写版见 `lexical/`，作为 A/B 对照保留）。
>
> 元数据过滤（主体/年份/废止状态）仍**在 2.3.4 上即可实施**（标量字段 + 表达式），与本升级无关。

## 一、为什么升级（如实施）

| 收益 | 说明 |
|---|---|
| 原生 BM25 / sparse 向量 | 2.3.4 无稀疏向量与全文检索能力 |
| 服务端混合检索 + RRF | `AnnSearchRequest`(dense) + `AnnSearchRequest`(sparse) + `RRFRanker`，应用侧不写融合代码 |
| 单存储双路 | 相比 ES 方案少一套基建与双写一致性负担 |
| 更强的 analyzer 体系 | 中文分词质量是 BM25 路效果的决定变量（需实测选定） |

## 二、改动面（已核实：仅 4 个文件）

```
config/MilvusConfig.java
vector/init/MilvusVectorInitializer.java    ← schema / index 定义
vector/impl/MilvusVectorServiceImpl.java    ← insert / search
service/impl/HealthServiceImpl.java         ← 健康检查
pom.xml: milvus.version 2.3.4 → 2.5.x（SDK 与服务端版本需对齐官方兼容矩阵）
```

## 三、Spike（先跑通最小闭环，再改业务代码）

1. 新容器 + 全新 volume（不要原地升级数据目录）：
   `milvusdb/milvus:v2.5.x`，保留旧容器/tag 以便回滚。
2. 建最小 collection 验证 SDK 能力：
   - `dense` FLOAT_VECTOR(1024) + HNSW/IP
   - `text` VARCHAR（开 analyzer）
   - `sparse` SPARSE_FLOAT_VECTOR + `SPARSE_INVERTED_INDEX`(IP)
   - `Function`：text → sparse（BM25）
   - 标量字段：`document_id` / `chunk_index` / `scope` / `year` / `status`
3. 插 100 篇真实语料 → 验证三件事：
   - 纯稠密检索结果与现网一致（回归基线）
   - 纯稀疏检索：中文 query（"补卡几次"、"1:1.5"）能命中预期 chunk
   - 混合检索 + RRFRanker：结果条数、排序、`expr` 过滤同时生效
4. 记录 SDK 破坏性点（迁移清单）：`FieldType` 稀疏/analyzer setter、`Function` 构造、
   `SearchParam` 的 ranker、`SearchResultsWrapper` 取值方式、`MilvusServiceClient` 是否废弃。

## 四、切换与护栏

- 混合检索走**配置开关**（如 `rag.retrieval.hybrid.enabled=false` 默认关），dense-only 保持主路径；
- 元数据过滤单独开关（`rag.retrieval.metadata-filter`），先软加权后硬过滤；
- 回滚 = 换回 2.3.4 镜像 + 旧 volume + 关闭开关；
- 每次重建索引统一走 `upload_corpus.py`（已固化流程）。

## 五、验收标准（必须用度量，不接受"感觉更好"）

| 指标 | 当前基线 | 升级后目标 |
|---|---|---|
| hit@5（38 题） | 36/38 | ≥ 现状 |
| chunkRecall@5 | 0.818 | 提升（多事实题靠排序腾槽位） |
| MRR | 0.732 | ≥ 现状 |
| 端到端（三臂 × 44 题） | 待重跑 | 不劣化，且需给出成本对比 |

前置依赖：`retrieval_metrics.py` 与 `retrieval-annotations.json`（已就绪）。

---

# 附：Spike 执行结果（2026-10-14）

## 环境

```bash
docker run -d --name rag-milvus-25-dev -p 19531:19530 \
  -e ETCD_USE_EMBED=true -e COMMON_STORAGETYPE=local \
  -v dev_milvus_data_25:/var/lib/milvus \
  milvusdb/milvus:v2.5.4 milvus run standalone
```

按清单第三节要求：**新容器 + 全新 volume，不原地升级数据目录**；旧 2.3.4 容器（19530）与旧卷保留以便回滚。
启动日志的 `milvus-session` 解析告警是启动期噪声，`Milvus Proxy successfully initialized and ready to serve!` 后即可用。

## 结论一：必须迁移到 `MilvusClientV2`（改变了改动性质）

实测 SDK 2.5.4 两套 API 的能力边界：

| 能力 | 旧 API（`MilvusServiceClient` + `io.milvus.param.*`） | 新 API（`MilvusClientV2`） |
|---|---|---|
| 稀疏字段 `SPARSE_FLOAT_VECTOR` | ❌ `FieldType` 无此类型 | ✅ |
| BM25 `Function`（服务端由 text 生成稀疏向量） | ❌ `CreateCollectionParam` 无 Function | ✅ |
| 稀疏插入 | ❌ | ✅ |
| 混合检索 ranker | ✅（`io.milvus.param.dml.ranker` 有 RRFRanker/WeightedRanker） | ✅ `hybridSearch` + `RRFRanker` |

**因此本升级不是"加几行"，而是把向量层重写为 V2 API。** 清单第二节估的改动面（4 个文件）依然准确：

```
config/MilvusConfig.java
vector/init/MilvusVectorInitializer.java
vector/impl/MilvusVectorServiceImpl.java
service/impl/HealthServiceImpl.java
pom.xml: milvus.version 2.3.4 → 2.5.4（旧代码在该 SDK 下编译通过，向后兼容）
```

## 结论二：中文 analyzer 是决定变量（清单标注"需实测选定"→ 已选定）

同一批语料、同一查询，两种 analyzer 下的纯稀疏（BM25）命中情况：

| 查询 | `default` analyzer | `{"type":"chinese"}` |
|---|---|---|
| `YC-HY-2026-0003`（业务编号） | ✅ 命中（score 4.03） | ✅ 命中（4.70） |
| `1:1.5` | ✅ 命中 | ✅ 命中 |
| **`补卡几次`**（中文子词） | ❌ **无结果** | ✅ 命中 |
| **`上海年假`** | ❌ **无结果** | ✅ 命中 |

**决定：文本字段统一用 `analyzerParams = {"type":"chinese"}`。** 默认 analyzer 对中文子词查询完全召回失败，
而中文子词查询正是"员工口语化提问"的常态。

## 结论三：其他实测 API 约束（照抄，避免重复试错）

1. **BM25 Function 输出字段的索引 metric 必须是 `BM25`**（用 `IP` 会被服务端拒绝：
   `index metric type of BM25 function output field must be BM25, got IP`）；
2. Function 用 `.name(...)`（不是 `functionName`）；
3. `AnnSearchReq.builder().vectorFieldName(x).vectors(List<BaseVector>).topK(n).params("{}")`（是 `vectors`，非 `data`）；
4. `new RRFRanker(60)`（无 builder）；`WeightedRanker` 同样直接构造；
5. 全文检索的数据用 `new EmbeddedText(queryText)`，稠密用 `new FloatVec(List<Float>)`；
6. `MilvusClientV2` **未实现 `AutoCloseable`**，需显式 `client.close()`；
7. `InsertReq.data(...)` 收 `List<com.google.gson.JsonObject>`（gson），不是 `Map`；
8. 当前 Milvus **不存正文**（正文从 MySQL 取），而 BM25 需要文本 → **必须新增 `text` 字段存 chunk 正文**。

参考实现（已跑通）：`src/test/java/io/github/d3kstra/rag_knowledge_qa/vector/MilvusV2SpikeTest.java`
（`MILVUS_SPIKE=true ./mvnw test -Dtest=MilvusV2SpikeTest` 显式触发，默认跳过）。

## 结论四：RRF 可能稀释精确匹配（待复验）

混合检索用 RRF 融合的是**排名**而非分数：某文档"稀疏第 1、稠密第 50"的融合分（≈0.0164+0.0091=0.0255）
可能低于"两路都第 5"（≈0.0308）。而 sparse 通道对资产编号是强信号（实测 score 4.7）。
所以升级后必须复验精确匹配是否仍能进最终结果；若被稀释，改用 `WeightedRanker` 提高稀疏权重，或按标量字段做精确过滤。
**这是本次升级最需要盯的风险点。**

---

# 附：迁移实施记录（2026-10-14，4+1 个文件）

## 改动清单

| 文件 | 关键改动 |
|---|---|
| `config/MilvusConfig.java` | bean `MilvusServiceClient` → `MilvusClientV2`；`ConnectConfig.builder().uri("http://host:port")`；**`@Bean(destroyMethod="close")`**（V2 未实现 `AutoCloseable`） |
| `vector/init/MilvusVectorInitializer.java` | 全量 V2 重写；新增 `text`(VarChar 4096, enableAnalyzer, `{"type":"chinese"}`) + `sparse`(SparseFloatVector) + BM25 Function；索引 `vector`=HNSW/IP、`sparse`=SPARSE_INVERTED_INDEX/**BM25** |
| `vector/VectorStoreService.java` | 新增 `hybridSearch(float[] queryVector, String queryText, int topK)`；`searchSimilarVectors` 保留纯稠密 |
| `vector/impl/MilvusVectorServiceImpl.java` | 插入改 gson `JsonObject` 且**必带 `text`**（BM25 输入，稀疏向量由服务端生成）；flush 改 `FlushReq`；新增 `hybridSearch` = 两个 `AnnSearchReq`（dense=`FloatVec`，sparse=`EmbeddedText`）+ `RRFRanker(60)` |
| `service/impl/HealthServiceImpl.java` | `hasCollection(不存在的集合)+状态码` → `describeCollection(真实集合)` |
| `knowledge/impl/KnowledgeBaseServiceImpl.java` | 新增 `rag.search.hybrid.provider`（默认 `milvus`）：milvus 走 `hybridSearch` 并跳过 local 的合并/提权/兜底；local 路径逐字保留 |
| `application.yml` | `rag.search.hybrid.provider: milvus` |
| **`pom.xml`（清单外第 5 个文件）** | **显式声明 `com.alibaba:fastjson:1.2.83`** —— SDK 2.5.4 移除了该传递依赖，而 `rerank/impl/AliyunRerankerServiceImpl` 依赖它，不补则编译失败 |

## 新增的必须知道的语义变化（迁移风险）

1. **`similarity` 的语义变了**（milvus 通道下）：
   - provider=local → 稠密余弦（0~1，实测 0.5~0.9）；
   - provider=milvus → **RRF 融合分**（实测 0.01~0.05）。
   该值会进入 `agent/tool/KnowledgeSearchTool` 输出（`相似度=0.03`）——**agent 的覆盖性自检会读它**，
   可能把低分读成"不相关"而影响判断。`TongYiRAGServiceImpl` 的带标注上下文同理会打印它（该开关默认关闭）。
   `getMaxSimilarity` 走纯稠密，语义缓存阈值不受影响。
2. **健康检查语义收紧**：由"连得上"变为"`describeCollection(真实集合)` 必须成功"，
   集合缺失时 `/healthy/check` 返回 false（启动期并发调用可能瞬时 false）。
3. **IP vs COSINE 的隐含前提**：改用 HNSW/IP 依赖"嵌入已 L2 归一化"（`spring.ai.openai.embedding.normalize` 默认 true）。
   归一化一旦关闭，IP 不再等价余弦，`ChatOrchestrationServiceImpl` 的离题/意图阈值会漂移。
4. **`insertVectors` 返回值**由"Milvus 生成 ID"变为传入的 `chunk_id`（autoID=false 下同值），调用方未使用，无影响。

## 已知未处理（排期中）

- reranker 的 `fastjson → gson` 迁移（fastjson 1.2.x 有已知反序列化 CVE；gson 已在依赖树）；
- `spike_hybrid_default` / `spike_hybrid_chinese` 两个 spike 集合的清理；
- 若 A/B 显示 milvus 通道稀释了精确编号命中 → 改 `WeightedRanker` 抬高稀疏权重，或对业务编号走标量字段精确过滤。
