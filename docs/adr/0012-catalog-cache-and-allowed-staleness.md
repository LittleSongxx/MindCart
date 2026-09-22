# ADR-012：商品目录缓存与"允许的陈旧"

日期：2026-09-22 ｜ 状态：已实施

## 为什么

user / goods / trade 三个核心服务此前完全没有 Redis：首页 `/product/selectAll` 是无分页、
两表 left join、带 `like` 的查询，分类与品牌字典每进一次首页就查一遍库。
同时商品列表行里带着库存展示列，而这一列**每次下单都会变**——这正是"要不要缓存、
缓存多久"必须想清楚的地方，也是国内面试必问的缓存一致性题在本项目的落点。

## 怎么做

分区 TTL（`smartore.cache.ttls`）：`product` 60s，`detail` / `param` / `category` / `brand` 30min，
全部叠加 ±20% 抖动（`JitterTtlRedisCacheManager`，摊开失效时刻防雪崩）。
三条明确的取舍：

1. **商品缓存按 id 建键时，库存变动只精确失效 `id=N` 单键**（`StockSagaService` 在事务提交后失效，
   避免并发读把旧值灌回）。
   - 「提交后失效」而不是「事务内失效」：事务未提交时清缓存，并发读会把旧值重新写进去，等于没清。
   - 为什么按 id 精确失效而不是清整个区：下单前的库存预检走的就是按 id 读，
     精确失效让**预检路径始终新鲜**；列表页（键为筛选条件组合）保留 TTL 内的展示延迟，
     不会因为每一笔成交就被清空——若按库存变动清整个区，缓存基本等于不存在。
2. **浏览页允许 ≤60s 的库存陈旧，不会超卖**：下单的权威校验是 stock 表上的条件扣减
   （`deductAvailable` 的 `available >= quantity`），与展示列无关。
   最坏情况是预检放行后权威扣减失败，用户仍看到明确的"库存不足"，只是白走一次补偿。
3. **带关键词（name/productNo）的查询不进缓存**：搜索词取值空间无界，
   缓存它既低命中又会在 Redis 里堆大量一次性键。
4. **分页查询一律直连 mapper，绝不套 `@Cacheable`**：PageHelper 开启后返回的只是"当页切片"，
   套缓存会把第一个调用者的那一页发给所有人。`InternalGoodsController` 里两处分页读都写了这条注释。

## 附带结论：库存展示列的双源已消解

`ProductService.deleteById` 原先物理删商品却不删 `stock` 行、且无事务，会留下孤儿库存行；
商品 id 复用时该行会被 `upsert` 命中而"继承"旧库存。现已同事务清理。

## 证据

- `GoodsCacheIntegrationTest`（真实 MySQL + 真实 Redis，4 例）：SpEL 键能解析、二次查询命中缓存
  （直接改库后读到的仍是旧值，证明确实没回源）、写操作清除条件键、**库存变动精确失效 id=N**、
  关键词查询不产生缓存条目、分类字典写后失效。
- `GoodsCacheKeysTest`（5 例）：锁住键格式，防止"写缓存"与"主动失效"两处手写漂移——
  漂移后失效会静默失败（看起来清了，实际清的是另一个键）。
- 运行期：`redis-cli --scan --pattern 'goods:cache:*'` 可见三类键，
  TTL 分别落在 51s（60s−15%）与 1932s（1800s+7%），抖动生效。
- `ops/verify/trade_e2e.py` 的"库存原子扣减"在下单后立刻按 id 读并断言扣减正确——
  这条断言同时是"精确失效真的生效"的运行期证据。

## 已知代价

- 列表页库存最多陈旧 60s（已在上面论证其安全性）。
- 审计表与缓存均按服务分库/分区，查询审计需分别访问 `/operLog` 与 `/aiOperLog`。
- 缓存是可选加速层（`smartore.cache.enabled` 默认 false）：`StockSagaService` 用
  `ObjectProvider<CacheManager>` 可选获取，关掉缓存服务照常启动。
