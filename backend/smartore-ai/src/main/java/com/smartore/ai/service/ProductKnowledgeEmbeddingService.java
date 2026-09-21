package com.smartore.ai.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.smartore.ai.entity.AiModelConfig;
import com.smartore.ai.entity.EmbeddingGenerateProgress;
import com.smartore.ai.entity.EmbeddingSearchRequest;
import com.smartore.ai.entity.ProductKnowledgeChunk;
import com.smartore.ai.entity.ProductKnowledgeEmbedding;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.ai.mapper.ProductKnowledgeChunkMapper;
import com.smartore.ai.mapper.ProductKnowledgeEmbeddingMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 商品知识向量服务。
 *
 * 相对单体版的两处架构升级：
 * 1. 全量生成的进度从「单机 volatile」改为 Redis —— 多实例部署时任何实例都能回答进度查询；
 * 2. 并发闸从「单机 AtomicBoolean」改为 Redis SET NX 分布式锁 —— 全局唯一批量任务。
 * 批量执行本身由 MQ 消息触发（EmbeddingJobConsumer），本服务只提供触发口与执行体。
 */
@Service
public class ProductKnowledgeEmbeddingService {

    @Resource
    private NameFillService nameFillService;

    public static final String PROGRESS_KEY = "smartore:ai:embedding:progress";
    public static final String LOCK_KEY = "smartore:ai:embedding:lock";

    // 未配置向量模型时的本地回退：2-gram 哈希到 64 维，仅用于无 Key 演示 RAG 流程
    private static final String LOCAL_EMBEDDING_MODEL = "local-hash-embedding-v1";
    private static final int LOCAL_VECTOR_DIMENSION = 64;

    @Resource
    private ProductKnowledgeChunkMapper chunkMapper;
    @Resource
    private ProductKnowledgeEmbeddingMapper embeddingMapper;
    @Resource
    private AiChatService aiChatService;
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    @Resource
    private com.smartore.goods.api.GoodsFeignClient goodsClient;
    @Resource
    private HybridRetrievalService hybridRetrieval;

    /** 供 EmbeddingJobConsumer 调用的执行体：跑全量并向 Redis 写进度 */
    public void runGenerateAll() {
        ProductKnowledgeChunk condition = new ProductKnowledgeChunk();
        condition.setChunkStatus("READY");
        List<ProductKnowledgeChunk> chunks = chunkMapper.selectAll(condition);
        EmbeddingGenerateProgress current = new EmbeddingGenerateProgress();
        current.setRunning(true);
        current.setTotal(chunks.size());
        current.setStartTime(cn.hutool.core.date.DateUtil.now());
        saveProgress(current);
        if (chunks.isEmpty()) {
            finishProgress(current, "没有可用切片，请先在【商品知识切片】生成切片");
            return;
        }
        AiModelConfig embConfig = aiChatService.findEnabledConfig("EMBEDDING");
        try {
            for (ProductKnowledgeChunk chunk : chunks) {
                embeddingMapper.deleteByChunkId(chunk.getId());
                try {
                    saveEmbedding(chunk, embConfig);
                    current.setSuccessCount(current.getSuccessCount() + 1);
                } catch (Exception e) {
                    if (aiChatService.isFatalModelError(e)) {
                        current.setFailCount(current.getFailCount() + 1);
                        current.setProcessed(current.getProcessed() + 1);
                        finishProgress(current, "向量生成已中断：" + aiChatService.describeModelError(e)
                                + "（已成功 " + current.getSuccessCount() + " 条）");
                        return;
                    }
                    current.setFailCount(current.getFailCount() + 1);
                }
                current.setProcessed(current.getProcessed() + 1);
                saveProgress(current);
            }
            String message = "已生成 " + current.getSuccessCount() + " 条向量";
            if (current.getFailCount() > 0) {
                message += "，" + current.getFailCount() + " 条失败（可在列表里对失败切片重新生成）";
            }
            finishProgress(current, message);
        } catch (Exception e) {
            finishProgress(current, "生成中断：" + StrUtil.maxLength(e.getMessage(), 200));
        }
    }

