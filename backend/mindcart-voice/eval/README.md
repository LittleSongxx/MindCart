# mindcart-voice 评测体系（移植自 jc-voice-shopping，适配 MindCart 底座）

> 原则沿用原版：能用业界标准指标就用标准的（Accuracy/混淆矩阵、P/R/F1、Recall@K/MRR/nDCG、CER、P50/P95）；
> 小样本对照一律给配对 bootstrap 95% CI，**CI 跨 0 不得写"提升/胜出"**。
> `eval/reports/*.md` 是评测器每次运行覆盖的原始产物，不入库。

## 跑法

```bash
# 1. 起全栈（voice 由 dev.sh 以 debug.enabled=true 启动）
./scripts/dev.sh infra-up && ./scripts/dev.sh up

# 2. 首次先同步商品目录与向量
TOKEN=$(grep '^MINDCART_INTERNAL_TOKEN=' run/runtime.env | cut -d= -f2)
curl -X POST http://127.0.0.1:9105/voice/admin/reindex -H "X-Internal-Token: $TOKEN"

# 3. 跑评测（默认不进 CI 门禁；需要 LLM/ASR key）
set -a && . ./run/runtime.env && set +a
cd backend && mvn test -pl mindcart-voice -P eval
```

## 与 jc 原版的差异（移植口径）

- **鉴权**：HTTP 调试端点直连 9105 + `X-Internal-Token`；WS 直连 9105 `/voice/ws` + `X-Gateway-Token`/`X-User-Id` 头（等效网关注入）。要打真实网关链路：`-Deval.base.url=http://localhost:9080`。
- **价格基准**：`EvalSupport.prices()` 从运行中服务 `/voice/debug/catalog-prices` 实时加载，替代原版硬编码的 30 件种子价目表。
- **报告输出**：`eval/reports/`（不入库）。
- **未移植**：`EvalVoiceLoopTest`（依赖原项目 asr-cross uv 工程布局）；语音回环由 EvalAsrSweepTest + E2E 覆盖。

## ⚠️ 数据集校准状态（诚实声明）

| 数据集 | 状态 | 说明 |
|---|---|---|
| `independent-intent-200.jsonl` | **待校准** | 封存于原项目目录（跑鞋/手表/耳机/口红），MindCart 类目为手机通讯/笔记本电脑/耳机音箱等 13 类；原样跑会大量落在 OUT_OF_SCOPE/CLARIFY_NEEDED，读数不代表当前系统 |
| `independent-intent-multiturn-60.prod.jsonl` | **待校准** | 同上 |
| `v2-speech-40.jsonl` | **待校准** | 话术价格忠实度断言的品类锚点按原目录区间映射；价格集已改实时加载，品类锚点失效 |
| `rag-golden.jsonl` | **待校准** | 检索黄金集的商品 id 是原目录 id，需按 MindCart 商品重标 |

结论：当前 `-P eval` 跑出的意图/检索/话术指标**只能用于回归监控（口径一致地看漂移），不能与 jc 原报告的数字直接对比**。跨基线对比需要先按 MindCart 目录重标数据集（沿用 `audit-prompt-leakage.py` / `check-unseen-batch.py` 治理流程出新封存集）。

## 直接有效（与目录无关）

- `EvalE2eLatencyTest` / `EvalConcurrencyTest`：WS 链路延迟/并发
- `EvalAsrSweepTest`：ASR 参数网格
- 离线单测 132 个（`mvn test`，默认 CI 门禁）：重排/澄清/合规/记忆/语音工具类全绿
