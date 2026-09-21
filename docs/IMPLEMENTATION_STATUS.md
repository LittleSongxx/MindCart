# Smartore 实施状态

更新：2026-09-22。本文是唯一现状来源；架构决策见 `docs/adr/`，证据细节见 `docs/evidence.md`。

## 系统现在是什么

- **6 服务微服务**（gateway / user / goods / trade / ai / **voice**，Nacos 注册发现）+ 3 契约 api 模块 + common，
  Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / SCA 2025.0.0.0 / Java 21，4 库分域 Flyway 自管
  + voice 自带 PostgreSQL/pgvector（库 `smartore_voice`，compose 容器 mall-postgres）。
- **交易一致性**：Saga + 幂等 + Outbox + 恢复任务（ADR-002，否决 Seata 的分析在案）；
  库存/余额条件 UPDATE，订单状态机条件转移，requestId 幂等下单。
- **AI 域**：模型配置 AES-GCM 加密落库 + 环境变量冷启动升级；RAG（知识→切片→向量→余弦检索，
  无向量模型回退本地哈希）；导购 Agent MQ 异步执行（工具注册表策略模式，
  实测 qwen3.7-plus 多轮 Function Calling 收敛）；评价分析/成长报告跨服务取数。
- **安全**：BCrypt、JWT 固定密钥、网关集中 RBAC 规则表、内部令牌防绕过、
  文件上传登录+白名单+大小限制、API Key 脱敏、CORS/依赖收敛。
- **可观测**：Prometheus 6 targets（含 voice/9105）、告警规则（服务宕/5xx/JVM/LLM 错误率/P95）、
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
| 测试套件 | ✅ 147/147（voice 132 + 其余模块 15） | `cd backend && mvn test` |
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

## 2026-09-22 第六轮：双 AI 域统一视图 + 用户侧语音记录 + 导购页合并

| 项 | 做法 | 验证 |
|---|---|---|
| **② 统一视图**（文本 AI 看不到语音通道） | voice 新增只读 `GET /internal/voice/user-summary/{userId}` 与 `/sessions/{id}/messages`；ai 新增 `VoiceFeignClient` + 工具 `query_voice_history`（Spring 注册即生效） | 文本导购任务（userId=2）第 1 步即 `query_voice_history` DONE，输出含语音会话与语音下单记录（agent_step 轨迹可查） |
| **③ C 端语音记录** | voice 新增 `GET /voice/my/sessions`（含开场需求/轮数/成交额，过滤 0 轮空会话）与 `/voice/my/sessions/{id}`（对话流水 + 语音订单）；身份只取网关注入的 X-User-Id | aaa 可见自己的会话与回放（17 条消息/1 条订单）；**越权访问他人会话 → HTTP 403**（`CustomException(FORBIDDEN)`，common 异常处理器统一映射） |
| **导购页合并**（文本与语音合为一个 AI 智能导购页） | 抽出 `components/guide/VoiceGuidePanel.vue`（含"我的语音记录"）；`Guide.vue` 顶部加通道切换（文本/语音），`?mode=voice` 可深链；删掉独立语音页与导航项，`/front/voice` 重定向到合并页语音模式 | 浏览器实测：文本模式默认（语音记录隐藏）→ 页内点击切语音（URL 同步 `?mode=voice`）→ 记录 1 条真实会话（"耳机坏了想退货" 8 轮 成交 ¥69）→ 切回文本；旧链接重定向正常 |

视觉调整（同轮）：通道切换从两张满宽大卡改为**一行胶囊分段控件 + 一行模式说明**（不再与内容区抢视觉重心）；
hero 收紧（min-height 210→176、标题 31→27px）；语音卡重排（麦克风 68→56px、状态文案置顶、示例话术 chips 补齐右侧留白，
与文本模式的建议按钮形成呼应）；卡片圆角/阴影/间距统一为 14px/同一阴影体系。

顺带修复：**上传文件目录改绑宿主 `run/files`**（容器化后换过卷，用户历史头像 404）——现在容器与 jar 方式共用同一份上传存储，控制台无异常响应。

## 2026-09-22 第七轮：合并后缺口清扫（语音入口归因 + LLM 配置统一 + 运维收口）

合并后复查（四路并行排查 + 全量编译/测试）确认主干零断链，本轮补齐残留缺口：

