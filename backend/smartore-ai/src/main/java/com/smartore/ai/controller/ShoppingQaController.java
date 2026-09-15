package com.smartore.ai.controller;

import com.smartore.common.result.Result;
import com.smartore.ai.entity.ShoppingQa;
import com.smartore.ai.entity.ShoppingQaRequest;
import com.smartore.ai.service.ShoppingQaService;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/shoppingQa")
public class ShoppingQaController {

    @Resource
    private ShoppingQaService shoppingQaService;

    @PostMapping("/ask")
    public Result ask(@RequestBody ShoppingQaRequest request) {
        ShoppingQa shoppingQa = shoppingQaService.ask(request);
        return Result.success(shoppingQa);
    }

    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Integer id) {
        shoppingQaService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping("/delete/batch")
    public Result delete(@RequestBody List<Integer> ids) {
        shoppingQaService.deleteBatch(ids);
        return Result.success();
    }

    @GetMapping("/selectPage")
    public Result selectPage(ShoppingQa shoppingQa,
                             @RequestParam(defaultValue = "1") Integer pageNum,
                             @RequestParam(defaultValue = "10") Integer pageSize) {
        PageInfo<ShoppingQa> pageInfo = shoppingQaService.selectPage(shoppingQa, pageNum, pageSize);
        return Result.success(pageInfo);
    }

    /**
     * 流式问答（SSE）：增量文本以 delta 事件推送，complete 事件携带完整 QA 实体（含 conversationId）。
     * 事件类型约定：delta=文本增量 / complete=最终结果 / error=失败原因。
     */
    @GetMapping(value = "/askStream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam Integer userId,
                                @RequestParam String questionType,
                                @RequestParam String questionText,
                                @RequestParam(required = false) Integer productId,
                                @RequestParam(required = false) String productName,
                                @RequestParam(required = false) Integer conversationId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        ShoppingQaRequest request = new ShoppingQaRequest();
        request.setUserId(userId);
        request.setQuestionType(questionType);
        request.setQuestionText(questionText);
        request.setProductId(productId);
        request.setProductName(productName);
        request.setConversationId(conversationId);
        // SSE 工作线程独立于容器线程：LLM 生成可达数十秒
        java.util.concurrent.ExecutorService streamExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        streamExecutor.submit(() -> {
            try {
                ShoppingQa qa = shoppingQaService.ask(request, chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(chunk));
                    } catch (Exception ignored) {
                        // 客户端断开：停止转发增量，后台继续完成并落库（连接不是任务状态）
                    }
                });
                emitter.send(SseEmitter.event().name("complete").data(qa));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            cn.hutool.core.util.StrUtil.maxLength(String.valueOf(e.getMessage()), 300)));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            } finally {
                streamExecutor.shutdown();
            }
        });
        return emitter;
    }

    /** 我的会话列表（多轮上下文入口） */
    @GetMapping("/conversations")
    public com.smartore.common.result.Result<java.util.List<com.smartore.ai.entity.QaConversation>> conversations() {
        return com.smartore.common.result.Result.success(
                shoppingQaService.myConversations(com.smartore.common.context.UserContext.requireUserId()));
    }
}
