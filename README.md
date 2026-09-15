# Smartore

AI 电商全链路项目：**Spring Cloud 五服务微服务底座 + Vue 3 前端 + AI 导购/RAG 客服**，
本地 docker-desktop `mall` 栈可一键起全套（中间件 + 监控），集群部署件随仓库提供。

```
登录账号：admin / admin（管理员）、aaa / 123（买家）
本地入口：前端 dev http://localhost:5173 ｜ 网关 http://localhost:9080 ｜ Grafana http://localhost:3999
```

## 架构一览

```
                      ┌───────────────────────────── nginx / vite dev (/api 反代)
                      ▼
              ┌───────────────┐   JWT 认证 · 集中式 RBAC 规则 · Redis 令牌桶限流 · 信任头注入
              │ smartore-gateway │
              └───┬────┬────┬───┘
     ┌────────────┘    │    └───────────┐
     ▼                 ▼                ▼
 smartore-user    smartore-goods   smartore-trade      smartore-ai
 账号/地址/钱包     商品/库存/评价     购物车/订单/支付编排   模型配置/RAG/导购Agent/问答
 (smartore_user)  (smartore_goods)  (smartore_trade)    (smartore_ai)
     └────┬────────────┴────────────────┘                  ▲  ▲
          │  Feign（/internal/** 仅集群内可达）             │  │
          └────────────────────────────────────────────────┘  │
                                             RabbitMQ ◀── Outbox ──┘
                                             (导购任务/向量批任务/交易事件+死信)
```

- **5 服务 + 3 契约模块（api）+ common**：契约先行，消费方只依赖 api 不依赖实现
- **4 库分域**：user / goods / trade / ai 各自持库（Flyway 管理迁移与种子）
- **依赖方向无环**：ai → {user, goods, trade}；trade → {user, goods}；goods → trade(仅 api 评价校验)

## 核心工程决策（详见 docs/adr/）

| # | 决策 | 一句话理由 |
|---|---|---|
| 001 | 服务边界与 api/app 契约分层 | 高内聚低耦合；跨库 JOIN 全部消灭，改为服务层批量回填 |
| 002 | Saga + 幂等步骤 + Outbox + 恢复任务（**不用 Seata**） | 热点行无全局锁、无协调器单点、每步留痕即对账数据源 |
| 003 | 导购 Agent 走 MQ 异步执行 | 多轮 LLM 不再阻塞 HTTP；任务表即可靠队列，超时兜底重发 |
| 004 | 网关集中式 RBAC 规则表 | 鉴权单点化、规则可审计；服务侧信任头 + 内部令牌防绕过 |
| 005 | RAG 资产归 AI 服务 | 知识/切片/向量与模型配置同域，依赖方向保持无环 |

## 一致性设计（三层防线，全部实测）

1. **原子层**：库存/余额条件 UPDATE（`available >= ?` / `balance >= ?`）防超卖防透支
2. **幂等层**：钱包流水 `uk(business_no,type)`、库存流水 `uk(biz_no,product_id,type)`、
   下单 `request_id` 唯一 + requestHash 比对；订单状态机全条件转移（PAYING→PAID/…）
3. **对账层**：交易事件 Outbox → RabbitMQ(quorum+DLX) → AI 镜像；`ops/recon.sh` 逐单核对六组不变量，
   注错演练 30 秒内 MISMATCH → 回退 OK（见 docs/evidence.md）

崩溃自愈实测：进程在扣款后崩溃 → 订单停 PAYING → 恢复任务按钱包凭据自动续走成功路径。

## 快速开始（本地 docker-desktop）

```bash
./scripts/dev.sh bootstrap     # 生成 run/runtime.env（密钥 600 权限，gitignored）
./scripts/dev.sh infra-up      # mall-mysql/redis/rabbitmq/nacos（复用本地镜像）
./scripts/dev.sh up            # 构建并启动 5 个服务
./scripts/dev.sh check         # 冒烟：登录/路由/RBAC
docker compose -f ops/monitoring/compose.monitoring.yaml up -d   # Prometheus + Grafana
```

模型 Key：写入 `run/runtime.env` 的 `SMARTORE_CHAT_API_KEY` / `SMARTORE_EMBED_API_KEY`，
启动时自动加密落库（AES-GCM，密钥不出环境变量；管理界面只见 `sk-****末4位`）。

## 验证记录（本地 mall 栈实测）

- 交易链路：下单收敛 PAID ✓ requestId 幂等重放 ✓ 库存原子扣减/回补 ✓ 取消退款金额精确 ✓ 重复取消不双退款 ✓
- 并发防超卖：库存 3、两用户并发抢购各 2 件 → 恰 1 单成功、库存精确（Testcontainers 单测固化）
- 事件链路：Outbox→MQ→AI 镜像逐事件一致（ledger=mirror）✓ 注错演练闭环 ✓
- 导购 Agent：MQ 异步执行，LLM 多轮 Function Calling（候选检索→价格/库存/优惠→提交），
  推荐基于实时商品数据复核（下架/无货自动剔除），执行轨迹 agent_step 全程留痕
- 监控：5 服务 Prometheus targets up；LLM 调用画像（QPS/P95/错误率 by model&kind）入 Grafana

## 目录结构

```
backend/   5 服务 + common + 3 个 api 契约模块（Spring Boot 3.5 / Spring Cloud 2025 / SCA 2025）
web/       Vue 3 + Vite + Element Plus（前台 + 管理端单应用，/api 经网关）
deploy/    mall 中间件 compose（mysql:8.4.11 / redis:7.4.7 / rabbitmq:4.2.9 / nacos:v2.5.3）
ops/       监控(prometheus/告警/Grafana)、对账、CI 四动词网关、systemd、nginx
scripts/   dev.sh 全生命周期（bootstrap/infra/up/check/test/...）
docs/      架构文档 + ADR + 证据记录
sql/       单体版遗留全量 dump（仅追溯用，真实 schema 在各服务 Flyway）
```

## 测试

```bash
cd backend && mvn test   # 11 项：AES/状态机/Agent循环(mock LLM)/库存并发(Testcontainers 真实 MySQL)
```