| 项 | 做法 | 验证 |
|---|---|---|
| **商详页/搜索兜底语音入口未挂载**（PRODUCT_PAGE / SEARCH_FALLBACK 渠道永远无数据） | `VoiceGuidePanel` 改为 props 接 channel/productId；商详页加"语音问这款"按钮（带 productId 进 `/front/guide?mode=voice&channel=PRODUCT_PAGE`）；首页搜索无结果时出"对 AI 说一句"兜底入口（SEARCH_FALLBACK）；`Guide.vue` 切通道时保留归因 query | `npm run build` 通过；入口深链可直接复跑 |
| **语音 WS 地址硬编码 /api** | `voiceSession.js` 改读 `VITE_BASE_URL`（与 request.js 同源），改前缀时 HTTP/WS 不分叉 | 前端构建通过 |
| **两套 LLM 配置体系割裂**（ai 后台改模型对语音链路无效） | ai 新增 `GET /internal/model-config/active`（解密下发当前启用的 CHAT/EMBEDDING）；voice 新增 `LlmConfigResolver`：首次使用拉取远端、失败回退本地 yml；chat 三档模型/baseUrl/apiKey 与 embedding key/model 统一走解析器；远端 EMBEDDING 仅当 DashScope 端点时采纳（voice 向量走原生 SDK）；embedding 缓存 key 纳入模型名防换模型后串维度 | `mvn test` 147/147 绿 |
| **voice 归因镜像无对账** | `ops/recon.sh` 第 9 组不变量：voice_order_event（PG）SUCCEEDED 订单逐单反查 trade（孤儿成交检测）+ DISPATCHED 超 2h 未收口预警；PG 不可达显式报 `voice_pg_unreachable` | `bash -n` 通过，本地 mall 栈可复跑 |
| **CI 网关 status 不探 voice** | `ci-gateway.sh status` 增加 voice/9105 health 探测 | `bash -n` 通过 |
| **"五服务"时代文案残留** | systemd/deploy.sh/prometheus/README/IMPLEMENTATION_STATUS/architecture.md 共 8 处改为六服务；architecture.md 中间件清单补 postgres、ADR 范围改 0001-0011；ADR-0011 边界"FAQ 一期不接"回填为已打通（KnowledgeClient 实装）；测试计数 11→147 两处口径统一 | grep 复查零残留 |

voice pom 的 `spring-ai-alibaba-bom` 覆盖父 pom `spring-ai-bom` 的问题经 `dependency:list` 核实：voice 类路径上无任何
org.springframework.ai 构件（AgentScope 直连 OpenAI 兼容端点），覆盖对运行无影响，已在 pom 内加注防未来误引。

## 2026-09-22 第五轮：集成缺口补足（数据链路闭环）

上一轮集成审计暴露的 9 项缺口，本轮全部补足并逐项验证：

