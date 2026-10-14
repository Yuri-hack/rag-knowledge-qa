package io.github.d3kstra.rag_knowledge_qa.vector;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Milvus 2.5 升级 spike（清单第三节）：只验证 SDK/服务端能力，不进业务代码。
 * 默认不跑；显式执行：MILVUS_SPIKE=true ./mvnw test -Dtest=MilvusV2SpikeTest
 *
 * 验证四件事：
 *  1. V2 API 能否建出 dense + sparse + BM25 Function + 标量字段的集合；
 *  2. 纯稠密检索可用（回归基线）；
 *  3. 纯稀疏（全文）检索：中文 query 能否命中预期 chunk；
 *  4. 混合检索 + RRFRanker 可用，且能得到服务端融合结果。
 * 另外对比 default 与 chinese 两种 analyzer 的中文检索效果（清单标注的决定变量）。
 */
@EnabledIfEnvironmentVariable(named = "MILVUS_SPIKE", matches = "true")
class MilvusV2SpikeTest {

    private static final String URI = "http://localhost:19531";
    private static final int DIM = 1024;

    /** 取自真实语料的样本（含业务编号、中文政策、比例数字） */
    private static final List<String> TEXTS = List.of(
            "YC-HY-2026-0003 | 整机 36 个月 | 电池 18 个月 | 屏幕 24 个月",
            "员工年假天数由职级与司龄共同决定，以《职级与假期对照表》为准。",
            "当月补卡次数不得超过 3 次，超出部分按事假处理。",
            "休息日加班调休比例为 1:1.5，法定节假日加班调休比例为 1:3。",
            "设备保修期自领用登记之日起算，超出期限的维修按有偿服务处理。",
            "上海子公司员工年假基准为 10 天，与北京总部不同。");

    private static final List<String> QUERIES = List.of(
            "YC-HY-2026-0003", "补卡几次", "1:1.5", "上海年假");

    private static List<Float> pseudoVector(String text) {
        Random random = new Random(text.hashCode());
        List<Float> vector = new ArrayList<>(DIM);
        double norm = 0;
        double[] raw = new double[DIM];
        for (int i = 0; i < DIM; i++) {
            raw[i] = random.nextGaussian();
            norm += raw[i] * raw[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < DIM; i++) {
            vector.add((float) (raw[i] / norm));
        }
        return vector;
    }

    @Test
    @DisplayName("spike：dense + BM25 sparse + Function + RRF 混合检索；对比 default/chinese analyzer")
    void spike() {
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder().uri(URI).build());
        try {
            runSpike(client, "spike_hybrid_default", null);
            runSpike(client, "spike_hybrid_chinese", Map.of("type", "chinese"));
        } finally {
            client.close();
        }
    }

    private void runSpike(MilvusClientV2 client, String collection, Map<String, Object> analyzerParams) {
        System.out.println("\n############ analyzer=" + (analyzerParams == null ? "default" : analyzerParams) + " ############");
        try {
            client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
        } catch (Exception ignored) {
            // 首次不存在
        }

        // ---- schema：dense + text(analyzer) + sparse + 标量字段 ----
        CreateCollectionReq.CollectionSchema schema = client.createSchema();
        schema.addField(AddFieldReq.builder().fieldName("id")
                .dataType(DataType.Int64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName("dense")
                .dataType(DataType.FloatVector).dimension(DIM).build());
        AddFieldReq.AddFieldReqBuilder<?, ?> textField = AddFieldReq.builder().fieldName("text")
                .dataType(DataType.VarChar).maxLength(4096).enableAnalyzer(true);
        if (analyzerParams != null) {
            textField.analyzerParams(analyzerParams);
        }
        schema.addField(textField.build());
        schema.addField(AddFieldReq.builder().fieldName("sparse")
                .dataType(DataType.SparseFloatVector).build());
        schema.addField(AddFieldReq.builder().fieldName("document_id")
                .dataType(DataType.VarChar).maxLength(128).build());
        schema.addField(AddFieldReq.builder().fieldName("chunk_index")
                .dataType(DataType.Int32).build());
        schema.addFunction(CreateCollectionReq.Function.builder()
                .name("bm25_text")
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of("text"))
                .outputFieldNames(List.of("sparse"))
                .build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .collectionSchema(schema)
                .indexParams(List.of(
                        IndexParam.builder().fieldName("dense")
                                .indexType(IndexParam.IndexType.HNSW)
                                .metricType(IndexParam.MetricType.IP).build(),
                        IndexParam.builder().fieldName("sparse")
                                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                                .metricType(IndexParam.MetricType.BM25).build()))
                .build());
        System.out.println("  集合创建成功（dense+sparse+Function+标量）");

        // ---- insert ----
        List<com.google.gson.JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < TEXTS.size(); i++) {
            com.google.gson.JsonObject row = new com.google.gson.JsonObject();
            row.addProperty("id", (long) i);
            row.add("dense", new com.google.gson.Gson().toJsonTree(pseudoVector(TEXTS.get(i))));
            row.addProperty("text", TEXTS.get(i));
            row.addProperty("document_id", "DOC_" + i);
            row.addProperty("chunk_index", i);
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(collection).data(rows).build());
        System.out.println("  插入 " + rows.size() + " 条");

        for (String query : QUERIES) {
            System.out.println("  --- 查询: " + query + " ---");

            SearchResp dense = client.search(SearchReq.builder()
                    .collectionName(collection)
                    .data(List.of(new FloatVec(pseudoVector(query))))
                    .annsField("dense").topK(2)
                    .outputFields(List.of("text")).build());
            System.out.println("    [dense]  " + top1(dense));

            SearchResp sparse = client.search(SearchReq.builder()
                    .collectionName(collection)
                    .data(List.of(new EmbeddedText(query)))
                    .annsField("sparse").topK(2)
                    .outputFields(List.of("text")).build());
            System.out.println("    [sparse] " + top1(sparse));

            SearchResp hybrid = client.hybridSearch(HybridSearchReq.builder()
                    .collectionName(collection)
                    .searchRequests(List.of(
                            AnnSearchReq.builder().vectorFieldName("dense")
                                    .vectors(List.of(new FloatVec(pseudoVector(query)))).topK(5)
                                    .params("{}").build(),
                            AnnSearchReq.builder().vectorFieldName("sparse")
                                    .vectors(List.of(new EmbeddedText(query))).topK(5)
                                    .params("{}").build()))
                    .ranker(new RRFRanker(60))
                    .topK(2)
                    .outFields(List.of("text"))
                    .build());
            System.out.println("    [hybrid] " + top1(hybrid));
        }
    }

    /** 打印第一条结果的 id 与文本，便于人工判断命中 */
    private static String top1(SearchResp resp) {
        if (resp == null || resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return "(无结果)";
        }
        List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
        if (results.isEmpty()) {
            return "(无结果)";
        }
        SearchResp.SearchResult first = results.get(0);
        Object text = first.getEntity() == null ? null : first.getEntity().get("text");
        return "score=" + String.format("%.3f", first.getScore())
                + " id=" + first.getId() + " text=" + (text == null ? "" : text.toString().substring(0, Math.min(40, text.toString().length())));
    }
}
