# ADR-0011：语音导购通道（mindcart-voice，WS + 前端代执行交易）

日期：2026-09-22 ｜ 状态：已实施并实测

## 为什么

mindcart-ai 的文本链路（ADR-003 异步导购 + ADR-008 SSE 问答）覆盖不了语音：语音是
**双向、长连、音频帧级别**的交互（上行 PCM 流 + 下行 TTS 流式音频），SSE 只能下行文本、
轮询更没有实时性——超出 ADR-008 划定的边界，需要 WebSocket。

新能力来自 jc-voice-shopping 项目的整体移植（语音链路 + 意图编排 + pgvector 检索 +
三级记忆 + 评测体系），与 mindcart-ai **共存分工**：voice 独占语音 I/O 与语音会话编排；
文本导购/RAG/管理面留在 ai 不动。

## 怎么做

- **拓扑**：`mindcart-voice`（9105）注册 Nacos；网关两条路由——`lb:ws://` 承接
  `/voice/ws`（WS 升级），`lb://` 承接其余 `/voice/**`（调试/管理 HTTP）。
- **鉴权**：浏览器 WS 无法自定义头 → token 走 query，网关 `QUERY_TOKEN_PATHS`
  白名单只加 `/voice/ws`（与 `/shoppingQa/askStream` 同款特批）；网关验完 JWT 注入
  `X-Gateway-Token + X-User-Id`，服务侧握手拦截器做常数时间比对——**客户端自报身份
  一律无效**（ADR-0004 信任头体系的延伸）。
- **交易（前端代执行）**：语音确认下单后，服务发 `order_action` 动作帧（含服务端生成的
  requestId 幂等键），前端用**用户自己的登录态**走现成 `/shoppingCart/add` +
  `/shopOrder/create` Saga 链路，结果经 WS 控制帧 `order_result` 回传口播。
  trade 零改动，幂等/防超卖/ Saga 补偿全部复用（ADR-002 的机制原样生效）。
  备选方案（trade 加 `/internal/order/create` 服务端直下单）被否：下单 Saga 强耦合
  购物车选中项，改它要为语音单独开辟第二条交易路径，风险收益不成比例。
- **数据**：自带 PostgreSQL+pgvector（compose 加 `mall-postgres`，库 `mindcart_voice`），
  存 `voice_product` 同步目录 + 向量、语音会话、动态画像；目录经新增的只读端点
  `GET /internal/product/listOnSale` 全量同步（30min 定时 + `/voice/admin/reindex` 手动），
  **价格/库存不进向量**，推荐出口前经 `/internal/product/batch` 实时复核（防幻觉）。
  业务事实只在 MySQL 四库，voice 库全是副本与自有会话数据。

## 部署形态（2026-09-22 补）

两种方式二选一：

- **容器部署**：`deploy/compose.apps.yaml`（六个服务 + web 各一容器，共享 `backend/Dockerfile`
  运行镜像）。外部网络 `mall_default` 复用 mall 中间件容器，容器内以 `mall-postgres` /
  `mall-redis` / `mall-nacos` 等容器名直连——**不新建任何中间件**。浏览器入口 8081
  （nginx 容器，含 WS 升级头）。密钥经 `env_file: run/runtime.env` 注入，不落镜像层。
- **jar 方式**：`scripts/dev.sh up`（开发迭代用）。

两者端口冲突，需互切（`docker compose -f deploy/compose.apps.yaml down`）。

## 后果

- 得到：语音导购完整闭环（实测：19 件在售商品同步向量化；推荐/下单/取消/幂等拦截全链路通）；
  132 个离线单测并入 CI 门禁；eval 评测体系（`-P eval` 手动）。
- 付出：多一个 PG 中间件与 Java 进程；**原项目封存评测集与旧商品目录绑定，
  意图/检索指标的绝对读数需按 MindCart 类目重标后才可跨基线对比**（见
  `backend/mindcart-voice/eval/README.md` 的诚实声明）。
- 边界：~~语音 FAQ/售后问答一期不接~~ **已打通（2026-09 后续轮次）**：voice 经
  `KnowledgeClient → POST /internal/knowledge/search` 复用 ai 侧知识库混合检索
  （见 `KnowledgeAnswerService`，阈值口径与文本问答一致）；本行保留作决策时点记录。
