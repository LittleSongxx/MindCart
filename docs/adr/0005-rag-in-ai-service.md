# ADR-005：RAG 资产归 AI 服务 + 模型密钥治理

日期：2026-09-15 ｜ 状态：已实施

## 决策

**知识库（knowledge/chunk/embedding）归 ai 服务**，与模型配置、提示词同域（见 ADR-001 依赖方向推演）。
检索 = ai 内部余弦排序（数据量级内全量内存计算；向量存 MySQL 文本列，扩展时换向量索引/专库）。

**模型密钥治理**（相对单体版的三个升级）：
1. AES-GCM 加密落库（`enc:` 前缀 + 随机 IV），密钥只在 runtime.env；
2. 任何出参脱敏 `sk-****末4位`；前端回传掩码值视为"未修改"，保留库内密文；
3. 冷启动从环境变量升级配置（ModelConfigInitializer）：仓库与 SQL 零真实密钥，
   占位密钥自动识别并原地升级。

**模型客户端工厂**：SpringAiModelFactory 按配置内容寻址缓存（改配置免重启），
聊天走 Spring AI ChatClient，Function Calling 走 OpenAI 兼容原始 HTTP（tools 语义完全可控），
并排除 Spring AI 静态自动配置（无静态 key 时会启动失败）。

**LLM 观测**：所有模型调用经 Micrometer 打点（kind/model/outcome + 耗时直方图），
Grafana 出调用画像； embedding 未配置向量模型时回退本地 64 维哈希（无 Key 也能演示 RAG 流程）。
