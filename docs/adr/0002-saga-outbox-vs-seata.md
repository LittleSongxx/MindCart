# ADR-002：交易一致性 = Saga + 幂等步骤 + Outbox + 恢复任务（否决 Seata AT）

日期：2026-09-15 ｜ 状态：已实施（含故障注入实测）

## 背景

下单链路跨 3 服务 4 个本地事务：trade(订单) + goods(库存) + user(买家扣款) + user(平台收款)。
备选：Seata AT（集群上现成、Smartlect 用过）vs 无协调器的最终一致方案。

## 决策分析

| 维度 | Seata AT | Saga+幂等+Outbox+对账（选定） |
|---|---|---|
| 热点行 | 全局锁持有到全局提交，SKU/用户行串行化 | 各服务本地短锁 |
| 协调器依赖 | Seata server 宕机 → **下单全站不可用**（Smartlect 实测 1.6s 快速失败） | 无协调器；组件故障 → 单笔订单失败/挂起自愈 |
| 可审计性 | undo_log 不可读 | 每步留痕（钱包/库存流水+事件账本）即对账数据源 |
| 实现成本 | @GlobalTransactional 一行 | 幂等步骤 + 补偿 + 恢复任务（~300 行，可控） |
| 适用场景 | 高并发强一致诉求 | 演示级流量、支付为本地模拟、可接受秒级收敛 |

本项目支付是本地模拟、流量为演示级 —— **Seata 的代价买不来收益**，选最终一致。

## 实现（三层防线）

1. **原子层**：`UPDATE stock SET available=available-? WHERE available>=?`、
   `UPDATE user SET balance=balance-? WHERE balance>=?` —— 数据库条件更新兜底。
2. **幂等层**：所有远程步骤以 orderNo 为幂等键（INSERT IGNORE 流水先占位，占住才动钱/货）；
   下单 requestId 唯一 + requestHash（同请求重放返回原订单，异内容 409）；
   订单状态机全条件转移（`WHERE status=期望前态`，影响行数=0 即并发冲突）。
3. **对账层**：事件 Outbox（与状态变更加同事务）→ RabbitMQ quorum+DLX → ai 镜像；
   `ops/recon.sh` 逐单核对六组不变量。

## 补偿的关键安全设计

- 退款只在**存在 PAY 流水**时生效（凭据前置）—— 补偿永不凭空造钱；
- 回补库存只在**存在 DEDUCT 流水**时生效 —— 同理；
- 失败处理先查钱包凭据：已扣款 → 不做破坏性补偿，交恢复任务按证据续走成功路径
  （防止"已收款却判失败"的资错方向）。

## 恢复任务（收敛器）

- PAYING > 60s：查钱包凭据 → paid ? 补完成（income/markPaid/outbox 全幂等）: 回补库存+判失败
- CANCELLING > 60s：重放取消补偿步骤（全幂等）后落终态
- 崩溃实测：扣款后进程被杀 → 订单停 PAYING → 恢复任务自动收敛 PAID（本仓库验证记录）

## Spring 事务陷阱（工程细节）

编排器（OrderSagaService，无事务）与本地事务单元（OrderTxService，@Transactional）分离成两个 Bean ——
同类内自调用会绕过代理使事务失效。