| # | 缺口（此前形态） | 补足做法 | 验证证据 |
|---|---|---|---|
| 1 | **对话正文不落库**（`session_message` 0 行，无法审计/回溯） | V2 迁移补 `intent`/`latency_ms` 列 + `ConversationJournal`（用户/助手/系统三类行，异步旁路写入，失败不影响链路）；同步路径与流式路径各接入一次，不双写 | 单会话 12 行完整流水（USER/ASSISTANT/SYSTEM + 生效意图），管理端可回放 |
| 2 | **订单无归因**（业务库 shop_order 无会话字段，语音贡献不可统计） | 新增 `voice_order_event`（requestId ↔ orderNo ↔ sessionId，派发/成交/失败/取消全链状态），requestId 与交易侧幂等表同键 | `CREATE SUCCEEDED orderNo=OD…802311 ¥69 requestId=voice-2267…`；stats 显示 GMV ¥69、派发 1、成交 1 |
| 3 | **会话 outcome/channel 未落地** | WS 关闭时 `SessionService.close()` 写 `outcome=ORDERED/ABANDONED` + `ended_at`；握手读 `channel`/`productId` 归因入口；每轮 `touch()` 刷 `last_active_at` | 会话 `voice-gap-1` outcome=ORDERED；管理端转化率 5.0%（1/20） |
| 4 | **记忆非持久**（Redis TTL 到期即失忆） | `ConversationJournal.hydrateIfCold()`：Redis 冷启动时从 PG 回灌最近轮次进 ShortTermMemory，WS 建连/每轮入口触发 | 手动删 Redis 键后再对话 → 日志"会话上下文已从 PG 回灌 行数=12" |
| 5 | **画像不吃历史订单** | trade 新增只读端点 `GET /internal/order/recent-product-ids/{userId}`；`ProfileOrderSync` 在建连时把真实已购商品并入动态画像（合并去重） | `user_profile_dynamic.recent_purchased = {19}`（用户 2，"考试考证"亲和 0.1） |
| 6 | **购物车副作用**（语音下单清掉用户原有勾选） | Voice.vue 下单前记录被取消勾选的行，`finally` 中恢复（成功失败都恢复） | 代码路径 + 前端构建通过；下单不再留下"别人的商品被取消勾选"状态 |
| 7 | **知识库未接**（政策/售后/物流类只能兜底） | ai 新增只读端点 `POST /internal/knowledge/search`（全库检索，阈值 0.5 与站内问答同口径）；voice 在兜底分支先检索，命中才作答且要求依据资料 | 实测："耳机坏了想退货" → 依据售后规则答"7 天内可退/拆封化妆品不退"；"质量有问题能换货吗" → 引用质保条款 |
| 8 | **管理端无可见性** | 新增 `VoiceAdminController`（会话列表/详情/订单流水/汇总）+ 管理端页面"语音会话与归因"（指标卡 + 会话表 + 对话流水 & 归因订单弹窗） | 浏览器 E2E 6/6：`BASE=…8081 node ops/verify/voice_manager_e2e.mjs` |
| 9 | **语音不能查订单**（本轮验证中新发现：问"我刚买的书"被兜底） | 规则前置分支 `OrderQueryService`（关键词命中即进，不占 LLM 往返）：trade 取最近订单 + goods 批量补商品名，口播"尾号/金额/状态/买过什么" | 实测："我的订单到哪了" → "你最近有 3 个订单，最近一单尾号 802311，69 元，已付款。买过：考研英语高分词汇与真题解析" |

说明：缺口 6 的前半段（`updateById` 需 `id+quantity` 完整字段）是本轮端到端验证抓到的 Voice.vue 真 Bug，已修；
缺口 8 的会话详情端点首版因 jsonb/数组直接序列化报 500，已改为 `::text` 投影。

## 2026-09-22 第四轮：容器化部署（复用 mall 中间件）+ 三个真 Bug 修复

**部署形态**：六个服务 + 前端各一个容器（`deploy/compose.apps.yaml`，共享 `backend/Dockerfile`
运行镜像，jar 由宿主 mvn 预构建）；外部网络 `mall_default` 复用 mall 全部中间件容器，不新建任何中间件。
浏览器入口 http://localhost:8081（nginx 容器：静态 SPA + /api 反代 gateway 容器，含 WS 升级头）。
与 jar 方式（dev.sh up）二选一，可互切。

**本轮修掉的真 Bug（都是运行方才暴露，静态检查/单测覆盖不到）**：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 网关容器起不来（Redis 连接失败） | 网关 yml 从未配 `spring.data.redis`，Boot 回落到 localhost:6379——宿主恰好有本地 Redis 才一直"正常"，且限流状态与业务 Redis 实例不一致 | 网关 yml 补 `spring.data.redis`（与其他服务同款 SMARTORE_REDIS_* 三件套） |
| 2 | 容器里网关连不到 voice（503） | voice 注册 IP 被硬编码 `127.0.0.1`（此前为宿主 jar 模式所加），容器内网关解析到自己的 loopback | 移除硬编码，改自动探测（服务绑 0.0.0.0，两种模式都可达） |
| 3 | **生产构建前端白屏**（登录页 0 输入框，控制台 `TypeError: c.then is not a function`） | 路由 `component: () => X`（箭头返回静态导入组件）被 vue-router 当作异步加载器调用；dev 无事、**生产构建下所有静态路由全挂** | 39 处改为 `component: X`；`() => import(...)` 的合法懒加载保持不变 |

Bug 3 说明：此前所有浏览器 E2E 都跑 vite dev（5173），CI 的 web job 只 build 不跑页面，
部署健康门禁只探 `GET /` 的 200——三道闸都没覆盖"生产产物真实渲染"，所以长期未暴露。

### 验证台账（本轮，全部针对容器栈）

