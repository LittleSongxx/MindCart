package com.mindcart.voice.service;

import com.mindcart.voice.entity.ProductEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductVectorService {

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;
    private final ProductTextBuilder textBuilder;

    /**
     * 单条商品写入向量。embedding 列为空或过期都靠这个方法刷新。
     */
    public void upsertEmbedding(ProductEntity p) {
        float[] vec = embeddingService.embed(textBuilder.build(p));
        jdbc.update(
                "UPDATE voice_product SET embedding = ?::vector WHERE id = ?",
                VectorLiteral.of(vec), p.getId());
    }

    /**
     * 向量 + 标量混合检索。
     *
     * @param query       用户 query（会被向量化）
     * @param extraFilter 额外 WHERE 条件片段（不含 WHERE 关键字），可为空
     * @param extraParams extraFilter 里的 ? 对应的参数
     * @param topK        返回前 K 个
     * @return (id, 真实余弦相似度) 列表，按相似度降序——真实相似度供重排层做量纲正确的加权
     */
    public record Hit(long id, double similarity) {}

    public List<Hit> search(String query, String extraFilter, List<Object> extraParams, int topK) {
        float[] qvec = embeddingService.embed(query);

        StringBuilder sql = new StringBuilder(
                "SELECT id, 1 - (embedding <=> ?::vector) AS sim FROM voice_product " +
                        "WHERE status = 'ON_SALE' AND embedding IS NOT NULL");
        if (extraFilter != null && !extraFilter.isBlank()) {
            sql.append(" AND ").append(extraFilter);
        }
        // <=> 是 pgvector 的 cosine distance 运算符，越小越相似。
        // 标量条件先过滤、向量距离后排序合在一条 SQL 里；带过滤的 HNSW 查询
        // 依赖连接级 hnsw.iterative_scan（见 application.yml 的 hikari init-sql）
        // 保证过滤掉大半候选时仍能扫够 LIMIT 条。
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");

        // 注意占位符顺序：SELECT 列里的向量在前，其次 WHERE 的 extras、ORDER BY 的向量、LIMIT。
        // JDBC 按出现顺序绑定，参数数组必须与之一致（曾因顺序错位报
        // "character varying = double precision"——价格绑到了品类占位符上）
        Object[] params = new Object[extraParams.size() + 3];
        params[0] = VectorLiteral.of(qvec);
        for (int i = 0; i < extraParams.size(); i++) params[i + 1] = extraParams.get(i);
        params[extraParams.size() + 1] = VectorLiteral.of(qvec);
        params[extraParams.size() + 2] = topK;

        return jdbc.query(sql.toString(), params,
                (rs, i) -> new Hit(rs.getLong("id"), rs.getDouble("sim")));
    }
}