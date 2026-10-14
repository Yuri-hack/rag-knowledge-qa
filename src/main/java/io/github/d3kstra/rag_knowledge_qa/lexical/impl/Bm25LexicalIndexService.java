package io.github.d3kstra.rag_knowledge_qa.lexical.impl;

import io.github.d3kstra.rag_knowledge_qa.entity.DocumentChunk;
import io.github.d3kstra.rag_knowledge_qa.lexical.LexicalIndexService;
import io.github.d3kstra.rag_knowledge_qa.repository.DocumentChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程内 BM25 词法索引（对齐 vector/、rerank/ 的特性包 + impl 约定）。
 *
 * 分词策略（针对中文 + 业务编号混合文本）：
 *  1. 字母数字串整体成词，内部允许 - _ / ："YC-HY-2026-0003"、"MRX33CH/A" 各为一个 token；
 *     同时把其分段（yc / hy / 2026 / 0003）也入索引，支持用户只记得片段时仍能命中；
 *  2. 中文按**字符二元组**入索引（"整机保修期" → 整机/机保/保修/修期），单字串退化为单字。
 *
 * 索引规模：当前语料约千级 chunk，全量常驻内存；语料再大一个量级也仍可承受
 * （倒排表只存 postings，不存正文副本）。TTL 到期或显式 rebuild 时整体重建。
 */
@Slf4j
@Service
public class Bm25LexicalIndexService implements LexicalIndexService {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** 稀有 token 判定：长度阈值与 df 上限（资产编号/单号属此类） */
    private static final int RARE_TOKEN_MIN_LENGTH = 6;
    private static final int RARE_TOKEN_MAX_DF = 3;

    /** 字母数字串（内部允许分隔符） */
    private static final Pattern ALNUM = Pattern.compile("[a-z0-9]+(?:[-_/][a-z0-9]+)*");
    private static final Pattern SEPARATOR = Pattern.compile("[-_/]");

    private final DocumentChunkRepository chunkRepository;

    @Value("${rag.search.hybrid.index-ttl-seconds:300}")
    private long indexTtlSeconds;

    private final Object buildLock = new Object();
    private volatile Snapshot snapshot;

    public Bm25LexicalIndexService(DocumentChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    /** 索引快照：不可变，构建后整体替换 */
    private record Snapshot(Map<Long, Doc> docs,
                            Map<String, List<Posting>> postings,
                            double avgDocLength,
                            long builtAtMillis) {
    }

    private record Doc(Long chunkId, String documentId, String fileName,
                       Integer chunkIndex, String content, int length) {
    }

    private record Posting(Long chunkId, int tf) {
    }

    @Override
    public List<LexicalHit> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        Snapshot snap = ensureSnapshot();
        if (snap.docs().isEmpty()) {
            return List.of();
        }
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        int totalDocs = snap.docs().size();

        // 稀有 token（如资产编号）：命中它的 chunk 视为"精确匹配"，用于压过语义 rerank 的排序
        java.util.Set<Long> exactMatchChunks = new java.util.HashSet<>();
        for (String token : new HashSet<>(tokens)) {
            if (token.length() < RARE_TOKEN_MIN_LENGTH || !token.matches("[a-z0-9][a-z0-9\\-_/]*")) {
                continue;
            }
            List<Posting> postings = snap.postings().get(token);
            if (postings != null && !postings.isEmpty() && postings.size() <= RARE_TOKEN_MAX_DF) {
                postings.forEach(posting -> exactMatchChunks.add(posting.chunkId()));
            }
        }

        Map<Long, Double> scores = new HashMap<>();
        for (String token : new HashSet<>(tokens)) {          // 同一 token 在查询里只计一次
            List<Posting> postings = snap.postings().get(token);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            double idf = Math.log(1.0 + (totalDocs - postings.size() + 0.5) / (postings.size() + 0.5));
            for (Posting p : postings) {
                Doc doc = snap.docs().get(p.chunkId());
                if (doc == null) {
                    continue;
                }
                double tfNorm = (p.tf() * (K1 + 1.0))
                        / (p.tf() + K1 * (1.0 - B + B * doc.length() / snap.avgDocLength()));
                scores.merge(p.chunkId(), idf * tfNorm, Double::sum);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topK)
                .map(e -> {
                    Doc d = snap.docs().get(e.getKey());
                    return new LexicalHit(d.chunkId(), d.documentId(), d.fileName(),
                            d.chunkIndex(), d.content(), e.getValue(),
                            exactMatchChunks.contains(d.chunkId()));
                })
                .toList();
    }

    @Override
    public void rebuild() {
        synchronized (buildLock) {
            long start = System.currentTimeMillis();
            List<DocumentChunk> chunks = chunkRepository.findAll();
            Map<Long, Doc> docs = new HashMap<>();
            Map<String, List<Posting>> postings = new HashMap<>();
            long totalLength = 0;

            for (DocumentChunk chunk : chunks) {
                List<String> tokens = tokenize(chunk.getContent());
                if (tokens.isEmpty()) {
                    continue;
                }
                docs.put(chunk.getId(), new Doc(chunk.getId(), chunk.getDocumentId(),
                        chunk.getFileName(), chunk.getChunkIndex(), chunk.getContent(), tokens.size()));
                totalLength += tokens.size();
                Map<String, Integer> tf = new HashMap<>();
                for (String token : tokens) {
                    tf.merge(token, 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : tf.entrySet()) {
                    postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                            .add(new Posting(chunk.getId(), e.getValue()));
                }
            }
            double avg = docs.isEmpty() ? 1.0 : (double) totalLength / docs.size();
            snapshot = new Snapshot(docs, postings, avg, System.currentTimeMillis());
            log.info("词法索引重建完成: {} 个 chunk, {} 个 term, 耗时 {}ms",
                    docs.size(), postings.size(), System.currentTimeMillis() - start);
        }
    }

    @Override
    public int indexedChunkCount() {
        Snapshot snap = snapshot;
        return snap == null ? 0 : snap.docs().size();
    }

    /** 索引为空或超过 TTL 时重建（与上传进程解耦，靠 TTL 感知新增语料） */
    private Snapshot ensureSnapshot() {
        Snapshot snap = snapshot;
        boolean expired = snap == null
                || System.currentTimeMillis() - snap.builtAtMillis() > indexTtlSeconds * 1000L;
        if (expired) {
            rebuild();
            snap = snapshot;
        }
        return snap;
    }

    /**
     * 分词：字母数字串（含分段）+ 中文二元组。
     * 可见性为包级，便于单测直接验证分词结果。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lower = text.toLowerCase();

        Matcher matcher = ALNUM.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group();
            tokens.add(token);
            if (SEPARATOR.matcher(token).find()) {          // 片段也入索引：yc-hy-2026-0003 → yc/hy/2026/0003
                for (String part : SEPARATOR.split(token)) {
                    if (part.length() > 1) {
                        tokens.add(part);
                    }
                }
            }
        }

        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                cjkRun.append(c);
            } else {
                appendCjkTokens(cjkRun, tokens);
                cjkRun.setLength(0);
            }
        }
        appendCjkTokens(cjkRun, tokens);
        return tokens;
    }

    /** 中文串 → 二元组；单字串退化为单字 */
    private static void appendCjkTokens(StringBuilder run, List<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        if (run.length() == 1) {
            tokens.add(run.toString());
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            tokens.add(run.substring(i, i + 2));
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
