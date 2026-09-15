# ADR-003：导购 Agent 走 MQ 异步执行（工具注册表策略模式）

日期：2026-09-15 ｜ 状态：已实施（含真实 LLM 端到端验证）

## 背景

单体版在 HTTP 线程里同步跑完多轮 Function Calling（每轮 LLM 调用可达 60s），
前端 axios 30s 超时必然踩雷；工具分发是一个 200 行 switch-case。

## 决策

**执行模型**：`POST /shoppingGuideTask/add` → 落库 WAITING → 发 RabbitMQ（quorum+DLX）→ 立即返回；
消费者执行 Agent 循环，每轮工具调用落 agent_step；前端轮询任务状态收敛。

**可靠性**（不丢任务的三重保障）：
1. 任务表即事实源：消费者以任务当前状态为准（非 WAITING 直接 ack，天然幂等）；
2. 入队失败/消息丢失：WAITING > 90s 兜底扫描重发；
3. 消费者挂死：RUNNING > 10min 重置 WAITING 重发（重放安全——新一轮执行新建 AgentRun）。

执行异常：失败原因落库后 ack（毒丸消息不无限重投），页面可见 FAILED+原因。

**工具注册表（策略模式）**：每个工具是实现 `AgentTool` 接口的独立 Bean
（name/label/schema/execute 内聚），执行器只认识注册表不认识具体工具 ——
新增工具 = 新增一个类，分发逻辑零改动（OCP）。终结工具（submit_recommendations）
由 `isTerminal()` 声明，执行器据此收口；模型提交的商品仍要用实时商品数据复核
（下架/无货剔除）后落地 —— 防幻觉的证据链。

**向量批量生成同模式**：MQ 触发 + Redis 分布式锁（替代单机 AtomicBoolean）+
进度存 Redis（多实例一致，替代单机 volatile）。

## 实测

qwen3.7-plus 两轮工具调用收敛 DONE；推荐结果价格/库存/折扣全部来自 goods 实时查询；
AgentExecutor 单测（mock LLM 脚本化响应）固化循环终止与落地复核行为。
