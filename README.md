<p align="center">
  <img src="docs/assets/logo.png" alt="MindCart" width="72" />
</p>

<h1 align="center">MindCart</h1>

<p align="center">
  <b>一家会自己挑货、也能把单子做完的店</b><br/>
  逛店、问答、文本导购、语音导购，最后都落到同一套库存和钱包上
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-6db33f?style=flat-square&logo=springboot&logoColor=white" />
  <img src="https://img.shields.io/badge/Spring_Cloud-2025-6db33f?style=flat-square" />
  <img src="https://img.shields.io/badge/Vue-3-42b883?style=flat-square&logo=vuedotjs&logoColor=white" />
  <img src="https://img.shields.io/badge/MySQL-8-4479a1?style=flat-square&logo=mysql&logoColor=white" />
  <img src="https://img.shields.io/badge/PostgreSQL-pgvector-4169e1?style=flat-square&logo=postgresql&logoColor=white" />
  <a href="https://github.com/LittleSongxx/MindCart/actions/workflows/ci.yml"><img src="https://github.com/LittleSongxx/MindCart/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
</p>

<p align="center">
  <img src="docs/assets/screenshots/mindcart-home.png" alt="MindCart 商城首页：分类、搜索和在售商品" width="100%" />
</p>

---

**MindCart 是一套可本地跑通的 AI 电商。** 前面是能逛的商城和后台，中间是导购与知识库，后面是六个 Java 服务。模型可以推荐、可以回答，不能自己改价或跳过库存。下单、扣款、回补都走交易服务里的 Saga。

支付是余额模拟，没有真实资金。语音和文本共用同一套商品、订单和会话历史。

