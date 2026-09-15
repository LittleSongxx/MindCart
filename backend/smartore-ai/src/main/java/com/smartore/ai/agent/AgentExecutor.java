package com.smartore.ai.agent;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.smartore.ai.agent.tools.SubmitRecommendationsTool;
import com.smartore.ai.entity.AgentRun;
import com.smartore.ai.entity.AgentStep;
import com.smartore.ai.entity.ProductToolRequest;
import com.smartore.ai.entity.ProductToolResult;
import com.smartore.ai.entity.ShoppingGuideTask;
import com.smartore.ai.entity.ShoppingRecommendation;
import com.smartore.ai.service.AgentRunService;
import com.smartore.ai.service.AgentStepService;
import com.smartore.ai.service.AiChatService;
import com.smartore.ai.service.ProductToolService;
import com.smartore.ai.service.ShoppingRecommendationService;
import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 导购 Agent 执行器（真 Function Calling 循环）。
 *
 * 单体版的 switch 分发在这里改为「工具注册表」：Spring 注入全部 AgentTool 策略 Bean，
 * 按 name 建索引；循环逻辑只认识注册表，不认识任何具体工具（OCP：新工具=新类+注解）。
 * 每一轮真实工具调用落一条 AgentStep，模型提交的推荐仍要用实时商品数据复核后落地（防幻觉）。
 */
