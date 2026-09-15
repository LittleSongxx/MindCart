# 验证证据记录（本地 mall 栈，2026-09-15）

> 全部命令可复跑；数字为实测输出摘录。

## 1. 冒烟（dev.sh check）

```
[dev] 登录 OK（token 136 字符）
[dev] 商品列表 OK
[dev] RBAC OK（USER 访问管理接口被 403）
[dev] 冒烟全部通过 ✅
```

## 2. 交易链路（trade_e2e.py）

```
PASS 登录 aaa
PASS 加购
PASS 下单 Saga 收敛为 PAID          ← 订单 OD...，金额与商品现价一致
PASS 幂等重放返回原订单              ← 同 requestId 二次提交返回原单
PASS 库存原子扣减
PASS 取消订单 / 退款金额=订单金额    ← 余额差精确等于 totalAmount
PASS 重复取消幂等 / 重复取消不产生二次退款
PASS 取消后库存回补
```

## 3. 并发防超卖（库存 3，两用户并发各买 2）

```
PASS admin 重置库存为 3
PASS 并发 2 单只成功 1 单（库存3/需求4）
PASS 库存精确（3-2=1，无超卖无丢失）
```

固化单测 `StockSagaConcurrencyTest`（Testcontainers 真实 MySQL）：
20 线程抢库存 10 → `success=10, available=0`；同单重放扣/补各只生效一次。

## 4. 崩溃自愈（恢复任务）

开发过程中真实发生（非人为构造）：早期版本在下单收口处存在 SQL 绑定缺陷，
进程在"已扣款"后崩溃 → 订单停 PAYING → 60s 内恢复任务按钱包凭据自动补完
income/markPaid/事件账本 → 订单收敛 PAID。日志（logs/trade.log 2026-09-15 21:14）：

```
WARN 买家已扣款但后续步骤失败，交恢复任务续走成功路径（orderNo=OD20260915211415119399）
（恢复任务）处理 PAYING 订单 → platformIncome(幂等) → finishPay → PAID
```

## 5. 事件链路与对账

```
ledger_events=6 mirror_events=6        ← Outbox→MQ→AI 镜像逐事件一致（早期验证）
--- 逐单六不变量（当前基线含种子+交易） ---
paid_without_wallet_pay=0  cancelled_without_refund=0
paid_without_ledger_event=0  cancelled_without_cancel_event=0
ledger_orphan_events=0  ledger_mirror_diff=0
verdict=OK
```

### 注错演练

```
注入伪造孤儿事件 → ledger_orphan_events=1, verdict=MISMATCH (violations=2)
回退删除         → verdict=OK
```

## 6. 导购 Agent（真实 LLM：qwen3.7-plus via 阿里 MaaS）

```
poll: WAITING → RUNNING → DONE（约 20s）
状态: DONE | 消息: 已完成导购任务，AI 生成 1 条推荐
推荐商品：考研英语高分词汇与真题解析
当前价格：69.00，原价：99.00 / 库存数量：1 / 优惠：30.00，折扣率 0.70   ← 全部来自 goods 实时查询
推荐理由：…现价69元（原价99元，享7折优惠，节省30元），在100元预算以内…
```

## 7. 监控

```
Prometheus targets: smartore-ai/user/gateway/goods/trade 全 up
LLM 指标: smartore_llm_call_seconds_count{kind="agent",model="qwen3.7-plus",outcome="success"} = 14
Grafana: http://localhost:3999（总览 + LLM 画像两块看板自动装配）
```

## 8. 测试

```
mvn test → 11/11 通过：
CryptoUtilsTest(4) OrderStatusTest(3) StockSagaConcurrencyTest(2, Testcontainers) AgentExecutorTest(2, mock LLM)
```
