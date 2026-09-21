# Smartore

AI 电商全链路项目：**Spring Cloud 六服务微服务底座 + Vue 3 前端 + AI 导购/RAG 客服 + 语音导购**，
本地 docker-desktop `mall` 栈可一键起全套（中间件 + 监控），集群部署件随仓库提供。

AI 智能导购页（`/front/guide`）：**页内可切换文本导购 / 语音导购**，两条通道共用同一业务底座与历史。
语音闭环：推荐 → 澄清 → 比价 → 下单/取消 → 查订单 → 政策问答；每次对话落会话归档
（用户端"我的语音记录"可回放，管理端"语音会话与归因"看 GMV/转化率）；
政策/售后类问题复用 ai 知识库作答；文本导购 Agent 可经 `query_voice_history` 读到语音通道记录。

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
 smartore-user    smartore-goods   smartore-trade      smartore-ai       smartore-voice
 账号/地址/钱包     商品/库存/评价     购物车/订单/支付编排   模型配置/RAG/     语音导购（ASR/TTS+
 (smartore_user)  (smartore_goods)  (smartore_trade)    导购Agent/问答      意图编排/pgvector）
                                                      (smartore_ai)     (smartore_voice, PG)
     └────┬────────────┴────────────────┘                  ▲  ▲
          │  Feign（/internal/** 仅集群内可达）             │  │
          └────────────────────────────────────────────────┘  │
                                             RabbitMQ ◀── Outbox ──┘
                                             (导购任务/向量批任务/交易事件+死信)
```

- **6 服务 + 3 契约模块（api）+ common**：契约先行，消费方只依赖 api 不依赖实现
- **4 库分域 + voice 自带 PG**：user / goods / trade / ai 各自持库（Flyway 管理迁移与种子）；
  smartore-voice 持有 `smartore_voice`（PostgreSQL+pgvector：商品同步目录向量/语音会话/动态画像）
- **依赖方向无环**：ai → {user, goods, trade}；voice → {user, goods, trade}；trade → {user, goods}；goods → trade(仅 api 评价校验)

## 核心工程决策（详见 docs/adr/）

| # | 决策 | 一句话理由 |
|---|---|---|
| 001 | 服务边界与 api/app 契约分层 | 高内聚低耦合；跨库 JOIN 全部消灭，改为服务层批量回填 |
| 002 | Saga + 幂等步骤 + Outbox + 恢复任务（**不用 Seata**） | 热点行无全局锁、无协调器单点、每步留痕即对账数据源 |
| 003 | 导购 Agent 走 MQ 异步执行 | 多轮 LLM 不再阻塞 HTTP；任务表即可靠队列，超时兜底重发 |
| 007 | 混合检索 BM25+RRF | 编号/型号字面查询是稠密向量盲区（真实检索缺陷） |
| 008 | QA 流式 SSE + 多轮会话 | H3 题族「输入到前端流式链路」；连接不是任务状态 |
| 011 | 语音通道独立成服务 + 前端代执行交易 | 双向音频流超出 SSE/轮询边界；交易零改动复用 Saga/幂等 |
| 010 | 评测 v1 确定性断言 | 改提示词/换模型从盲飞变成有回归数字；评测器自身校准两次 |
| 004 | 网关集中式 RBAC 规则表 | 鉴权单点化、规则可审计；服务侧信任头 + 内部令牌防绕过 |
| 005 | RAG 资产归 AI 服务 | 知识/切片/向量与模型配置同域，依赖方向保持无环 |

## 一致性设计（三层防线，全部实测）

1. **原子层**：库存/余额条件 UPDATE（`available >= ?` / `balance >= ?`）防超卖防透支
2. **幂等层**：钱包流水 `uk(business_no,type)`、库存流水 `uk(biz_no,product_id,type)`、
   下单 `request_id` 唯一 + requestHash 比对（指纹=userId+收货人三要素；购物车属服务端状态不参与，
   同请求重放=返回原订单）；订单状态机全条件转移（PAYING→PAID/…，PAY_FAILED→CANCELLED 供资错自愈）
3. **对账层**：交易事件 Outbox → RabbitMQ(quorum+DLX) → AI 镜像；`ops/recon.sh` 逐单核对六组不变量，
   注错演练 30 秒内 MISMATCH → 回退 OK（见 docs/evidence.md）

崩溃自愈实测：进程在扣款后崩溃 → 订单停 PAYING → 恢复任务按钱包凭据自动续走成功路径。

## 快速开始（本地 docker-desktop）

### 方式一：容器部署（推荐用于交付/演示）

```bash
./scripts/dev.sh bootstrap     # 生成 run/runtime.env（密钥 600 权限，gitignored）
./scripts/dev.sh infra-up      # 中间件容器：mall-mysql/redis/rabbitmq/nacos/postgres

cd backend && mvn -q package -DskipTests && cd ..
(cd web && npm run build)      # 前端产物（web 容器直接挂载 web/dist）
docker compose -f deploy/compose.apps.yaml up -d --build

./scripts/dev.sh check         # 冒烟：登录/路由/RBAC/语音 WS/目录
```

六个应用服务 + 前端各一个容器，**复用 mall 中间件容器**（外部网络 `mall_default`，容器内以
`mall-mysql` / `mall-redis` / `mall-rabbitmq` / `mall-nacos` / `mall-postgres` 直连，不新建任何中间件）。
浏览器入口 **http://localhost:8081**（web 容器 nginx：静态 SPA + `/api` 反代 gateway 容器）。

### 方式二：jar 方式（开发迭代）

```bash
./scripts/dev.sh up            # 构建并启动 6 个服务（本机 jar 进程）
./scripts/dev.sh check         # 冒烟：登录/路由/RBAC/语音 WS
docker compose -f ops/monitoring/compose.monitoring.yaml up -d   # Prometheus + Grafana
```

两种方式**二选一**（端口冲突）：`docker compose -f deploy/compose.apps.yaml down && ./scripts/dev.sh up` 可互相切换。

模型 Key：写入 `run/runtime.env` 的 `SMARTORE_CHAT_API_KEY` / `SMARTORE_EMBED_API_KEY`
启动时自动加密落库（AES-GCM，密钥不出环境变量；管理界面只见 `sk-****` + **密文**末 4 位——
  仅用于区分"已/未配置"，不可逆推真实 Key 末 4 位）。容器部署下同一份 runtime.env 经
  `env_file` 注入各容器（密钥不落镜像层）。

## 验证记录（本地 mall 栈实测，docs/evidence.md 有完整台账）

- 混合检索：BM25+RRF（mode 参数 A/B 对照）；SSE 流式 delta 实测；评测 v1 首跑抓出 2 个真 Bug
  （无关键词检索空转 → 召回放宽；materialize 缺预算硬校验 → 补确定性防线）——评测的回归价值已兑现

- 交易链路：下单收敛 PAID ✓ requestId 幂等重放 ✓ 库存原子扣减/回补 ✓ 取消退款金额精确 ✓ 重复取消不双退款 ✓
- 并发防超卖：库存 3、两用户并发抢购各 2 件 → 恰 1 单成功、库存精确（Testcontainers 单测固化）
- 事件链路：Outbox→MQ→AI 镜像逐事件一致（ledger=mirror）✓ 注错演练闭环 ✓
- 导购 Agent：MQ 异步执行，LLM 多轮 Function Calling（候选检索→价格/库存/优惠→提交），
  推荐基于实时商品数据复核（下架/无货自动剔除），执行轨迹 agent_step 全程留痕
- 监控：6 服务 Prometheus targets up；LLM 调用画像（QPS/P95/错误率 by model&kind）入 Grafana

## 目录结构

```
backend/   6 服务 + common + 3 个 api 契约模块（Spring Boot 3.5 / Spring Cloud 2025 / SCA 2025）
           共享运行镜像 Dockerfile + .dockerignore（jar 由宿主 mvn 预构建，镜像只做运行时）
web/       Vue 3 + Vite + Element Plus（前台 + 管理端单应用，/api 经网关）
deploy/    mall 中间件 compose（mysql/redis/rabbitmq/nacos/postgres）+ 应用栈 compose.apps.yaml
           + nginx.local.conf（web 容器入口）
ops/       监控(prometheus/告警/Grafana)、对账、CI 四动词网关、systemd、nginx、verify(E2E)
scripts/   dev.sh 全生命周期（bootstrap/infra/up/check/test/...）
docs/      架构文档 + ADR + 证据记录
sql/       单体版遗留全量 dump（仅追溯用，真实 schema 在各服务 Flyway）
```

## 测试

```bash
cd backend && mvn test   # 147 项：voice 132（语音编排/合规/评测口径）+ AES/状态机/Agent循环(mock LLM)/库存并发(Testcontainers 真实 MySQL)
                         # voice 的 eval 评测默认排除（需真实 LLM key），手动跑：mvn test -pl smartore-voice -P eval
```