**线上演示：[https://mindcart.cn](https://mindcart.cn)**（部署在阿里云 ECS，HTTPS 由 Let's Encrypt 签发；演示账号见文末「快速开始」的种子账号表）

---

## 逛的时候在发生什么

<table>
<tr>
<td width="33%" valign="top">

**① 逛店**

首页、分类、搜索、详情都读商品服务的现价和库存。封面、卖点和品牌来自目录，不是聊天临时拼的卡片。

</td>
<td width="33%" valign="top">

**② 问这件**

商品页里可以直接问。回答先检索这件商品的知识切片，资料里没有的内容会直接说明，并留下引用。

</td>
<td width="33%" valign="top">

**③ 让它选**

说出预算和用途。导购任务进队列，Agent 多轮查候选、价格和库存，再给出可以加购的清单。

</td>
</tr>
</table>

<p align="center">
  <img src="docs/assets/screenshots/mindcart-guide.png" alt="文本导购：预算 8000 的轻薄办公本，推荐三台在售笔记本" width="100%" />
  <em>本地实跑：需求是「8000 元左右的轻薄办公本，主要用来写代码和看论文」。三台推荐都带现价、库存和匹配说明，可以直接加入购物车。</em>
</p>

<table>
<tr>
<td width="50%" valign="top">
<img src="docs/assets/screenshots/mindcart-product.png" alt="商品详情：价格、库存、加购" /><br/>
<b>商品详情</b> — 现价、库存、相册。旁边可以收藏、加购，或切到语音问这一件。
</td>
<td width="50%" valign="top">
<img src="docs/assets/screenshots/mindcart-qa.png" alt="商品页 AI 问答：依据知识库回答并给出引用" /><br/>
<b>问这件</b> — 问续航和是否适合写代码。回答标了检索来源，下面可以展开引用的切片。
</td>
</tr>
</table>

## 语音是另一条入口，不是另一套交易

同一页里可以切到语音。开口之后的推荐、澄清、下单确认，仍然回调用户、商品和交易服务。成交记录会出现在「我的订单」里。

<p align="center">
  <img src="docs/assets/screenshots/mindcart-voice.png" alt="语音导购面板，以及已成交的语音会话" width="100%" />
  <em>语音面板和两条已结束的会话：各自 3 轮对话，各成交一笔 ¥69，和订单列表里的两笔对得上。</em>
</p>

<p align="center">
  <img src="docs/assets/screenshots/mindcart-order.png" alt="我的订单：待发货与已取消" width="100%" />
  <em>买家订单页。待发货的两笔是语音通道落下的 ¥69 订单，取消单走的是同一套状态机。</em>
</p>

## 后台看的是同一份数据

管理端不是另一套商城。商品、订单、知识库、导购轨迹都在这里。知识要先写成资料，切片之后才能被问答检索到。

<p align="center">
  <img src="docs/assets/screenshots/mindcart-knowledge.png" alt="管理端商品知识库列表" width="100%" />
  <em>商品知识库：卖点说明来自商品详情，状态为启用后才会进入后续切片和检索。</em>
</p>

## 架构

浏览器只打网关。网关做登录、角色和限流，再把用户身份传给后面的服务。服务之间不跨库连表，要别的域的数据就走内部接口。订单事件用 Outbox 投到队列，AI 服务消费后做镜像，不参与扣款。

```mermaid
flowchart LR
  subgraph edge [浏览器]
    Web["Vue 3 商城 + 管理端"]
  end
  Web --> GW["mindcart-gateway"]
  GW --> User["mindcart-user\n账号 / 地址 / 钱包"]
  GW --> Goods["mindcart-goods\n商品 / 库存 / 评价"]
  GW --> Trade["mindcart-trade\n购物车 / 订单"]
  GW --> AI["mindcart-ai\nRAG / 导购 Agent"]
  GW --> Voice["mindcart-voice\n语音会话"]
  Trade --> Goods
  Trade --> User
  AI --> Goods
  AI --> User
  AI --> Trade
  Voice --> Goods
  Voice --> User
  Voice --> Trade
  Trade -.-> MQ["RabbitMQ"]
  MQ -.-> AI
```

```
                      Vue 3  （/api 与语音 WebSocket 都进网关）
                                      │
                              mindcart-gateway
                         JWT · RBAC · 限流 · 信任头
                                      │
     ┌────────────┬────────────┬──────┴──────┬────────────┐
     ▼            ▼            ▼             ▼            ▼
 mindcart-user  goods        trade          ai           voice
 账号/钱包       商品/库存     购物车/订单     RAG/Agent     ASR/TTS
 MySQL          MySQL        MySQL          MySQL        PostgreSQL
                                      │
                              Outbox → RabbitMQ → AI 镜像
```

六个服务的边界、为什么不用 Seata、导购为什么走队列、缓存允许多大陈旧，
写在 `docs/adr/` 和 `docs/architecture.md`。

## 技术栈

| | |
|---|---|
| **后端** | Java 21 · Spring Boot 3.5 · Spring Cloud 2025 · Spring Cloud Alibaba · MyBatis |
| **前端** | Vue 3 · Vite · Element Plus · 商城和管理端同一个应用 |
| **数据** | MySQL 四库（user / goods / trade / ai，Flyway）· PostgreSQL + pgvector（语音） |
| **中间件** | Redis · RabbitMQ · Nacos |
| **AI** | OpenAI 兼容接口 · 混合检索 · 导购工具调用 · 商品页 SSE 问答 |
| **工程化** | 请求 DTO + Bean Validation · Redis 缓存 · springdoc 接口文档 · 操作审计 · JaCoCo |
| **交付** | Docker Compose · GitHub Actions（测试、前端构建、手动部署） |

写接口统一收请求 DTO 并做分组校验（字段边界与越权字段在入口就拦掉），
商品目录挂 Redis 缓存（库存变动精确失效单品键），管理端写操作留痕可查，
接口文档在网关聚合成一页，`mvn test` 出覆盖率报告。这些约定的取舍见 ADR-0012 / ADR-0013。

```
backend/
  mindcart-common/     返回体、异常、用户上下文、内部调用、共享装配（缓存/文档/审计）
  mindcart-gateway/    认证、权限、限流、接口文档聚合
  mindcart-user/       账号、地址、钱包
  mindcart-goods/      商品、库存、评价、售后规则、缓存、审计
  mindcart-trade/      购物车、订单 Saga、Outbox（退避重投）
  mindcart-ai/         模型配置、知识库、导购、问答、审计
  mindcart-voice/      语音会话、意图、目录向量
web/                   Vue 3 前台 + 管理端
deploy/                中间件与应用 Compose
ops/                   Nginx、监控、对账、systemd
docs/adr/              架构决策
```

## 快速开始

依赖本机 Docker、JDK 21、Maven、Node.js 22。

```bash
./scripts/dev.sh bootstrap     # 生成 run/runtime.env（密钥不入库）
./scripts/dev.sh infra-up      # MySQL / Redis / RabbitMQ / Nacos / PostgreSQL
./scripts/dev.sh up            # 构建并启动六个服务
./scripts/dev.sh check         # 登录、路由、权限、语音 WebSocket

cd web && npm install && npm run dev
```

本地入口：前端 `http://localhost:5173`，网关 `http://localhost:9080`。

<p align="center">
  <img src="docs/assets/screenshots/mindcart-login.png" alt="MindCart 登录页" width="100%" />
</p>

也可以把前端构建进容器，和六个服务一起起：

```bash
cd backend && mvn -q package -DskipTests && cd ..
(cd web && npm run build)
docker compose -f deploy/compose.apps.yaml up -d --build
```

容器方式的浏览器入口是 `http://localhost:8081`。两种方式不要同时开，端口会撞。
重建过某个服务后若 `/api` 返回 502，见文末"运维与排查入口"里的 nginx 上游缓存说明。

模型 Key 写在 `run/runtime.env` 的 `MINDCART_CHAT_API_KEY` / `MINDCART_EMBED_API_KEY`。启动时加密进库，管理界面只看到掩码。

本地种子账号（线上演示站 [https://mindcart.cn](https://mindcart.cn) 用的也是这一组，演示数据可能被访客改动，随时可重置）：

| 角色 | 账号 | 密码 |
|---|---|---|
| 管理员 | `admin` | `admin` |
| 买家 | `aaa` | `123` |

## 怎么验证它真的能跑

不只看截图，下面这些都能在本地重跑。`mvn test` 不需要起服务，其余都需要服务栈在跑。

```bash
cd backend && mvn test          # 100+ 用例：单元 + Testcontainers 真 MySQL（+ 真 Redis）集成
                                # 跑完看 backend/mindcart-trade/app/target/site/jacoco/index.html

# 需要先 ./scripts/dev.sh up（或容器栈），网关在 9080
python3 ops/verify/trade_e2e.py     # 交易链路：下单→幂等重放→库存扣减→取消退款→回补
python3 ops/verify/oversell_test.py # 并发下单不超卖（需要一个库存很小的商品）
python3 ops/verify/eval_ai.py       # AI 评测：导购 Pass@1、问答拒答、检索 Recall@3（需模型 Key）

# 浏览器全链路（含 SSE 流式问答）；BASE 指向前端入口，jar 方式 5173 / 容器方式 8081
npm ci --prefix ops/verify            # 浏览器脚本的 playwright-core 依赖（首次运行）
BASE=http://localhost:8081 node ops/verify/browser_e2e.mjs
BASE=http://localhost:8081 node ops/verify/voice_e2e.mjs           # 语音导购对话
BASE=http://localhost:8081 node ops/verify/voice_manager_e2e.mjs   # 管理端语音会话与归因
```

浏览器脚本依赖 playwright 的 chromium，首次运行需要 `npx playwright install chromium`；
已装在非默认位置时用 `CHROME_PATH` 指定。

## 运维与排查入口

服务起来之后，这些是排查时的第一站：

| 入口 | 在哪 | 用途 |
|---|---|---|
| 接口文档 | `http://localhost:9080/doc.html` | 五个服务的接口一页汇总（容器栈经 `/api` 反代不可达，直连网关） |
| 操作审计 | `GET /operLog/selectPage`、`GET /aiOperLog/selectPage` | 谁改了价格/库存/模型配置，含失败尝试 |
| Outbox 水位 | `GET /shopOrder/outboxStats` | PENDING / PUBLISHED / FAILED 计数 |
| Outbox 复位重投 | `POST /shopOrder/replayOutbox` | Rabbit 故障恢复后把 FAILED 事件重新投递（管理员） |
| 覆盖率报告 | `backend/*/target/site/jacoco/index.html` | `mvn test` 后生成 |

> 容器栈下重建某个服务后，若经 `http://localhost:8081/api/...` 返回 502 而直连 9080 正常，
> 是 nginx 缓存了容器的旧 IP：`docker restart mindcart-web` 即可。

## 这个仓库里没有的东西

内部的过程台账（排期、验收摘录、实施状态草稿）不在这里——那些是给自己看的。
架构说明和 `docs/adr/` 里的决策记录保留，因为它们就是这套系统为什么这样拆。

管理端的**操作审计**是另一回事，它是系统功能不是过程文档：谁在什么时候改了价格、
库存、模型配置，都落在各服务自己的 `oper_log` 表里，接口在 `/operLog` 与 `/aiOperLog`。
