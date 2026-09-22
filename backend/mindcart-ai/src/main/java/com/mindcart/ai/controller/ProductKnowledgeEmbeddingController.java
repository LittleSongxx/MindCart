package com.mindcart.ai.controller;

import com.github.pagehelper.PageInfo;
import com.mindcart.ai.config.AiRabbitTopology;
import com.mindcart.ai.entity.EmbeddingGenerateProgress;
import com.mindcart.ai.entity.EmbeddingSearchRequest;
import com.mindcart.ai.entity.ProductKnowledgeEmbedding;
import com.mindcart.ai.service.ProductKnowledgeEmbeddingService;
import com.mindcart.common.audit.OperationLog;
import com.mindcart.common.result.Result;
import jakarta.annotation.Resource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 知识向量接口。generateAll 从"HTTP 线程同步跑完"改为发 MQ 消息异步执行
 * （消费者持 Redis 分布式锁），前端轮询 generateProgress（进度在 Redis，多实例一致）。
 */
@RestController
@RequestMapping("/productKnowledgeEmbedding")
public class ProductKnowledgeEmbeddingController {

    @Resource
    private ProductKnowledgeEmbeddingService embeddingService;
    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostMapping("/generate/{chunkId}")
    public Result<Integer> generateByChunkId(@PathVariable Integer chunkId) {
        return Result.success(embeddingService.generateByChunkId(chunkId));
    }

    /** 触发全量生成（异步）：入队即返回，进度经 /generateProgress 轮询 */
    @OperationLog(module = "知识库", action = "全量重建向量索引")
    @PostMapping("/generateAll")
    public Result<Void> generateAll() {
        rabbitTemplate.convertAndSend(AiRabbitTopology.EXCHANGE, AiRabbitTopology.ROUTING_EMBEDDING_JOB, "all");
        return Result.success();
    }

    @GetMapping("/generateProgress")
    public Result<EmbeddingGenerateProgress> generateProgress() {
        return Result.success(embeddingService.getGenerateProgress());
    }

    @PostMapping("/search")
    public Result<List<ProductKnowledgeEmbedding>> search(@RequestBody EmbeddingSearchRequest request) {
        return Result.success(embeddingService.search(request));
    }

    @DeleteMapping("/delete/{id}")
    public Result<Void> delete(@PathVariable Integer id) {
        embeddingService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result<Void> deleteBatch(@RequestBody List<Integer> ids) {
        embeddingService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result<PageInfo<ProductKnowledgeEmbedding>> selectPage(ProductKnowledgeEmbedding condition,
                                                                  @RequestParam(defaultValue = "1") Integer pageNum,
                                                                  @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(embeddingService.selectPage(condition, pageNum, pageSize));
    }
}
