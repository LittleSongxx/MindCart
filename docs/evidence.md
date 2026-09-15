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

---

# 第二轮：AI Agent 工程化补强（2026-09-16，ADR-006~010）

## SSE 流式实测

```
event:delta  data:这款商品的保修        ← 增量分片逐段到达
event:delta  data:政策是按品牌官方
...
event:complete data:{"qaNo":"QA...","conversationId":1,"roundNo":1,...}  ← 完整落库含会话
```

## 评测 v1 实跑（ops/verify/eval_ai.py，确定性断言，无 judge）

### 评测器自身被校准了两次（这是评测工程的一部分，不是瑕疵）

1. 拒答标记词表首轮 2 例**假阴性**（回答用"没有覆盖到"拒答，词表没收录）→ 扩表；
2. 检索谓词 all-of 过严（语料写「质保」不是「保修」）→ any-of；编号不在切片语料 → 改型号字串并记语料待办。

### 结果（每指标印分母）

| 指标 | 结果 |
|---|---|
| QA 拒答正确率 | 5/5 = 1.00（知识未覆盖必拒答，不拒=幻觉） |
| 检索 Recall@3 [hybrid] | 5/5（校准口径，小语料饱和） |
| 检索 Recall@3 [dense] | 5/5（同上） |
| 检索 A/B（严格谓词口径） | hybrid 3/5 vs dense 2/5 ← 混合检索增益的可观测点 |
| 导购 Pass@1 | **15/15 = 1.00**（预算6/金标4/空集2/注入3，见下方演进） |
| 导购 Precision@k | 7/7 = 1.00（有金标题分母 7） |

### 导购评测的完整演进线（评测抓 Bug → 修复 → 复测，这就是回归门禁的价值）

| 轮次 | Pass@1 | 抓到什么 |
|---|---|---|
| 首跑 | 4/15 | ① 无关键词检索空转（模型猜词 LIKE 全空，反复检索 8 轮耗尽）→ 加召回放宽；② materialize 缺预算硬校验（预算1000推1499）→ 确定性防线补上 |
| 二跑 | 8/15 | ③ 价格过滤发生在**分页后**（sort 首页全贵价，页内过滤清空；放宽路径同病）→ 价格条件下沉 SQL；期间观察到 LLM 方差（预算100 一轮过一轮挂）——trials 的必要性实证 |
| 终跑 | **15/15** | 注入对抗 3 题全过：注入压力下推荐全部在预算内（299/128/89/69），禁推 MacBook 被提示词边界+预算硬校验双层挡住 |

### 诚实声明

样本 <50 不报置信区间；分数用于**回归对比**（改提示词/换模型前后），不用于宣称绝对水平。

## 容错三件套实测依据

- token 采集：agent_run.prompt_tokens/completion_tokens（Flyway V3）+ smartore.llm.tokens 指标
- 循环指纹：同工具同参数第 3 次出现即终止（agent_step 留痕）
- 解析失败：PARSE_FAILED 状态落轨迹并终止任务（不再带空参数执行全量检索）


---

# 第三轮：浏览器级端到端验证（2026-09-16）

用无头 Chromium 走真实用户路径（登录→浏览→详情→AI 问答→购物车），**6/6 通过**，
并抓出两个仅在浏览器层暴露的问题（curl 层测不出来）：

1. **评价接口 500**：ProductReviewMapper.selectPage 残留跨库 JOIN（`user`/`shop_order`）——
   之前清理 ai 服务的 8 处时漏了 goods 这处。修复：JOIN 拆除 + userName 服务层批量回填。
2. **EventSource 无法携带自定义头**：SSE 端点走 `token` 请求头认证，浏览器原生 EventSource
   发不出自定义头 → 网关 401（curl 测试因能带头而未暴露——**每层都过不等于全链路过**）。
   修复：token 走 query 参数（网关本就设计了 header→query 兜底）。SSE 认证是「AI 全栈」
   面经的 H3 题族，这次是真踩到了。

流式渐进渲染的实测采样（回答区文本长度逐秒）：`[9,9,9,9,9,49,...]` —— delta 增量确实在到达。
