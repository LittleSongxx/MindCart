package com.mindcart.voice.service;

import com.mindcart.voice.dto.Intent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 高频确认词短路 + 下单确认的肯定/否定词表。
 * 关键回归：肯定词表含单字"好/对"，因此"不好/不对"会同时命中肯定词——
 * 这两个谓词不能独立判真假，调用方（handleOrderConfirm）必须先判否定再判肯定。
 */
class ConfirmWordsTest {

    @Test
    // 高频确认词命中短路
    void case79() {
        assertTrue(CommonConfirmer.isCommonConfirmer("嗯"));
        assertTrue(CommonConfirmer.isCommonConfirmer("好的"));
        assertTrue(CommonConfirmer.isCommonConfirmer("嗯。"));      // 标点被剥掉
        assertTrue(CommonConfirmer.isCommonConfirmer(" 好，！？ "));
        assertTrue(CommonConfirmer.isCommonConfirmer("嗯好的"));     // 连用：切分命中（多轮 M09 曾漏）
        assertTrue(CommonConfirmer.isCommonConfirmer("好的好的"));
        assertTrue(CommonConfirmer.isCommonConfirmer("对对对"));
    }

    @Test
    // 上下文语义词不再短路（回归："换一个"曾短路成 ORDER_CONFIRM，
    // 下单分支无指代可解析形成死路——换款/继续类交给意图 LLM 结合历史判别）
    void case79b() {
        assertFalse(CommonConfirmer.isCommonConfirmer("换一个"));
        assertFalse(CommonConfirmer.isCommonConfirmer("下一个"));
        assertFalse(CommonConfirmer.isCommonConfirmer("再来"));
        assertFalse(CommonConfirmer.isCommonConfirmer("继续"));
    }

    @Test
    // 非确认词不短路
    void case80() {
        assertFalse(CommonConfirmer.isCommonConfirmer("不好"));       // 否定词，绝不能当确认
        assertFalse(CommonConfirmer.isCommonConfirmer("帮我推荐双跑鞋")); // 超长
        assertFalse(CommonConfirmer.isCommonConfirmer(null));
        assertFalse(CommonConfirmer.isCommonConfirmer(""));
    }

    @Test
    // 短路意图固定为订单确认
    void case81() {
        assertEquals(Intent.ORDER_CONFIRM, CommonConfirmer.CONFIRMER_INTENT.intent());
    }

    @Test
    // 肯定词表
    void case82() {
        assertTrue(OrchestratorService.containsYes("确认下单"));
        assertTrue(OrchestratorService.containsYes("可以"));
        assertTrue(OrchestratorService.containsYes("就这款"));
        assertTrue(OrchestratorService.containsYes("嗯嗯"));
        assertFalse(OrchestratorService.containsYes("再看看"));
        assertFalse(OrchestratorService.containsYes(null));
    }

    @Test
    // 否定词表
    void case83() {
        assertTrue(OrchestratorService.containsNo("不要了"));
        assertTrue(OrchestratorService.containsNo("算了"));
        assertTrue(OrchestratorService.containsNo("取消"));
        assertTrue(OrchestratorService.containsNo("再想想"));
        assertFalse(OrchestratorService.containsNo("就买它"));
    }

    @Test
    // 真实拒绝表达覆盖（回归：曾漏判"先不买了"导致反问用户）
    void case85() {
        assertTrue(OrchestratorService.containsNo("还行，先不买了"));
        assertTrue(OrchestratorService.containsNo("这款不好"));
        assertTrue(OrchestratorService.containsNo("不合适"));
        assertTrue(OrchestratorService.containsNo("不用了谢谢"));
        assertTrue(OrchestratorService.containsNo("不对，我说的是另一款"));
    }

    @Test
    // 子串冲突文档化_存在双命中话术（此前缺 @Test，这条回归约束从未被执行）
    void case84() {
        // "不好"含"好"：只命中肯定表 → 若先判肯定会误下单
        assertTrue(OrchestratorService.containsYes("不好"));
        // "不要了可以吗"同时命中两表（"不要"+"可以"）→ 调用方必须先判否定再判肯定，
        // 该话术最终语义是否定。此测试把双命中场景固化为回归约束。
        assertTrue(OrchestratorService.containsNo("不要了可以吗"));
        assertTrue(OrchestratorService.containsYes("不要了可以吗"));
    }
}
