# MindCart 架构说明

> 决策依据见 `docs/adr/0001-0013`；本文讲「现在长什么样、请求怎么走」。

## 分层与模块

```
backend/
├── mindcart-common          统一返回/错误码/全局异常(含校验异常)/用户上下文/Feign透传/AES/单号
│                            共享装配：CacheAutoConfiguration(可选开关)/OpenApiAutoConfiguration/
│                            audit(OperationLog 注解 + 切面 + Recorder SPI)
├── mindcart-gateway         WebFlux 网关：AuthGlobalFilter（认证+RBAC+信任头+traceId）+ Redis 限流
│                            + 接口文档聚合（一个 /doc.html 汇总五个服务的 spec）
├── mindcart-user/           api: UserFeignClient/UserVO/WalletOpRequest/WalletStatusVO
│                            app: 账号(BCrypt)/地址/钱包(充值)/WalletSagaService(四步幂等)/文件
├── mindcart-goods/          api: GoodsFeignClient/ProductVO/StockOpRequest/AfterSaleRuleVO/...
│                            app: 商品CRUD(dto/ 请求DTO)/库存权威表+StockSagaService/评价/售后规则
│                                 + internal + Redis 缓存(ADR-0012) + oper_log 审计
├── mindcart-trade/          api: OrderFeignClient/OrderStatus(状态机)/TradeEventPayload/...
│                            app: 购物车/OrderSagaService+OrderTxService/幂等表/Outbox(退避重试+人工复位)
│                                 /恢复任务 + internal
└── mindcart-ai/             模型配置(加密)/提示词/SpringAiModelFactory/RAG/AgentExecutor+工具注册表/
                             问答(SSE 有界线程池)/分析报告/MQ消费者(导购/向量/事件镜像)/NameFillService
                             + oper_log 审计
└── mindcart-voice/          语音导购通道（ADR-0011）：ASR/TTS omni 实时会话 + 意图编排状态机 +
                             pgvector 同步目录检索 + 三级记忆 + 前端代执行交易（order_action/order_result）；
                             自带 PG(mindcart_voice)，业务事实经 Feign /internal/** 取自 goods/user/trade
```

## 请求进入业务之前：统一入口防线

```
浏览器 → 网关 JWT 验签 → RBAC(yml 规则表) → 剥伪造信任头 + 注入身份/traceId → Redis 令牌桶
       → 下游 UserContextFilter(双令牌校验) → 控制器 @Validated(Create|Update) 请求 DTO 校验
       → 服务层业务校验(手写白名单/状态机/幂等)
```

三层各管一段，互不替代：网关只管"能不能进来"，DTO 管"字段合不合法"，
服务层管"这个身份此刻允不允许做这件事"（如普通用户改资料时的字段白名单）。
约定与踩坑记录见 ADR-0013。

## 可观测与运维面（本轮新增）

| 能力 | 入口 | 说明 |
|---|---|---|
| 接口文档 | `/doc.html`（网关聚合） | 五个服务的 spec 在 `/doc/<svc>/v3/api-docs`，网关聚合成单页 |
| 操作审计 | `/operLog`、`/aiOperLog`（ADMIN） | 管理端改价/改库存/审核/改模型配置等留痕；审计表分库 |
| Outbox 水位 | `/shopOrder/outboxStats`（ADMIN） | PENDING/PUBLISHED/FAILED 计数 |
| Outbox 复位重投 | `POST /shopOrder/replayOutbox`（ADMIN） | 替代过去手写 SQL 的处置方式 |
| 覆盖率 | `mvn test` → `target/site/jacoco/` | JaCoCo 绑在 test 阶段；CI 打印按模块汇总并上传报告 |

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
POST /shoppingGuideTask/add → 落库WAITING → MQ(mindcart.guide.task) → 立即返回
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
| mindcart.trade.event | trade(Outbox中继) | ai(镜像) | 订单事件，event_id幂等 |
| mindcart.guide.task | ai | ai | 导购任务，任务表为事实源 |
| mindcart.embedding.job | ai | ai | 向量批量，Redis锁全局唯一 |
| mindcart.*.dead (×3) | DLX | — | 死信，告警规则盯水位 |

Outbox 重投采用**退避**而非固定间隔：`next_retry_at` 由失败次数决定（5s→30min 封顶），
中继每 3 秒只捞"退避时间已到"的事件。此前固定 3 秒重投全部 PENDING，
broker 故障时会持续硬拍打；重试耗尽转 FAILED 后由 `/shopOrder/replayOutbox` 人工复位。

## 测试与覆盖率

| 层 | 位置 | 说明 |
|---|---|---|
| 单元 | 各模块 `src/test` | 纯逻辑与映射口径（校验异常、缓存键、检索、合规…） |
| 集成 | `mindcart-trade/app`、`mindcart-goods/app` | Testcontainers 真 MySQL（+ 真 Redis）：Saga 六个分支、Mapper 绑定守卫、缓存命中与失效 |
| 评测 | `mindcart-voice`（`-P eval`） | 需外部服务与真实 Key，默认 `@Tag("eval")` 排除 |

`mvn test` 即产出 `target/site/jacoco/`（JaCoCo 绑在 test 阶段，不等到 verify）。

## 可观测性

- 指标：`/actuator/prometheus`（6服务，含 voice/9105）→ Prometheus(15s) → Grafana 总览/LLM画像
- 打点：`mindcart.llm.call{kind=chat|embedding|agent, model, outcome}` 耗时直方图
- 日志：traceId（网关生成→信任头→服务MDC→Feign透传）贯穿六服务
- 告警：服务宕/5xx比例/堆内存/LLM错误率/LLM P95（ops/monitoring/alerts.yml）

## 部署形态

- **本地（mall 栈）**：`deploy/compose.yaml`（mysql/redis/rabbit/nacos/postgres(pgvector，voice 专用)）+
  `ops/monitoring/compose.monitoring.yaml`（prometheus/grafana）+ dev.sh 起服务
- **容器（app 栈）**：`deploy/compose.apps.yaml`（六个服务 + web，复用 mall 中间件网络），
  入口 `http://localhost:${MINDCART_WEB_PORT:-8081}`
- **集群**：`ops/`（四动词CI网关、deploy.sh健康门禁、systemd、nginx、recon.sh 对账 cron）

> 运维提示：app 栈重建某个服务后，nginx 侧会继续用容器旧 IP（上游在启动时解析一次），
> 表现为经 web 容器访问 `/api/**` 返回 502，而直连网关 9080 正常。
> 处置：`docker restart mindcart-web` 让它重新解析。
