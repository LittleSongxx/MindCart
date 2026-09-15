# ADR-001：服务边界与契约分层

日期：2026-09-15 ｜ 状态：已实施

## 背景

单体版（见 git 首个提交）30 个 Service 共库共表，任何模块都能 JOIN 任何表，
库存扣减、模型调用、文件上传、订单全部耦在一个进程里。

## 决策

按业务域拆 5 服务，每服务独立数据库（Flyway 自管 schema+种子）：

| 服务 | 域 | 库 |
|---|---|---|
| smartore-user | 账号、地址、钱包（余额+流水）、文件 | smartore_user |
| smartore-goods | 商品、分类、品牌、参数、详情、收藏、评价、售后规则、**库存权威表** | smartore_goods |
| smartore-trade | 购物车、订单、支付编排、幂等、事件账本 | smartore_trade |
| smartore-ai | 模型配置、提示词、知识库 RAG、导购 Agent、问答、分析报告、事件镜像 | smartore_ai |

**契约模块**（`*-api`）：Feign 接口 + VO + 枚举。消费方只依赖 api，绝不依赖 app 实现
（Smartlect 同款范式）。ai 是叶子服务（无人消费）→ 不建 api 模块，避免空转。

## 关键推演（不是拍脑袋）

- **知识库/RAG 归 ai 而非 goods**：若归 goods，则 goods 向量生成需调 ai 的模型能力、
  ai 问答需调 goods 的检索能力 → 循环依赖。归 ai 后依赖方向严格单向：
  `ai → {user, goods, trade}`、`trade → {user, goods}`。
- **库存权威表独立于商品表**：product.stock_quantity 降级为展示列（同事务同步），
  扣减走 stock.available 条件 UPDATE + stock_change_record 流水（幂等键 biz_no+product_id+type）。
- **跨库 JOIN 一律消灭**：8 处展示性 JOIN（用户名/商品名）改为 `NameFillService`
  服务层一次批量 Feign 回填；评价"是否已买"校验经 trade 内部接口。
- **钱包流水带 uk(business_no, type)**：它是钱包 Saga 幂等的物理保障，也是对账数据源。

## 后果

- 加：一次跨域查询 = 一次 Feign；发布要起 5 个进程（dev.sh/systemd 兜住）。
- 得：故障隔离（ai 重启不碰交易）、按域演进、每域可独立扩缩、契约即文档。
