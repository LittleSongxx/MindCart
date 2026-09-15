# Smartore 架构说明

> 决策依据见 `docs/adr/0001-0005`；本文讲「现在长什么样、请求怎么走」。

## 分层与模块

```
backend/
├── smartore-common          统一返回/错误码/全局异常/用户上下文/Feign透传/AES/单号
├── smartore-gateway         WebFlux 网关：AuthGlobalFilter（认证+RBAC+信任头+traceId）+ Redis 限流
├── smartore-user/           api: UserFeignClient/UserVO/WalletOpRequest/WalletStatusVO
│                            app: 账号(BCrypt)/地址/钱包(充值)/WalletSagaService(四步幂等)/文件
├── smartore-goods/          api: GoodsFeignClient/ProductVO/StockOpRequest/AfterSaleRuleVO/...
│                            app: 商品CRUD/库存权威表+StockSagaService/评价/售后规则 + internal
├── smartore-trade/          api: OrderFeignClient/OrderStatus(状态机)/TradeEventPayload/...
│                            app: 购物车/OrderSagaService+OrderTxService/幂等表/Outbox/恢复任务 + internal
└── smartore-ai/             模型配置(加密)/提示词/SpringAiModelFactory/RAG/AgentExecutor+工具注册表/
                             问答/分析报告/MQ消费者(导购/向量/事件镜像)/NameFillService
```

## 一次下单的完整路径

```
POST /shopOrder/create (token)
 └─ 网关: JWT验签 → RBAC(需登录) → 注入 X-User-Id/Role/Internal-Token/Trace-Id → 限流
 └─ trade.OrderSagaService.create
     ① 幂等闸门: request_id 唯一 + requestHash（重放→返回原单）
     ② 购物车选中项 → goods.getProducts 现价重定价 + 库存校验
     ③ 本地事务①(订单PAYING+明细+清车+幂等占位)
     ④ goods.deductForOrder    条件UPDATE + insertIgnore流水（不足→整批回滚）
     ⑤ user.payForOrder        条件UPDATE + 流水（不足→BALANCE_NOT_ENOUGH）
     ⑥ user.platformIncome
     ⑦ 本地事务②(markPaid + Outbox追加[同事务] + 幂等绑定)
     ⑧ Outbox提交后即投 + 中继3s重投 → RabbitMQ(quorum) → ai镜像消费(insertIgnore幂等)
 失败: 查钱包凭据 → 未扣款: restore+refund(凭据前置,空操作安全)+PAY_FAILED
                        已扣款: 不动,留PAYING → 恢复任务按证据续走成功路径
```

## 一次导购任务的完整路径

```
POST /shoppingGuideTask/add → 落库WAITING → MQ(smartore.guide.task) → 立即返回
ai.GuideTaskConsumer: 状态闸门(非WAITING直接ack) → RUNNING
AgentExecutor(工具注册表): system消息(工具清单自动生成) 
  └─ 循环(≤10轮): chatCompletion(tools) → tool_calls逐个分发给AgentTool策略Bean
       每次调用落agent_step(轨迹) → 工具结果回填对话
  └─ submit_recommendations(终结) → 用goods实时数据复核(下架/无货剔除) → 落地推荐
终态落库 DONE/FAILED；前端轮询收敛
兜底: WAITING>90s重发 / RUNNING>10min重置重发 / 失败原因落库后ack(毒丸不重投)
```

## 消息拓扑（RabbitMQ）

| 队列 | 生产者 | 消费者 | 说明 |
|---|---|---|---|
| smartore.trade.event | trade(Outbox中继) | ai(镜像) | 订单事件，event_id幂等 |
| smartore.guide.task | ai | ai | 导购任务，任务表为事实源 |
| smartore.embedding.job | ai | ai | 向量批量，Redis锁全局唯一 |
| smartore.*.dead (×3) | DLX | — | 死信，告警规则盯水位 |

## 可观测性

- 指标：`/actuator/prometheus`（5服务）→ Prometheus(15s) → Grafana 总览/LLM画像
- 打点：`smartore.llm.call{kind=chat|embedding|agent, model, outcome}` 耗时直方图
- 日志：traceId（网关生成→信任头→服务MDC→Feign透传）贯穿五服务
- 告警：服务宕/5xx比例/堆内存/LLM错误率/LLM P95（ops/monitoring/alerts.yml）

## 部署形态

- **本地（mall 栈）**：`deploy/compose.yaml`（mysql/redis/rabbit/nacos）+
  `ops/monitoring/compose.monitoring.yaml`（prometheus/grafana）+ dev.sh 起服务
- **集群**：`ops/`（四动词CI网关、deploy.sh健康门禁、systemd、nginx、recon.sh 对账 cron）
