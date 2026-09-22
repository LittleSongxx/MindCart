# ADR-013：请求 DTO 与校验分组约定

日期：2026-09-22 ｜ 状态：已实施

## 为什么

过去 30 个写接口直接把实体当请求体（`@RequestBody Product` / `User` / `ProductBrand`…），
5 个模块都引了 `spring-boot-starter-validation` 却一处没用。后果：
空商品名能入库、负价格能入库、超长字段要么 500 要么被静默截断；
客户端还能往请求体里塞 `status`/`role`/`auditStatus` 这类字段，全靠服务层逐个方法手写白名单去挡。

真正的问题不是"某个字段没校验"，而是**防护靠"每个方法记得写"**：
`UserService` 里已有的字段白名单、`sanitize()` 出参脱敏都是手写出来的，
新增一个接口时没有任何机制强制它也被保护。

## 怎么做

1. 写接口一律收请求 DTO（`com.mindcart.<svc>.dto`），DTO 只声明"允许传什么"，
   `toEntity()` 转实体后调原 service（**service 签名不动**，改动面收敛在 controller）。
   字段名与前端 JSON 完全一致，前端零改动。
2. DTO 不含服务端字段：`createTime`/`updateTime`、join 派生的 `categoryName`，
   以及归属类字段（`userId` 由服务层取当前登录用户，写进 DTO 就是多余的越权面）。
3. 校验分组：`@Validated(Create.class)` / `@Validated(Update.class)`。
4. `GlobalExceptionHandler` 接管 4 类校验异常，统一 **HTTP 400 + 错误码 4002 + 字段级消息**
   （`name: 商品名称不能为空`）。
5. 用户侧 `selectPage` 的 `pageSize` 统一 `@Max(200)`，挡掉 `pageSize=1000000` 式全表拉取。

## 两条必须遵守的约定（踩过才知道）

- **分组约束必须显式声明 `groups`**。`@Validated(Create.class)` 只执行该分组的约束，
  未声明 `groups` 的约束属于 `Default` 分组，会被**静默跳过**——
  写了注解却没生效，比不写更危险。因此本项目所有分组 DTO 都显式写
  `groups = {Create.class, Update.class}`（格式与边界，更新时也要查）
  或 `groups = Create.class`（必填，仅新增）。
- **只对"代码真的按值分支"的字段加枚举约束**（`modelType`、`product.status`、
  `auditStatus`、`role`）。其余看起来像枚举的字段（`invokeType`/`toolType`/`knowledgeType`）
  代码只校验非空，写死枚举反而会拒掉现有数据（种子数据里的 `invokeType=SERVICE` 就是反例）。

## 已知代价

- springdoc 读取校验注解时不区分分组，"仅更新时必填"的字段在文档里会显示为新增也必填。
- 未通过 Bean Validation 的请求在进入控制器方法前就被框架拒绝，
  因此这类 400 **不会**留下审计记录（见 `OperationLogAspect` 注释）——这是有意的。

## 证据

`GlobalExceptionHandlerTest`（3 例）锁住异常映射结果；运行期对 5 类非法入参实测：
空商品名 → `4002 name: 商品名称不能为空；price: 商品价格不能为空`、
负价格 → `4002 price: 商品价格必须大于0`、非法枚举 → `4002 status: 商品状态只能是 ON_SALE 或 OFF_SALE`，
全部 HTTP 400（不是 500）。`ops/verify/browser_e2e.mjs` 6/6 通过，证明前端契约未被破坏。