    /** 单切片重新生成（同步，量小） */
    public int generateByChunkId(Integer chunkId) {
        if (ObjectUtil.isEmpty(chunkId)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        ProductKnowledgeChunk condition = new ProductKnowledgeChunk();
        condition.setId(chunkId);
        List<ProductKnowledgeChunk> chunks = chunkMapper.selectAll(condition);
        if (chunks.isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        AiModelConfig embConfig = aiChatService.findEnabledConfig("EMBEDDING");
        embeddingMapper.deleteByChunkId(chunkId);
        saveEmbedding(chunks.get(0), embConfig);
        return 1;
    }

    public EmbeddingGenerateProgress getGenerateProgress() {
        String json = redisTemplate.opsForValue().get(PROGRESS_KEY);
        if (StrUtil.isBlank(json)) {
            EmbeddingGenerateProgress empty = new EmbeddingGenerateProgress();
            empty.setRunning(false);
            empty.setTotal(0);
            return empty;
        }
        try {
            return JSONUtil.toBean(json, EmbeddingGenerateProgress.class);
        } catch (Exception e) {
            EmbeddingGenerateProgress empty = new EmbeddingGenerateProgress();
            empty.setRunning(false);
            return empty;
        }
    }

    private void saveProgress(EmbeddingGenerateProgress progress) {
        redisTemplate.opsForValue().set(PROGRESS_KEY, JSONUtil.toJsonStr(progress), 1, TimeUnit.HOURS);
    }

    private void finishProgress(EmbeddingGenerateProgress progress, String message) {
        progress.setMessage(message);
        progress.setEndTime(cn.hutool.core.date.DateUtil.now());
        progress.setRunning(false);
        saveProgress(progress);
    }

    /** 语义检索：查询文本与切片同法向量化后按余弦排序（数据量级内全量内存计算，扩展时换向量索引） */
    public List<ProductKnowledgeEmbedding> search(EmbeddingSearchRequest request) {
        if (ObjectUtil.isEmpty(request.getQueryText())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        int topK = request.getTopK() == null ? 5 : request.getTopK();
        topK = Math.max(1, Math.min(topK, 20));
        String queryText = buildSearchText(request);

        AiModelConfig embConfig = aiChatService.findEnabledConfig("EMBEDDING");
        double[] queryVector = embConfig != null
                ? aiChatService.embedWithConfig(embConfig, queryText)
                : localHashEmbed(queryText);
        ProductKnowledgeEmbedding condition = new ProductKnowledgeEmbedding();
        condition.setEmbeddingStatus("READY");
        condition.setProductId(request.getProductId());
        List<ProductKnowledgeEmbedding> embeddings = embeddingMapper.selectAll(condition);
        for (ProductKnowledgeEmbedding embedding : embeddings) {
            double score = cosine(queryVector, parseVector(embedding.getVectorText()));
            embedding.setSimilarityScore(Math.round(score * 10000.0) / 10000.0);
        }
        // 稠密通道排名（所有候选都已算出余弦分）
        List<ProductKnowledgeEmbedding> denseRanked = embeddings.stream()
                .sorted(Comparator.comparing(ProductKnowledgeEmbedding::getSimilarityScore).reversed())
                .toList();

        boolean hybrid = !"dense".equalsIgnoreCase(request.getMode());
        if (!hybrid) {
            return denseRanked.stream().limit(topK).toList();
        }

        // 关键词通道：BM25 对「编号/型号/数字」类字面查询敏感，补稠密向量的盲区（ADR-007）
        Map<Integer, String> chunkTexts = new java.util.HashMap<>();
        Map<Integer, ProductKnowledgeEmbedding> byChunkId = new java.util.HashMap<>();
        for (ProductKnowledgeEmbedding embedding : embeddings) {
            byChunkId.putIfAbsent(embedding.getChunkId(), embedding);
        }
        if (!byChunkId.isEmpty()) {
            ProductKnowledgeChunk chunkCondition = new ProductKnowledgeChunk();
            chunkCondition.setChunkStatus("READY");
            for (ProductKnowledgeChunk chunk : chunkMapper.selectAll(chunkCondition)) {
                ProductKnowledgeEmbedding owner = byChunkId.get(chunk.getId());
                if (owner != null) {
                    chunkTexts.put(chunk.getId(), chunk.getChunkTitle() + " " + chunk.getChunkContent());
                }
            }
        }
        List<Integer> keywordRanked = hybridRetrieval.bm25Rank(queryText, chunkTexts);
        java.util.Set<Integer> keywordTopChunkIds = new java.util.HashSet<>(keywordRanked.stream().limit(5).toList());

        // RRF 融合两路名次（分数不可比，只融排名）
        Map<Integer, ProductKnowledgeEmbedding> idMap = new java.util.HashMap<>();
        for (ProductKnowledgeEmbedding embedding : embeddings) {
            idMap.put(embedding.getId(), embedding);
        }
        List<ProductKnowledgeEmbedding> fused = hybridRetrieval.rrfFuse(
                denseRanked.stream().map(ProductKnowledgeEmbedding::getId).toList(),
                keywordRanked.stream().map(id -> byChunkId.get(id).getId()).toList()
        ).stream().map(idMap::get).toList();
        for (ProductKnowledgeEmbedding embedding : fused) {
            embedding.setKeywordHit(keywordTopChunkIds.contains(embedding.getChunkId()));
        }
        return fused.stream().limit(topK).toList();
    }

    /** 指定商品时把商品名拼进查询，保证查询与切片在同一语境（切片内容以商品名开头） */
    private String buildSearchText(EmbeddingSearchRequest request) {
        String queryText = request.getQueryText().trim();
        if (ObjectUtil.isEmpty(request.getProductId())) {
            return queryText;
        }
        com.smartore.common.result.Result<com.smartore.goods.api.ProductVO> result =
                goodsClient.getProduct(request.getProductId());
        com.smartore.goods.api.ProductVO product = result == null || !"200".equals(result.getCode())
                ? null : result.getData();
        if (product == null || StrUtil.isEmpty(product.getName()) || queryText.contains(product.getName())) {
            return queryText;
        }
        return product.getName() + " " + queryText;
    }

    public void deleteById(Integer id) {
        embeddingMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        embeddingMapper.deleteBatch(ids);
    }

    public PageInfo<ProductKnowledgeEmbedding> selectPage(ProductKnowledgeEmbedding condition,
                                                          Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ProductKnowledgeEmbedding> rows = embeddingMapper.selectAll(condition);
        nameFillService.fillProducts(rows, ProductKnowledgeEmbedding::getProductId, (row, p) -> {
            row.setProductName(p.getName());
            row.setProductNo(p.getProductNo());
        });
        return PageInfo.of(rows);
    }

    private void saveEmbedding(ProductKnowledgeChunk chunk, AiModelConfig embConfig) {
        double[] vector;
        String modelName;
        if (embConfig != null) {
            vector = aiChatService.embedWithConfig(embConfig, chunk.getChunkContent());
            modelName = embConfig.getModelName();
        } else {
            vector = localHashEmbed(chunk.getChunkContent());
            modelName = LOCAL_EMBEDDING_MODEL;
        }
        String now = cn.hutool.core.date.DateUtil.now();
        ProductKnowledgeEmbedding embedding = new ProductKnowledgeEmbedding();
        embedding.setChunkId(chunk.getId());
        embedding.setKnowledgeId(chunk.getKnowledgeId());
        embedding.setProductId(chunk.getProductId());
        embedding.setEmbeddingModel(modelName);
        embedding.setVectorDimension(vector.length);
        embedding.setVectorText(vectorToText(vector));
        embedding.setEmbeddingStatus("READY");
        embedding.setCreateTime(now);
        embedding.setUpdateTime(now);
        embeddingMapper.insert(embedding);
    }

    private double[] localHashEmbed(String text) {
        double[] vector = new double[LOCAL_VECTOR_DIMENSION];
        String content = text == null ? "" : text.trim().toLowerCase();
        if (content.isEmpty()) {
            return vector;
        }
        for (int i = 0; i < content.length(); i++) {
            String gram = content.substring(i, Math.min(i + 2, content.length()));
            vector[Math.abs(gram.hashCode()) % LOCAL_VECTOR_DIMENSION] += 1.0;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(double[] vector) {
        double sum = 0;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            return;
        }
        double length = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / length;
        }
    }

    private double cosine(double[] left, double[] right) {
        // 维度不一致 = 换过 embedding 模型后新旧向量混存：静默按前 n 维算会产出垃圾相似度，
        // 必须显式判 0（检索层表现为"检索不到"，比错配召回可解释）
        if (left.length != right.length) {
            return 0;
        }
        int n = left.length;
        double dot = 0;
        for (int i = 0; i < n; i++) {
            dot += left[i] * right[i];
        }
        double leftNorm = 0;
        for (double value : left) {
            leftNorm += value * value;
        }
        double rightNorm = 0;
        for (double value : right) {
            rightNorm += value * value;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String vectorToText(double[] vector) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(String.format(Locale.US, "%.6f", vector[i]));
        }
        return builder.toString();
    }

    private double[] parseVector(String vectorText) {
        if (ObjectUtil.isEmpty(vectorText)) {
            return new double[0];
        }
        try {
            String[] values = vectorText.split(",");
            double[] vector = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                vector[i] = Double.parseDouble(values[i]);
            }
            return vector;
        } catch (NumberFormatException e) {
            // 脏数据按"无向量"处理（检索层降级），不让单行坏数据 500 整个检索
            return new double[0];
        }
    }
}