@Component
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /** 单次运行最多对话轮数，防止模型陷入无限工具调用 */
    public static final int MAX_ITERATIONS = 10;

    private final Map<String, AgentTool> toolRegistry;

    @Resource
    private AiChatService aiChatService;
    @Resource
    private AgentRunService agentRunService;
    @Resource
    private AgentStepService agentStepService;
    @Resource
    private ShoppingRecommendationService shoppingRecommendationService;
    @Resource
    private GoodsFeignClient goodsClient;
    @Resource
    private ProductToolService productToolService;
    @Resource
    private com.smartore.ai.mapper.ShoppingGuideTaskMapper taskMapper;

    public AgentExecutor(List<AgentTool> tools) {
        this.toolRegistry = tools.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));
        log.info("Agent 工具注册表加载完成：{}", toolRegistry.keySet());
    }

    /** 执行一次导购任务（同步方法，由 MQ 消费者调用；执行轨迹与结果全部落库） */
    public void execute(ShoppingGuideTask task) {
        AgentRun run = agentRunService.startGuideRun(task);
        try {
            runLoop(task, run);
            agentRunService.finishGuideRun(run, task);
        } catch (RuntimeException e) {
            try {
                agentRunService.failGuideRun(run, buildErrorMessage(e));
            } catch (Exception recordError) {
                log.error("记录导购任务失败原因时出错，任务ID：{}", task.getId(), recordError);
            }
            log.error("导购任务执行失败，任务ID：{}", task.getId(), e);
            throw e;
        }
    }

    private void runLoop(ShoppingGuideTask task, AgentRun run) {
        AgentContext context = new AgentContext(task.getUserId());
        JSONArray messages = new JSONArray();
        messages.add(buildSystemMessage());
        messages.add(buildUserMessage(task));

        List<Submission> submissions = new ArrayList<>();
        boolean submitted = false;
        int stepOrder = 1;

        for (int round = 0; round < MAX_ITERATIONS && !submitted; round++) {
            JSONObject assistant = aiChatService.chatCompletion(messages, buildToolsJson());
            if (assistant.get("content") == null) {
                assistant.set("content", "");
            }
            messages.add(assistant);

            JSONArray toolCalls = assistant.getJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                break; // 模型不再调用工具，视为给出最终判断
            }
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject toolCall = toolCalls.getJSONObject(i);
                String callId = toolCall.getStr("id");
                JSONObject function = toolCall.getJSONObject("function");
                String name = function.getStr("name");
                String argumentsRaw = function.getStr("arguments");
                JSONObject args = parseArguments(argumentsRaw);

                AgentTool tool = toolRegistry.get(name);
                AgentStep step = agentStepService.startStep(run, stepOrder++, "TOOL_CALL",
                        tool == null ? name : tool.label(), name, argumentsRaw);

                String toolResult;
                if (tool == null) {
                    toolResult = "未知工具：" + name;
                } else if (tool.isTerminal()) {
                    submissions.addAll(parseSubmissions(args));
                    submitted = true;
                    toolResult = "已收到 " + submissions.size() + " 条推荐提交";
                } else {
                    toolResult = tool.execute(args, context);
                }
                agentStepService.finishStep(step, toolResult);

                JSONObject toolMessage = new JSONObject();
                toolMessage.set("role", "tool");
                toolMessage.set("tool_call_id", callId);
                toolMessage.set("name", name);
                toolMessage.set("content", toolResult);
                messages.add(toolMessage);
            }
        }
        materialize(task, run, submissions, stepOrder);
    }

    /** 模型提交的商品用实时商品数据复核价格库存后落地；不可购买的直接剔除 */
    private void materialize(ShoppingGuideTask task, AgentRun run, List<Submission> submissions, int stepOrder) {
        List<String> recommendBlocks = new ArrayList<>();
        List<Integer> matchedIds = new ArrayList<>();
        List<ShoppingRecommendation> recommendations = new ArrayList<>();

        List<Integer> candidateIds = submissions.stream().map(s -> s.productId).toList();
        Map<Integer, ProductVO> productMap = candidateIds.isEmpty() ? Map.of()
                : unwrap(goodsClient.getProducts(candidateIds)).stream()
                        .collect(Collectors.toMap(ProductVO::getId, Function.identity()));

        for (Submission submission : submissions) {
            if (recommendations.size() >= SubmitRecommendationsTool.MAX_RECOMMENDATIONS) {
                break;
            }
            ProductVO product = productMap.get(submission.productId);
            if (product == null || !"ON_SALE".equals(product.getStatus())) {
                continue;
            }
            int stock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
            if (stock <= 0) {
                continue; // 无货商品不进推荐
            }
            ProductToolRequest toolRequest = new ProductToolRequest();
            toolRequest.setProductId(product.getId());
            toolRequest.setQuantity(1);
            ProductToolResult priceResult = productToolService.queryProductPrice(toolRequest);
            ProductToolResult stockResult = productToolService.queryProductStock(toolRequest);
            ProductToolResult promotionResult = productToolService.queryProductPromotion(toolRequest);
            String reason = ObjectUtil.isEmpty(submission.reason)
                    ? buildEvidenceSummary(product, priceResult, stockResult, promotionResult)
                    : submission.reason;

            matchedIds.add(product.getId());
            recommendBlocks.add(buildRecommendBlock(product, priceResult, stockResult, promotionResult, reason));
            recommendations.add(buildRecommendation(task, run, product, priceResult, stockResult,
                    promotionResult, reason, recommendations.size() + 1));
        }

        AgentStep finalStep = agentStepService.startStep(run, stepOrder, "RECOMMENDATION_GENERATE",
                "生成导购推荐结果", "",
                "模型提交商品ID：" + candidateIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        if (recommendBlocks.isEmpty()) {
            task.setStatus("FAILED");
            task.setMatchedProductIds("");
            task.setRecommendationResult("");
            task.setExecuteMessage("AI 未产出可购买的推荐商品（可能是候选不足或库存不足）");
            shoppingRecommendationService.saveTaskRecommendations(task.getId(), new ArrayList<>());
            agentStepService.failStep(finalStep, task.getExecuteMessage());
        } else {
            task.setStatus("DONE");
            task.setMatchedProductIds(matchedIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
            task.setRecommendationResult(String.join("\n\n", recommendBlocks));
            task.setExecuteMessage("已完成导购任务，AI 生成 " + recommendBlocks.size() + " 条推荐");
            shoppingRecommendationService.saveTaskRecommendations(task.getId(), recommendations);
            agentStepService.finishStep(finalStep, task.getRecommendationResult());
        }
        task.setExecuteTime(cn.hutool.core.date.DateUtil.now());
        task.setUpdateTime(cn.hutool.core.date.DateUtil.now());
        // 终态落库（DONE/FAILED 都在这里持久化，消费者据此收敛）
        taskMapper.updateById(task);
    }

    // ---- 消息与工具定义 ----

    private JSONObject buildSystemMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是电商平台的智能导购 Agent。你的目标是根据用户购物需求，为用户挑选最合适的商品。\n");
        sb.append("你可以调用以下工具获取真实数据：\n");
        for (AgentTool tool : toolRegistry.values()) {
            sb.append("- ").append(tool.name()).append("：").append(tool.description()).append("；\n");
        }
        sb.append("工作要求：先检索候选商品，再对候选逐个查询价格、库存、优惠，只推荐在售且有库存、价格不超预算的商品；");
        sb.append("推荐理由必须基于工具查到的真实数据，不要编造参数或价格。信息足够后调用 submit_recommendations 结束。");
        JSONObject message = new JSONObject();
        message.set("role", "system");
        message.set("content", sb.toString());
        return message;
    }

    private JSONObject buildUserMessage(ShoppingGuideTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("购物需求：").append(task.getDemandText()).append("\n");
        sb.append("预算金额：").append(task.getBudgetAmount() == null ? "未指定" : task.getBudgetAmount()).append("\n");
        sb.append("商品关键词：").append(ObjectUtil.isEmpty(task.getProductName()) ? "无" : task.getProductName()).append("\n");
        sb.append("基准商品ID：").append(task.getProductId() == null ? "无" : task.getProductId()).append("\n");
        sb.append("当前用户ID：").append(task.getUserId() == null ? "无（可跳过用户画像）" : task.getUserId());
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", sb.toString());
        return message;
    }

    private JSONArray buildToolsJson() {
        JSONArray tools = new JSONArray();
        for (AgentTool tool : toolRegistry.values()) {
            JSONObject function = new JSONObject();
            function.set("name", tool.name());
            function.set("description", tool.description());
            function.set("parameters", tool.parametersSchema());
            JSONObject item = new JSONObject();
            item.set("type", "function");
            item.set("function", function);
            tools.add(item);
        }
        return tools;
    }

    // ---- 私有辅助 ----

    private JSONObject parseArguments(String argumentsRaw) {
        if (StrUtil.isBlank(argumentsRaw)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(argumentsRaw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private List<Submission> parseSubmissions(JSONObject args) {
        List<Submission> list = new ArrayList<>();
        JSONArray items = args == null ? null : args.getJSONArray("items");
        if (items == null) {
            return list;
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            Integer productId = item.getInt("productId");
            if (productId != null) {
                list.add(new Submission(productId, item.getStr("reason")));
            }
        }
        return list;
    }

    private String buildRecommendBlock(ProductVO product,
                                       ProductToolResult priceResult,
                                       ProductToolResult stockResult,
                                       ProductToolResult promotionResult,
                                       String recommendReason) {
        StringBuilder builder = new StringBuilder();
        builder.append("推荐商品：").append(product.getName()).append("\n");
        builder.append("商品编号：").append(product.getProductNo()).append("\n");
        builder.append("当前价格：").append(priceResult.getPrice()).append("，原价：").append(priceResult.getOriginalPrice()).append("\n");
        builder.append("库存数量：").append(stockResult.getStockQuantity()).append("\n");
        builder.append("优惠信息：优惠 ").append(promotionResult.getDiscountAmount())
                .append("，折扣率 ").append(promotionResult.getDiscountRate()).append("\n");
        builder.append("推荐理由：").append(recommendReason);
        return builder.toString();
    }

    private ShoppingRecommendation buildRecommendation(ShoppingGuideTask task, AgentRun run, ProductVO product,
                                                       ProductToolResult priceResult, ProductToolResult stockResult,
                                                       ProductToolResult promotionResult, String reason, Integer rank) {
        ShoppingRecommendation recommendation = new ShoppingRecommendation();
        recommendation.setTaskId(task.getId());
        recommendation.setRunId(run.getId());
        recommendation.setUserId(task.getUserId());
        recommendation.setProductId(product.getId());
        recommendation.setProductNo(product.getProductNo());
        recommendation.setProductName(product.getName());
        recommendation.setProductImage(product.getCoverImage());
        recommendation.setPriceSnapshot(priceResult.getPrice());
        recommendation.setOriginalPriceSnapshot(priceResult.getOriginalPrice());
        recommendation.setDiscountAmount(promotionResult.getDiscountAmount());
        recommendation.setDiscountRate(promotionResult.getDiscountRate());
        recommendation.setAvailableQuantity(stockResult.getStockQuantity());
        recommendation.setRecommendRank(rank);
        recommendation.setRecommendScore(calculateScore(product, stockResult, promotionResult, task));
        recommendation.setRecommendReason(reason);
        recommendation.setEvidenceSummary(buildEvidenceSummary(product, priceResult, stockResult, promotionResult));
        return recommendation;
    }

    private Integer calculateScore(ProductVO product, ProductToolResult stockResult,
                                   ProductToolResult promotionResult, ShoppingGuideTask task) {
        int score = 0;
        if (product.getIsRecommend() != null && product.getIsRecommend() == 1) {
            score += 20;
        }
        if (product.getSort() != null) {
            score += Math.max(0, 20 - product.getSort());
        }
        if (product.getOriginalPrice() != null && product.getPrice() != null
                && product.getOriginalPrice().compareTo(product.getPrice()) > 0) {
            score += 10;
        }
        if (stockResult.getStockQuantity() != null && stockResult.getStockQuantity() > 0) {
            score += 30;
        }
        if (promotionResult.getDiscountAmount() != null
                && promotionResult.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            score += 15;
        }
        if (task.getBudgetAmount() != null && product.getPrice() != null
                && product.getPrice().compareTo(task.getBudgetAmount()) <= 0) {
            score += 20;
        }
        return score;
    }

    private String buildEvidenceSummary(ProductVO product, ProductToolResult priceResult,
                                        ProductToolResult stockResult, ProductToolResult promotionResult) {
        StringBuilder builder = new StringBuilder();
        builder.append("商品状态：").append(product.getStatus()).append("\n");
        builder.append("当前价格：").append(priceResult.getPrice()).append("\n");
        builder.append("库存数量：").append(stockResult.getStockQuantity()).append("\n");
        builder.append("优惠金额：").append(promotionResult.getDiscountAmount()).append("\n");
        builder.append("商品标签：").append(product.getTags());
        return builder.toString();
    }

    private String buildErrorMessage(RuntimeException e) {
        if (e instanceof com.smartore.common.exception.CustomException custom) {
            return custom.getMsg();
        }
        return StrUtil.isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
    }

    private <T> T unwrap(Result<T> result) {
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode())) {
            throw new com.smartore.common.exception.CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    result == null ? "商品服务不可用" : result.getMsg());
        }
        return result.getData();
    }

    /** 模型提交的一条推荐 */
    public record Submission(Integer productId, String reason) {
    }
}
