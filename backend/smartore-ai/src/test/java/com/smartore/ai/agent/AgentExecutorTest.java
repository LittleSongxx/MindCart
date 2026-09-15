package com.smartore.ai.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.smartore.ai.entity.AgentRun;
import com.smartore.ai.entity.AgentStep;
import com.smartore.ai.entity.ProductToolRequest;
import com.smartore.ai.entity.ProductToolResult;
import com.smartore.ai.entity.ShoppingGuideTask;
import com.smartore.ai.entity.ShoppingRecommendation;
import com.smartore.ai.mapper.ShoppingGuideTaskMapper;
import com.smartore.ai.service.AgentRunService;
import com.smartore.ai.service.AgentStepService;
import com.smartore.ai.service.AiChatService;
import com.smartore.ai.service.ProductToolService;
import com.smartore.ai.service.ShoppingRecommendationService;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 循环的行为契约：
 * 1. 模型经检索→提交两轮工具调用后，任务收敛 DONE 且落库；
 * 2. 推荐必须用真实商品数据复核（下架/无货商品被剔除）；
 * 3. 模型不调工具直接收口时不产生推荐（FAILED 路径落库）。
 */
@ExtendWith(MockitoExtension.class)
class AgentExecutorTest {

    @Mock AiChatService aiChatService;
    @Mock AgentRunService agentRunService;
    @Mock AgentStepService agentStepService;
    @Mock ShoppingRecommendationService recommendationService;
    @Mock GoodsFeignClient goodsClient;
    @Mock ProductToolService productToolService;
    @Mock ShoppingGuideTaskMapper taskMapper;

    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        // 只装纯逻辑的提交工具（终结工具）；Feign 型工具由 mock 直接喂给被测逻辑
        executor = new AgentExecutor(List.of(new com.smartore.ai.agent.tools.SubmitRecommendationsTool()));
        // 反射注入 mock（生产由 Spring 注入）
        inject("aiChatService", aiChatService);
        inject("agentRunService", agentRunService);
        inject("agentStepService", agentStepService);
        inject("shoppingRecommendationService", recommendationService);
        inject("goodsClient", goodsClient);
        inject("productToolService", productToolService);
        inject("taskMapper", taskMapper);
    }

    private void inject(String field, Object value) {
        try {
            java.lang.reflect.Field f = AgentExecutor.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(executor, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId(1);
        return run;
    }

    private JSONObject toolCall(String name, String args) {
        JSONObject call = new JSONObject();
        call.set("id", "call-" + name);
        JSONObject function = new JSONObject();
        function.set("name", name);
        function.set("arguments", args);
        call.set("function", function);
        return call;
    }

    private JSONObject assistantWithCalls(JSONObject... calls) {
        JSONObject message = new JSONObject();
        message.set("content", "");
        JSONArray array = new JSONArray();
        for (JSONObject call : calls) {
            array.add(call);
        }
        message.set("tool_calls", array);
        return message;
    }

    private ProductVO product(int id, String name, int stock, String status) {
        ProductVO vo = new ProductVO();
        vo.setId(id);
        vo.setProductNo("SP" + id);
        vo.setName(name);
        vo.setPrice(new BigDecimal("69.00"));
        vo.setOriginalPrice(new BigDecimal("99.00"));
        vo.setStatus(status);
        vo.setStockQuantity(stock);
        vo.setCategoryId(1);
        vo.setBrandId(1);
        return vo;
    }

    @Test
    void loopConvergesAndPersistsDone() {
        ShoppingGuideTask task = new ShoppingGuideTask();
        task.setId(9);
        task.setUserId(2);
        task.setDemandText("考研词汇书");
        task.setBudgetAmount(new BigDecimal("100"));

        when(agentRunService.startGuideRun(task)).thenReturn(run());
        when(agentStepService.startStep(any(), anyInt(), any(), any(), any(), any())).thenReturn(new AgentStep());
        AtomicInteger round = new AtomicInteger();
        when(aiChatService.chatCompletionWithUsage(any(), any())).thenAnswer(inv -> {
            if (round.getAndIncrement() == 0) {
                // 第一轮：检索候选（注册表为空 → 返回未知工具，验证分发兜底）
                return new AiChatService.ChatCompletionResult(assistantWithCalls(toolCall("unknown_tool", "{}")), 100, 20);
            }
            // 第二轮：提交推荐（在售有货 + 无货商品各一个，无货的必须被剔除）
            return new AiChatService.ChatCompletionResult(assistantWithCalls(toolCall("submit_recommendations",
                    "{\"items\":[{\"productId\":1,\"reason\":\"便宜\"},{\"productId\":2,\"reason\":\"没货\"}]}")), 100, 20);
        });
        when(goodsClient.getProducts(any())).thenReturn(Result.success(List.of(
                product(1, "考研词汇", 5, "ON_SALE"),
                product(2, "无货书", 0, "ON_SALE"))));
        when(productToolService.queryProductPrice(any())).thenReturn(priceResult());
        when(productToolService.queryProductStock(any())).thenReturn(stockResult(5));
        when(productToolService.queryProductPromotion(any())).thenReturn(promoResult());

        executor.execute(task);

        // 任务收敛 DONE 且真实落库
        assertEquals("DONE", task.getStatus());
        verify(taskMapper).updateById(task);
        // 只保留了有货商品
        verify(recommendationService).saveTaskRecommendations(eq(9), argThat(list -> list.size() == 1
                && ((ShoppingRecommendation) list.get(0)).getProductId() == 1));
        verify(agentRunService).finishGuideRun(any(), eq(task));
    }

    @Test
    void noToolCallMarksFailed() {
        ShoppingGuideTask task = new ShoppingGuideTask();
        task.setId(10);
        task.setDemandText("随便看看");
        when(agentRunService.startGuideRun(task)).thenReturn(run());
        JSONObject plain = new JSONObject();
        plain.set("content", "我直接回答");
        when(aiChatService.chatCompletionWithUsage(any(), any()))
                .thenReturn(new AiChatService.ChatCompletionResult(plain, 50, 10));

        executor.execute(task);

        assertEquals("FAILED", task.getStatus());
        verify(taskMapper).updateById(task);
        verify(recommendationService).saveTaskRecommendations(eq(10), anyList());
    }

    private ProductToolResult priceResult() {
        ProductToolResult result = new ProductToolResult();
        result.setProductId(1);
        result.setProductName("考研词汇");
        result.setPrice(new BigDecimal("69.00"));
        result.setOriginalPrice(new BigDecimal("99.00"));
        return result;
    }

    private ProductToolResult stockResult(int stock) {
        ProductToolResult result = new ProductToolResult();
        result.setStockQuantity(stock);
        result.setCanBuy(stock > 0 ? 1 : 0);
        return result;
    }

    private ProductToolResult promoResult() {
        ProductToolResult result = new ProductToolResult();
        result.setDiscountAmount(new BigDecimal("30.00"));
        result.setDiscountRate(new BigDecimal("0.70"));
        return result;
    }
}
