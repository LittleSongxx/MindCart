package com.mindcart.voice.service;

import com.mindcart.voice.compliance.ComplianceChecker;
import com.mindcart.voice.dto.*;
import com.mindcart.voice.entity.ProductEntity;
import com.mindcart.voice.entity.SessionStateEntity;
import com.mindcart.voice.entity.StreamChunk;
import com.mindcart.voice.event.VoiceEventPublisher;
import com.mindcart.voice.memory.ShortTermMemory;
import com.mindcart.voice.memory.TurnSummarizer;
import com.mindcart.voice.repository.ProductRepository;
import com.mindcart.voice.voice.SentenceAggregator;
import com.mindcart.voice.voice.TtsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.reactivex.Flowable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private final IntentService intentService;
    private final ClarifyService clarifyService;
    private final ParallelRecommendService recommendService;
    private final EmotionService emotionService;
    private final PerspectiveHubService perspectiveHub;
    private final SessionStateService stateService;
    private final SessionService sessionService;
    private final ShortTermMemory memory;
    private final VoiceEventPublisher eventPublisher;
    private final ComplianceChecker compliance;
    private final MeterRegistry metrics;
    private final ProductRepository productRepo;
    private final TurnSummarizer turnSummarizer;
    private final PendingOrderStore pendingStore;
    private final OrderReferenceResolver referenceResolver;
    private final VoiceOrderService voiceOrderService;
    private final ConversationJournal journal;
    private final com.mindcart.voice.integration.KnowledgeAnswerService knowledgeAnswerService;
    private final com.mindcart.voice.integration.OrderQueryService orderQueryService;
    private final EmotionStreamingService emotionStreamingService;
    private final TtsService tts;
    private final CachedTtsPhrases ttsCache;
    private final LlmGuard llmGuard;

    @Value("${voice-shopping.perspective.enabled:false}")
    private boolean perspectiveEnabled;

    /**
     * 处理一次用户输入的主入口（带流量准入：并发/QPS 超限时快速失败给降级文案，
     * 不让突发流量把 LLM 连接打满后集体阻塞）。
     *
     * @return 封装好的结果（speechText + displayBlocks），交给 TTS 下发
     */
    public EmotionResult handle(String sessionId, Long userId, String utterance) {
        EmotionResult result = llmGuard.guardEntry(() -> doHandle(sessionId, userId, utterance),
                () -> new EmotionResult("这会儿咨询的朋友有点多，稍等几秒钟，再跟我说一次哈。", List.of()));
        // 同步路径的助手回复入会话归档（流式路径在 streamHandle 里按累积文本记录，避免双写）
        journal.assistantSaid(sessionId, result.speechText(), "emotion_agent", lastIntent(sessionId), null);
        return result;
    }

    private String lastIntent(String sessionId) {
        try {
            return stateService.load(sessionId).getCurrentIntent();
        } catch (Exception e) {
            return null;
        }
    }

    private EmotionResult doHandle(String sessionId, Long userId, String utterance) {

        Timer.Sample sample = Timer.start(metrics);
        try {
            // 幂等开启 session 主表（session_state 外键依赖，首轮必须先创建）
            sessionService.openIfAbsent(sessionId, userId, "HOME_ENTRY");
            sessionService.touch(sessionId);
            // 用户话术入会话归档（两条路径的唯一入口；Redis 冷启动时从这里回灌上下文）
            journal.userSaid(sessionId, utterance);
            journal.hydrateIfCold(sessionId);

            eventPublisher.publishUserSpoken(sessionId, userId, utterance);

            SessionStateEntity state = stateService.load(sessionId);

            // 规则前置：查我的订单（确定性需求，不进意图分类的 LLM 往返；词表刻意收窄）
            if (com.mindcart.voice.integration.OrderQueryService.looksLikeOrderQuery(utterance)) {
                String orderSpeech = orderQueryService.answer(userId);
                if (orderSpeech != null) {
                    return new EmotionResult(orderSpeech, List.of());
                }
            }

            // 短路：ORDER_CONFIRM 状态直接走订单分支，不跑 IntentAgent
            if ("ORDER_CONFIRM".equals(state.getPhase())) {
                return handleOrderConfirm(sessionId, userId, state, utterance);
            }

            IntentResult intent = intentService.classify(sessionId, utterance);
            log.info("[Orc] sessionId={} intent={} slots={}", sessionId, intent.intent(), intent.slots());

            // 意图兜底矫正：LLM 对"便宜点/换一款"这类价格方向表达经常判成 CLARIFY_NEEDED，
            // 但只要上一轮已经推了商品 + 本轮抽到了 priceDirection，就强制按 PRODUCT_COMPARE 处理
            intent = reviseIntentByContext(state, intent);

            // 生效意图落状态：会话状态记录**矫正后**实际路由的意图（ops 排查 / 系统级评测口径），
            // 与 /api/v1/agent/intent 的纯分类器口径区分开
            state.setCurrentIntent(intent.intent().name());

            EmotionResult result = switch (intent.intent()) {
                case PRODUCT_RECOMMENDATION -> handleRecommendation(sessionId, userId, state, utterance, intent);
                case CLARIFY_NEEDED -> handleClarify(sessionId, state, utterance, intent);
                case PRODUCT_COMPARE -> handleCompare(sessionId, userId, state, utterance, intent);
                case ORDER_CONFIRM -> handleOrderConfirm(sessionId, userId, state, utterance);
                case ORDER_CANCEL -> handleOrderCancel(sessionId, state);
                case CHITCHAT -> handleChitchat(sessionId, userId, utterance);
                case OUT_OF_SCOPE -> handleOutOfScope(sessionId, utterance);
            };

            // 合规兜底
            result = compliance.ensureCompliant(sessionId, userId, result);

            String summary = turnSummarizer.summarize(utterance, intent.intent(), result.speechText());
            memory.append(sessionId, new ShortTermMemory.Turn(
                    "TURN", intent.intent().name(), summary, System.currentTimeMillis()));
            stateService.save(state);
            return result;

        } finally {
            sample.stop(metrics.timer("voice_shopping.orchestrator.latency"));
        }
    }

    /* ========== 四条分支的具体实现 ========== */

    private EmotionResult handleRecommendation(String sessionId, Long userId,
                                               SessionStateEntity state,
                                               String utterance, IntentResult intent) {
        // 合并历史槽位 + 本轮新槽位
        Map<String, Object> slots = new HashMap<>();
        if (state.getSlots() != null) slots.putAll(state.getSlots());
        intent.slots().forEach((k, v) -> {
            if (v != null) slots.put(k, v);
        });

        state.setSlots(slots);
        state.setCurrentIntent("PRODUCT_RECOMMENDATION");

        // 澄清判断
        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        if (clarify.action() == ClarifyResult.Action.ASK) {
            state.setPhase("CLARIFY");
            state.setPendingAsk(clarify.missingSlots().get(0));
            return new EmotionResult(clarify.questionToAsk(), List.of());
        }

        // 推荐
        state.setPhase("RECOMMEND");
        RecommendResult rec = recommendService.recommend(sessionId, userId, utterance, slots);
        state.setLastRecommendations(rec.items().stream().map(RecommendedItem::productId).toList());

        // 旁路：多视角点评团（15 节 PerspectiveHubService）。失败/关闭时降级为原始 utterance
        String contextForEmotion = utterance;
        if (perspectiveEnabled && !rec.items().isEmpty()) {
            String digest = perspectiveHub.discuss(sessionId, utterance, rec.items());
            if (digest != null && !digest.isBlank()) {
                contextForEmotion = utterance + "\n\n【点评团意见】\n" + digest;
            }
        }

        String userNeeds = formatUserNeeds(slots);
        return emotionService.wrap(sessionId, contextForEmotion, userNeeds, rec);
    }

    private EmotionResult handleClarify(String sessionId, SessionStateEntity state,
                                        String utterance, IntentResult intent) {
        // 用户第一次就说得很模糊（"最近想买点东西"），用意图里抽到的 slots 开始
        Map<String, Object> slots = new HashMap<>(intent.slots());
        state.setSlots(slots);
        state.setPhase("CLARIFY");

        ClarifyResult clarify = clarifyService.decide(sessionId, utterance, slots);
        return new EmotionResult(
                clarify.action() == ClarifyResult.Action.ASK
                        ? clarify.questionToAsk()
                        : "好，我这就帮你挑。",
                List.of());
    }

    private EmotionResult handleCompare(String sessionId, Long userId,
                                        SessionStateEntity state,
                                        String utterance, IntentResult intent) {
        // 对比类 = 在上一次推荐结果基础上，根据当前诉求重新排序/过滤
        Map<String, Object> slots = new HashMap<>();
        if (state.getSlots() != null) slots.putAll(state.getSlots());
        intent.slots().forEach((k, v) -> {
            if (v != null) slots.put(k, v);
        });

        // "便宜点/贵点"要以**上一轮推荐的实际价格**为锚，不能直接用 budget——
        // budget 只是用户给的上限，上一轮推出来的商品可能远低于 budget，
        // 若直接按 budget * 0.8 下调，新上限可能还比上一轮最高价高，起不到"便宜"的效果。
        String pd = (String) slots.get("priceDirection");
        List<Long> lastIds = state.getLastRecommendations();
        if (pd != null && lastIds != null && !lastIds.isEmpty()) {
            List<BigDecimal> lastPrices = productRepo.findByIdIn(lastIds).stream()
                    .map(ProductEntity::getPrice).toList();
            if (!lastPrices.isEmpty()) {
                BigDecimal maxP = lastPrices.stream().max(BigDecimal::compareTo).get();
                BigDecimal minP = lastPrices.stream().min(BigDecimal::compareTo).get();
                // cheaper：新上限 = 上轮最高价 * 0.8，保证比上一轮任何一款都便宜
                // expensive：新下限 = 上轮最低价 * 1.2（这里先放 budget 里，推荐层读 priceMin 字段）
                if ("cheaper".equals(pd)) {
                    slots.put("budget", maxP.multiply(BigDecimal.valueOf(0.8)).intValue());
                } else if ("expensive".equals(pd)) {
                    slots.put("priceMin", minP.multiply(BigDecimal.valueOf(1.2)).intValue());
                }
                // 排除上轮已推过的，避免重复推同一款
                slots.put("excludeProductIds", lastIds);
            }
        }

        RecommendResult rec = recommendService.recommend(sessionId, userId, utterance, slots);
        String userNeeds = formatUserNeeds(slots);
        return emotionService.wrap(sessionId, utterance, userNeeds, rec);
    }

    private EmotionResult handleOrderConfirm(String sessionId, Long userId,
                                             SessionStateEntity state, String utterance) {
        // 已经有 pending 单了，判断是 YES 还是 NO。
        // 必须先判否定再判肯定："不好"含"好"、"不对"含"对"，子串包含关系决定了顺序不能反
        PendingOrderStore.PendingOrder pending = pendingStore.get(sessionId);
        if (pending != null) {
            if (containsNo(utterance)) {
                voiceOrderService.cancel(sessionId);
                state.setPhase("RECOMMEND");
                return new EmotionResult("好的，没给你下。想再聊点别的还是换款看看？", List.of());
            }
            if (containsYes(utterance)) {
                // 交易事实只在 MindCart 业务服务产生：发动作帧给前端代执行，
                // 结果经 WS 控制帧 order_result 回传后由 handleOrderResult 口播收口
                Map<String, Object> action = voiceOrderService.confirm(sessionId);
                if (action == null) {
                    return new EmotionResult("这张单我正在处理中，稍等一下。", List.of());
                }
                return new EmotionResult("好，正在为你下单，请稍等。", List.of(), action);
            }
            return new EmotionResult("那你是确认要这款还是不要？", List.of());
        }

        // 没有 pending，新起一个
        Optional<Long> pidOpt = referenceResolver.resolve(state, utterance);
        if (pidOpt.isEmpty()) {
            return new EmotionResult(
                    "你想要的是刚才推荐的哪一款？可以说第一款、第二款或者商品名。",
                    List.of());
        }
        PendingOrderStore.PendingOrder po = voiceOrderService.preview(sessionId, userId, pidOpt.get(), 1);
        state.setPhase("ORDER_CONFIRM");
        return new EmotionResult(
                String.format("好，帮你准备下单：%s，¥%s，一共 %s 元。确认下单吗？",
                        po.productName(), po.unitPrice(), po.totalAmount()),
                List.of());
    }

    /**
     * 暂缓/放弃购买（浏览中的"先不买了/算了再想想"，非订单操作）：
     * 有 pending 单就一并取消，然后回到推荐态让会话可继续。
     */
    private EmotionResult handleOrderCancel(String sessionId, SessionStateEntity state) {
        if (pendingStore.get(sessionId) != null) {
            voiceOrderService.cancel(sessionId);
            state.setPhase("RECOMMEND");
            return new EmotionResult("好的，先不下单，想看别的随时跟我说。", List.of());
        }
        // 无 pending：尝试取消"刚才成交的那一单"（前端代执行 /shopOrder/cancel）
        Map<String, Object> cancelFrame = voiceOrderService.cancelLastOrder(sessionId);
        if (cancelFrame != null) {
            return new EmotionResult("好，正在为你取消刚才的订单，请稍等。", List.of(), cancelFrame);
        }
        state.setPhase("RECOMMEND");
        return new EmotionResult("好的，先不下单，想看别的随时跟我说。", List.of());
    }

    /**
     * 前端代执行的下单结果回传（WS 控制帧 order_result 的入口）。
     * 口播文案由 VoiceOrderService 收口给出；成交后按下单成功处理会话阶段。
     */
    public EmotionResult handleOrderResult(String sessionId, String requestId,
                                           boolean success, String orderNo, String error) {
        String speech = voiceOrderService.completeOnResult(sessionId, requestId, success, orderNo, error);
        if (speech == null) return null;   // 过期/重复回传，忽略
        SessionStateEntity state = stateService.load(sessionId);
        state.setPhase(success ? "ENDED" : "ORDER_CONFIRM");
        stateService.save(state);
        return new EmotionResult(speech, List.of());
    }

    /** order_result 的流式出口：与 doStreamHandle 的非推荐路径同构（文本 + TTS 音频） */
    public Flux<StreamChunk> streamOrderResult(String sessionId, String requestId,
                                               boolean success, String orderNo, String error) {
        EmotionResult r = handleOrderResult(sessionId, requestId, success, orderNo, error);
        if (r == null || r.speechText() == null) return Flux.empty();
        journal.systemEvent(sessionId, String.format("订单回执：%s（requestId=%s, orderNo=%s%s）",
                success ? "成交" : "失败", requestId, orderNo,
                error == null ? "" : ", " + error));
        journal.assistantSaid(sessionId, r.speechText(), "order_receipt", "ORDER_CONFIRM", null);
        return Flux.concat(
                Flux.just(StreamChunk.text(r.speechText())),
                ttsAudio(sessionId, r.speechText()).map(StreamChunk::audio)
        );
    }

    // 包私有静态：纯规则逻辑不依赖实例状态，开放给单测做表驱动覆盖
    static boolean containsYes(String s) {
        return s != null && (s.contains("确认") || s.contains("可以") || s.contains("就这")
                || s.contains("对") || s.contains("好") || s.contains("嗯"));
    }

    /**
     * 下单确认阶段的否定/取消判别。词表覆盖真实拒绝表达空间：
     * 明确拒绝（不要/不用/不买/算了/取消）+ 犹豫（再想想/等下/先不/不着急）
     * + 对商品的负评（不好/不行/不合适/不喜欢/不对——注意"不好/不对"同时含
     * 肯定词表的"好/对"，故调用方必须先判否定再判肯定）。
     */
    static boolean containsNo(String s) {
        return s != null && (s.contains("不要") || s.contains("不用") || s.contains("不买")
                || s.contains("算了") || s.contains("取消") || s.contains("再想想")
                || s.contains("等下") || s.contains("先不") || s.contains("不着急")
                || s.contains("不好") || s.contains("不行") || s.contains("不合适")
                || s.contains("不喜欢") || s.contains("不对"));
    }

    private EmotionResult handleChitchat(String sessionId, Long userId, String utterance) {
        // 闲聊走 EmotionService 的闲聊模式（EmotionAgent 内部可判断 products 为空）
        return emotionService.wrap(sessionId, utterance, "",
                new RecommendResult(List.of(), "chitchat"));
    }

    private EmotionResult handleOutOfScope(String sessionId, String utterance) {
        // 目录外问题先试知识库（店铺政策/售后/物流/发票）：命中且有资料依据才作答，
        // 否则维持原兜底话术——语音此前对这类问题一律兜底，是能力缺口
        var answer = knowledgeAnswerService.tryAnswer(sessionId, utterance);
        if (answer != null && answer.text() != null && !answer.text().isBlank()) {
            lastKnowledgeEvidence = answer.evidence();
            return new EmotionResult(answer.text(), List.of());
        }
        return new EmotionResult(
                "这个我暂时答不上来，可以找客服帮你处理哈。我们继续聊想买什么？",
                List.of());
    }

    /** 最近一次知识回答引用的资料（调试端点可读，用于核对是否真的有据可依） */
    private volatile java.util.List<com.mindcart.voice.integration.KnowledgeClient.KnowledgeHitVO>
            lastKnowledgeEvidence = java.util.List.of();

    public java.util.List<com.mindcart.voice.integration.KnowledgeClient.KnowledgeHitVO> lastKnowledgeEvidence() {
        return lastKnowledgeEvidence;
    }


    /**
     * 意图后处理：LLM 在两类场景下容易判错，这里做兜底矫正。
     * <p>
     * 1) 上下文比较：上一轮已有推荐 + 本轮抽到 priceDirection →
     * CLARIFY_NEEDED / PRODUCT_RECOMMENDATION 统一改写成 PRODUCT_COMPARE
     * <p>
     * 2) 信息已足：state.slots + 本轮 slots 合并后，category + (budget|scenario|brand)
     * 任一组合齐全，就不该再 CLARIFY_NEEDED，强制改写为 PRODUCT_RECOMMENDATION
     */
    // 包私有静态：纯规则逻辑不依赖实例状态，开放给单测做表驱动覆盖
    static IntentResult reviseIntentByContext(SessionStateEntity state, IntentResult intent) {
        Map<String, Object> curSlots = intent.slots() == null ? Map.of() : intent.slots();

        // ① 上下文价格对比
        boolean hasLastRecommend = "RECOMMEND".equals(state.getPhase())
                && state.getLastRecommendations() != null
                && !state.getLastRecommendations().isEmpty();
        boolean hasPriceDirection = curSlots.get("priceDirection") != null;
        if (hasLastRecommend && hasPriceDirection
                && (intent.intent() == Intent.CLARIFY_NEEDED
                || intent.intent() == Intent.PRODUCT_RECOMMENDATION)) {
            log.info("[Orc] 意图矫正 {} -> PRODUCT_COMPARE, priceDirection={}",
                    intent.intent(), curSlots.get("priceDirection"));
            return new IntentResult(Intent.PRODUCT_COMPARE, curSlots, intent.confidence());
        }

        // ② 信息已足阈值：合并历史 slots + 本轮 slots
        if (intent.intent() == Intent.CLARIFY_NEEDED) {
            Map<String, Object> merged = new HashMap<>();
            if (state.getSlots() != null) merged.putAll(state.getSlots());
            curSlots.forEach((k, v) -> {
                if (v != null) merged.put(k, v);
            });

            boolean hasCategory = merged.get("category") != null;
            boolean hasAnyAnchor = merged.get("budget") != null
                    || merged.get("scenario") != null
                    || merged.get("brand") != null;
            if (hasCategory && hasAnyAnchor) {
                log.info("[Orc] 意图矫正 CLARIFY_NEEDED -> PRODUCT_RECOMMENDATION, mergedSlots={}", merged);
                return new IntentResult(Intent.PRODUCT_RECOMMENDATION, merged, intent.confidence());
            }
        }

        return intent;
    }

    private static String formatUserNeeds(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) return "";
        return slots.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("，"));
    }

    public Flux<StreamChunk> streamHandle(String sessionId, Long userId, String utterance) {
        Flux<StreamChunk> flux = llmGuard.guardEntry(() -> doStreamHandle(sessionId, userId, utterance),
                () -> Flux.just(StreamChunk.text("这会儿咨询的朋友有点多，请稍后再试。")));
        return journalAssistantOnComplete(sessionId, flux);
    }

    /**
     * 流式路径的会话归档：累积下发的文本帧，流结束时落一行助手回复。
     * 订阅方（WS handler）不需要做任何额外工作，归档对链路透明。
     */
    private Flux<StreamChunk> journalAssistantOnComplete(String sessionId, Flux<StreamChunk> flux) {
        StringBuilder spoken = new StringBuilder();
        return flux
                .doOnNext(c -> {
                    if (c.type() == StreamChunk.Type.TEXT && c.text() != null) {
                        spoken.append(c.text());
                    }
                })
                .doOnComplete(() -> journal.assistantSaid(sessionId, spoken.toString(),
                        "emotion_stream", lastIntent(sessionId), null));
    }

    private Flux<StreamChunk> doStreamHandle(String sessionId, Long userId, String utterance) {
        // TTS 会话预热：与意图/检索/话术并行建连，把 ~0.9s 的 omni 握手移出首句出声的关键路径
        tts.prewarm(sessionId);

        IntentResult intent = intentService.classify(sessionId, utterance);
        log.info("[Stream] sessionId={} intent={} slots={}",
                sessionId, intent.intent(), intent.slots());

        if (intent.intent() != Intent.PRODUCT_RECOMMENDATION) {
            // 直接走 doHandle：入口准入已在 streamHandle 持有，不重复占用并发额度
            EmotionResult r = doHandle(sessionId, userId, utterance);
            Flux<StreamChunk> head = Flux.just(StreamChunk.text(r.speechText()));
            if (r.actionFrame() != null) {
                // 动作帧紧随口播文本下发，前端先听到"正在下单"再执行交易
                head = Flux.concat(head, Flux.just(StreamChunk.action(r.actionFrame())));
            }
            return Flux.concat(head,
                    ttsAudio(sessionId, r.speechText()).map(StreamChunk::audio)
            );
        }

        Map<String, Object> slots = intent.slots() == null ? Map.of() : intent.slots();
        // 流式链路只花这一次 LLM：卡片不带 LLM 理由，理由由情感 Agent 的 token 流口播，
        // 省掉一次同步的理由生成往返（它直接垫在首句出声的前面）
        RecommendResult rec = recommendService.recommend(sessionId, userId, utterance, slots, false);
        log.info("[Stream-Rec] sessionId={} slotsForRecommend={} recCount={}",
                sessionId, slots, rec.items().size());

        // 先把商品卡片发下去（用户立刻看到 UI）
        Flux<StreamChunk> productsFlow = Flux.just(StreamChunk.products(rec.items()));

        // EmotionAgent 流式文本 → 句子聚合 → TTS 流式合成
        Flux<String> rawTokens = emotionStreamingService.streamWrap(sessionId, utterance, rec);
        Flux<String> sentences = SentenceAggregator.aggregate(rawTokens);

        Flux<StreamChunk> textFlow = sentences.concatMap(sentence -> Flux.merge(
                Flux.just(StreamChunk.text(sentence)),
                ttsAudio(sessionId, sentence).map(StreamChunk::audio)
        ));

        return Flux.concat(productsFlow, textFlow);
    }

    private Flux<ByteBuffer> ttsAudio(String sessionId, String text) {
        byte[] cached = ttsCache.get(text);
        if (cached != null) {
            log.debug("[Cost] TTS 命中缓存 text={} bytes={}", text, cached.length);
            return Flux.just(java.nio.ByteBuffer.wrap(cached));
        }
        Flowable<ByteBuffer> flow = tts.synthesize(sessionId, Flowable.just(text));
        return Flux.from(flow);
    }

}