| 项 | 结果 | 方式 |
|---|---|---|
| 六服务 + web 容器全健康 | ✅ | `docker compose -f deploy/compose.apps.yaml ps` |
| Nacos 注册（各自容器 IP，网关可解析） | ✅ 6/6 | Nacos instance list API |
| 冒烟（登录/路由/RBAC/语音 WS/目录 19 件） | ✅ | `./scripts/dev.sh check` |
| 真实 LLM 对话（经网关，容器内调 LLM + Feign 复核 + pgvector） | ✅ 返回真实商品与价格 | `/voice/debug/chat` |
| 下单闭环（预览→确认→动作帧→重复确认拦截） | ✅ | 同上，actionFrame.requestId 可见 |
| **ASR+TTS 回环（容器内 omni 实时模型）** | ✅ 12/12 轮，首帧音频 P50 2.45s | `mvn test -pl smartore-voice -P eval -Dtest=EvalE2eLatencyTest` |
| 浏览器全链路（nginx→网关→服务，含 SSE 问答） | ✅ 6/6 | `BASE=http://localhost:8081 node ops/verify/browser_e2e.mjs` |
| 语音页浏览器 E2E（登录→渲染→录音状态机→WS） | ✅ 5/5 | `BASE=http://localhost:8081 node ops/verify/voice_e2e.mjs` |

## 2026-09-22 第三轮：语音导购通道 smartore-voice（ADR-0011）

- 整体移植 jc-voice-shopping 的语音能力为第 6 服务：ASR/TTS omni 实时会话（常驻会话复用/预热）、
  代码状态机编排（7 意图 + 规则矫正 + 澄清缺槽）、pgvector 同步目录检索（HNSW+标量过滤一条 SQL）、
  三级记忆（Redis 滑窗/摘要/PG 动态画像）、Sentinel 熔断限流、合规兜底
- **共存分工**：voice 独占语音 I/O 与语音会话编排；文本导购/RAG/管理面留 smartore-ai 不动
- **交易前端代执行**：order_action 动作帧（requestId 幂等键服务端生成）→ 前端以用户登录态走
  现成加购+下单 Saga → order_result 回传口播；重复确认 SETNX 拦截；trade 零改动
- **防幻觉复核**：推荐出口前经 /internal/product/batch 实时复核，下架/无货剔除、价格以实时为准
- 商品目录同步：goods 新增只读端点 `GET /internal/product/listOnSale`（唯一业务侧代码新增），
  voice 全量同步 + embedding 按 embed_hash 增量重算
- 鉴权：WS query-token 经网关验签（QUERY_TOKEN_PATHS +/voice/ws），服务侧校验 X-Gateway-Token
  常数时间比对；调试端点 /voice/debug/** 叠 in-app 闸门 + 网关 ADMIN 规则
- 移植代价实录：Jackson 3→2 包名回退（12 文件）+ lombok.config 复制 @Qualifier 到构造器 +
  Flyway V1 重写（废 merchant/app_user/order_record/faq_entry/user_profile_static）

### 验证台账（本轮新增）

| 项 | 结果 | 复验方式 |
|---|---|---|
| AgentScope 1.0.11 × Boot 3.5.16 真实 LLM 调用 | ✅ | `mvn test -pl smartore-voice -P eval -Dtest=AgentScopeSmokeTest` |
| 网关 WS 转发 + query-token 鉴权 + 无 token 401 | ✅ | `./scripts/dev.sh check` |
| 目录同步 19/19 在售商品向量化（3.8s） | ✅ | `POST /voice/admin/reindex` → `/voice/admin/catalog-stats` |
| 语音对话全链路（经网关）：推荐真实商品/指代下单/确认派发/重复确认幂等拦截 | ✅ | `/voice/debug/chat` 实测记录见本轮交付说明 |
| 离线单测并入 CI 门禁 | ✅ 132/132 | `cd backend && mvn test` |
| 全栈六服务健康 + 冒烟 | ✅ | `./scripts/dev.sh up && ./scripts/dev.sh check` |

**已知边界（本轮新增，不掩盖）**：原项目封存评测集（意图 200/多轮 60/语音 40/RAG 57）与旧商品目录绑定，
绝对读数需按 Smartore 类目重标后才可跨基线对比（`backend/smartore-voice/eval/README.md` 有诚实声明与重标流程）；
语音 FAQ/售后问答一期未接（知识库在 ai 侧）；麦克风实采为人工验收项（浏览器权限）。

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
