package io.github.yuri_hack.rag_knowledge_qa.splitter;

import io.github.yuri_hack.rag_knowledge_qa.splitter.impl.SlidingWindowTextSplitter;
import io.github.yuri_hack.rag_knowledge_qa.splitter.model.TextChunk;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分块回归：中文句读必须能切分（曾因只认英文句读导致整篇一个 chunk）。 */
class SlidingWindowTextSplitterTest {

    private static SlidingWindowTextSplitter splitter(int chunkSize, int overlap) {
        SlidingWindowTextSplitter splitter = new SlidingWindowTextSplitter();
        ReflectionTestUtils.setField(splitter, "chunkSize", chunkSize);
        ReflectionTestUtils.setField(splitter, "chunkOverlap", overlap);
        return splitter;
    }

    private static String chineseDoc(int sentences) {
        StringBuilder sb = new StringBuilder("# 测试制度\n\n");
        for (int i = 0; i < sentences; i++) {
            sb.append("这是第").append(i).append("条规定，用于验证中文分句能够正常切分并保持内容完整。");
        }
        return sb.toString();
    }

    @Test
    void 中文句读切出多块() {
        List<TextChunk> chunks = splitter(200, 50).splitText(chineseDoc(20), "测试制度", "DOC_1");
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void 块内容不丢句子且索引连续() {
        String content = chineseDoc(20);
        List<TextChunk> chunks = splitter(200, 50).splitText(content, "测试制度", "DOC_1");

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
            assertThat(chunks.get(i).getDocumentId()).isEqualTo("DOC_1");
            assertThat(chunks.get(i).getSentencesCount()).isGreaterThan(0);
        }
        // 首块与末块应分别覆盖文档开头与结尾的句子
        assertThat(chunks.get(0).getContent()).contains("这是第0条");
        assertThat(chunks.get(chunks.size() - 1).getContent()).contains("这是第19条");
    }

    @Test
    void 表格与多行文本按行切分不再黏成整篇() {
        StringBuilder sb = new StringBuilder("# 对照表\n\n| 职级 | 天数 |\n|---|---|\n");
        for (int i = 0; i < 30; i++) {
            sb.append("| P").append(i).append(" | ").append(i).append(" 天 |\n");
        }
        List<TextChunk> chunks = splitter(150, 30).splitText(sb.toString(), "对照表", "DOC_2");
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void 空内容返回空列表() {
        assertThat(splitter(200, 50).splitText("   ", "空文档", "DOC_3")).isEmpty();
    }

    @Test
    void 短文档保持单块() {
        List<TextChunk> chunks = splitter(1000, 200).splitText("只有一句话。", "短文档", "DOC_4");
        assertThat(chunks).hasSize(1);
    }

    @Test
    void 小数与比例数字不被句点切坏() {
        // 回归：ASCII 句点曾被当断句符，1:1.5 → "1:1." + "5"（再被过短句过滤丢掉）
        String content = "# 加班与调休管理制度\n\n"
                + "| 加班类型 | 调休比例 |\n| 工作日加班 | 1:1 |\n| 周末加班 | 1:1.5 |\n| 法定节假日加班 | 1:3 |\n\n"
                + "文字版（便于检索）：工作日 1 小时换 1 小时，周末 1 小时换 1.5 小时，法定节假日 1 小时换 3 小时。\n";
        List<TextChunk> chunks = splitter(400, 100).splitText(content, "加班与调休管理制度", "DOC_5");
        String merged = chunks.stream().map(TextChunk::getContent).reduce("", (a, b) -> a + " " + b);

        assertThat(merged).contains("1:1.5");
        assertThat(merged).contains("1.5 小时");
        assertThat(merged).doesNotContain("1:1. ");
        assertThat(merged).doesNotContain("1. 5");
    }

    @Test
    void 小数在长文档任意位置都不丢失() {
        StringBuilder sb = new StringBuilder("# 测试\n\n");
        for (int i = 0; i < 12; i++) {
            sb.append("第").append(i).append("条：折算系数为 0.5，加班时长按 1.5 倍计算。");
        }
        List<TextChunk> chunks = splitter(120, 20).splitText(sb.toString(), "测试", "DOC_6");
        String merged = chunks.stream().map(TextChunk::getContent).reduce("", (a, b) -> a + " " + b);
        long zeroFive = merged.split("0\\.5", -1).length - 1;
        long oneFive = merged.split("1\\.5", -1).length - 1;
        // 重叠窗口会重复计入，只断言"没有丢失"（每个数字至少出现 12 次）
        assertThat(zeroFive).isGreaterThanOrEqualTo(12);
        assertThat(oneFive).isGreaterThanOrEqualTo(12);
    }
}
