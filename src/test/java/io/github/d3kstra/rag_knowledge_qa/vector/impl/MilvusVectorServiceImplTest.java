package io.github.d3kstra.rag_knowledge_qa.vector.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务编号抽取单测。
 *
 * 动机：Milvus 原生 BM25 稀疏腿对 {@code YC-XX-YYYY-NNNN} 这类编号的区分性 token 不参与匹配
 * （实测三个不同编号返回同一条、同分数 6.88），因此改用数组字段 {@code codes} + ARRAY_CONTAINS
 * 精确过滤。写入与检索两侧共用 {@link MilvusVectorServiceImpl#extractCodes(String)}，
 * 这里覆盖两侧共用的抽取口径（见 results/milvus25-upgrade-20261015.md）。
 */
class MilvusVectorServiceImplTest {

    @Test
    @DisplayName("抽取：资产编号整体命中并统一大写")
    void extractsAssetCode() {
        assertThat(MilvusVectorServiceImpl.extractCodes("YC-HY-2026-0002"))
                .containsExactly("YC-HY-2026-0002");
        assertThat(MilvusVectorServiceImpl.extractCodes("资产编号 yc-hy-2026-0002。"))
                .containsExactly("YC-HY-2026-0002");
    }

    @Test
    @DisplayName("抽取：带斜杠的型号代码保留原样（仅大写化）")
    void extractsSlashCode() {
        assertThat(MilvusVectorServiceImpl.extractCodes("设备保修条款（型号 MRX33CH/A）"))
                .containsExactly("MRX33CH/A");
    }

    @Test
    @DisplayName("抽取：字母数字混排且含连字符的型号")
    void extractsHyphenCode() {
        assertThat(MilvusVectorServiceImpl.extractCodes("ThinkPad X1 Carbon Gen 12（型号 21KC-S1M700）35 台"))
                .containsExactly("21KC-S1M700");
    }

    @Test
    @DisplayName("抽取：无编号的中文问句返回空（不影响普通检索路径）")
    void returnsEmptyWhenNoCode() {
        assertThat(MilvusVectorServiceImpl.extractCodes("补卡几次")).isEmpty();
        assertThat(MilvusVectorServiceImpl.extractCodes("研发中心那批 MacBook Pro 14 寸的整机保修期是多长时间？"))
                .isEmpty();
        assertThat(MilvusVectorServiceImpl.extractCodes("休息日加班调休比例是多少")).isEmpty();
    }

    @Test
    @DisplayName("抽取：多个编号去重且保序")
    void deduplicatesAndKeepsOrder() {
        List<String> codes = MilvusVectorServiceImpl.extractCodes(
                "YC-JJ-2026-0004 | 整机 36 个月 YC-JJ-2026-0004 YC-AF-2026-0012");
        assertThat(codes).containsExactly("YC-JJ-2026-0004", "YC-AF-2026-0012");
    }

    @Test
    @DisplayName("抽取：最多 8 个（与 schema 的 maxCapacity 一致）")
    void capsAtMaxCapacity() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            text.append("YC-HY-2026-%04d ".formatted(i));
        }
        assertThat(MilvusVectorServiceImpl.extractCodes(text.toString())).hasSize(8);
    }

    @Test
    @DisplayName("抽取：null/空白安全")
    void handlesNullAndBlank() {
        assertThat(MilvusVectorServiceImpl.extractCodes(null)).isEmpty();
        assertThat(MilvusVectorServiceImpl.extractCodes("   ")).isEmpty();
    }

    @Test
    @DisplayName("抽取：纯数字区间（如 1:1.5、2026-10-14）行为明确")
    void handlesNumericLikeTokens() {
        // 冒号不是分隔符 → 不抽；连字符日期会抽中（无害：只多一次精确过滤查询）
        assertThat(MilvusVectorServiceImpl.extractCodes("休息日加班调休比例为 1:1.5")).isEmpty();
        assertThat(MilvusVectorServiceImpl.extractCodes("2026-10-14 发布")).containsExactly("2026-10-14");
    }
}
