# Smartore 实施状态

更新：2026-09-15。本文是唯一现状来源；架构决策见 `docs/adr/`，证据细节见 `docs/evidence.md`。

## 系统现在是什么

- **5 服务微服务**（gateway / user / goods / trade / ai，Nacos 注册发现）+ 3 契约 api 模块 + common，
  Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / SCA 2025.0.0.0 / Java 21，4 库分域 Flyway 自管。
- **交易一致性**：Saga + 幂等 + Outbox + 恢复任务（ADR-002，否决 Seata 的分析在案）；
  库存/余额条件 UPDATE，订单状态机条件转移，requestId 幂等下单。
- **AI 域**：模型配置 AES-GCM 加密落库 + 环境变量冷启动升级；RAG（知识→切片→向量→余弦检索，
  无向量模型回退本地哈希）；导购 Agent MQ 异步执行（工具注册表策略模式，
  实测 qwen3.7-plus 多轮 Function Calling 收敛）；评价分析/成长报告跨服务取数。
- **安全**：BCrypt、JWT 固定密钥、网关集中 RBAC 规则表、内部令牌防绕过、
  文件上传登录+白名单+大小限制、API Key 脱敏、CORS/依赖收敛。
- **可观测**：Prometheus 5 targets、告警规则（服务宕/5xx/JVM/LLM 错误率/P95）、
  Grafana 总览+LLM 画像看板、LLM 调用 Micrometer 打点、traceId 全链路日志。
- **交付**：CI（backend mvn test + web build + 手动 deploy/rollback 四动词网关）、
  systemd 单元、nginx、对账脚本、dev.sh 全生命周期、mall compose 栈。

## 验证台账（本地 mall 栈，全部可复跑）

| 项 | 结果 | 复验方式 |
|---|---|---|
| 冒烟：登录/路由/RBAC | ✅ | `./scripts/dev.sh check` |
| 交易链路 8 项断言（收敛/幂等/扣减/退款精确/不双退/回补） | ✅ | `/tmp/trade_e2e.py`（脚本随 evidence 归档） |
| 并发防超卖（库存3×两用户并发） | ✅ 恰1单成功、库存精确 | `StockSagaConcurrencyTest`（Testcontainers） |
| 崩溃自愈（扣款后杀进程→PAYING→恢复任务收敛 PAID） | ✅ 实测 | 见 evidence.md |
| Outbox→MQ→镜像逐事件一致 | ✅ ledger=mirror | `ops/recon.sh` |
| 对账注错演练（孤儿事件注入） | ✅ MISMATCH→回退→OK | `ops/recon.sh` |
| 导购 Agent 异步收敛 DONE（真实 LLM） | ✅ 推荐基于实时数据 | 提交任务后轮询 |
| 测试套件 | ✅ 11/11 | `cd backend && mvn test` |
| 前端构建 + /api 走网关 | ✅ | `npm run build` + dev 联调 |

## 2026-09-16 第二轮：AI Agent 工程化（ADR-006~010）

- 混合检索 BM25+RRF（中文 bigram 分词纯函数单测 4/4；mode=dense 对照通道保留）
- SSE 流式问答（delta/complete/error 事件；导购任务保持轮询——连接不是任务状态）
- 容错：瞬时错误重试≤2、参数解析失败显式终止、循环指纹第3次止损；token 采集入 agent_run（失败也归因）
- QA 多轮会话（最近3轮+1200字符预算；qa_conversation/shopping_qa.conversation_id）
- 评测 v1（确定性断言，无 judge）：拒答 5/5；检索 A/B hybrid≥dense（严格口径 3/5 vs 2/5）；
  导购评测 首跑 4/15 → 三轮迭代 **15/15**（抓出 3 个真 Bug：无关键词检索空转→召回放宽、
materialize 缺预算硬校验→确定性防线、价格过滤在分页后→下沉 SQL；期间实证 LLM 方差——预算100 单轮翻转）
- 评测器自身校准 2 次（拒答词表假阴性、检索谓词与语料措辞不符）——判分器未校准前分数不可信

## 已知边界（不掩盖）

- 向量检索为内存全量余弦 + BM25 全量打分（数据量级内正确；上量需换向量索引/倒排）
- 切片语料不含商品编号（编号类查询两头失败，语料侧待办：切片标题拼编号）
- 评测样本 <50 无置信区间，只用于回归对比；judge/holdout 等集子扩大后再引入
- 支付为本地模拟（钱包余额），无外部支付网关
- 单实例部署件（systemd 顺序拉起）；中间件单机 docker（集群件已备未上）
- 评价"是否已买"经 trade 查询（弱一致窗口内可能放行刚下单的评价，无害）
- CORS 未收紧到具体域名（演示环境放行；上生产前收紧）

## 下一步候选

- 集群替换上线（ops/ 部署件就绪：四动词网关 + systemd + nginx + 对账 cron）
- 对账 cron 常驻 + Alertmanager 邮件通道接入
- 前端 manager 端与前台拆分部署（当前单 SPA 够用）
