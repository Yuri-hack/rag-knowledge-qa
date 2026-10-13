# Milvus 2.3.4 → 2.5.x 升级 spike checklist

> 触发条件（2026-10-13 检索基线后修订）：**不是现在**。检索度量显示 gold 在 top-50 的命中率 100%，
> 召回无 headroom；混合检索若要上，应按"排序优化"立项并单独度量收益。
> 元数据过滤（主体/年份/废止状态）**在 2.3.4 上即可实施**（标量字段 + 表达式），不需要升级。
> 本文档留作将来真要升级时的执行清单。

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
