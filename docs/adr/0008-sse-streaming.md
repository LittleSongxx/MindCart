# ADR-008：QA 流式输出（SSE）

日期：2026-09-16 ｜ 状态：已实施并实测

## 为什么

「从用户输入到前端流式输出的完整链路」是 H3 题族（5 来源）；LLM 生成 3-10s 的空白等待
是 AI 应用的体验硬伤。**导购任务保持轮询不流式**——异步多轮任务用 SSE 反而复杂
（FS-01 选型题的标准答案：连接不是任务状态，长任务用持久化 run+轮询，短回答用 SSE）。

## 怎么做

- `GET /shoppingQa/askStream`（text/event-stream）：delta=文本增量 / complete=完整 QA（含 conversationId）/ error
- AiChatService.chatStream：Spring AI ChatClient.stream()，增量回调 + 计时打点（kind=chat_stream）
- 客户端断开：停止转发增量但后台继续完成并落库（「连接不是任务状态」的直接体现）
- 结构化/工具链路仍走非流式——半个 JSON 无法做完整校验（面经 FS-03 的标准边界）
- 前端 EventSource 渐进渲染，会话延续用 complete 事件回传的 conversationId

实测：delta 分片到达（"这款商品的保修"/"政策是按品牌官方"/…），complete 落库含 roundNo。
