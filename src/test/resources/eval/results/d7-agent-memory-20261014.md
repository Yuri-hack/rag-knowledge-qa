# D7：agent 侧会话记忆接入（2026-10-14）

## 背景：接入前的能力缺口

agent 链路（`/api/chat/agent`）**完全绕过编排层**，因此没有会话记忆：
- `AgentChatController` 只有 `question/maxSteps/mode/debug` 四个参数，无 `sessionId`；
- `AgentLoop` 零引用 `ChatSessionStore` / `QueryRewriteService`；
- `run_eval` 的 agent 分支直接丢弃 `session`/`rewrite` 参数。

后果：7 道多轮题（MT01-07）只能在老管线（`/api/chat/rag/stream` + 编排层）上跑，
agent 臂跑 MT 等于把多轮拆成互不相干的单轮；"多主体题路由到 agent"的建议存在功能漏洞。

## 本次改动

| 文件 | 改动 |
|---|---|
| `AgentChatController` | 新增 `sessionId`/`rewrite` 参数；`prepareQuery()` 载入历史并按需改写成自包含查询；`appendTurn()` 在循环结束后追加会话（记原始问题）。改写与追加逻辑放在 `boundedElastic` 线程内，避免 LLM 调用阻塞 netty 线程 |
| `run_eval.py` | agent/singleshot 分支补传 `sessionId`/`rewrite`（此前被丢弃） |

设计上**与老管线行为对齐**：同一改写入口、同样"只存原始问题"、同样"有历史且 rewrite=true 才改写"。

## 验收（MT01-07，深池 N=20/K=10）

| 配置 | 通过 | 结论 |
|---|---|---|
| 基线 + 记忆（改写开） | 7/7 | 参照 |
| **agent + 记忆（改写开）** | **7/7** | 能力补齐，与基线持平 |
| singleshot + 记忆（改写开） | 7/7 | B 臂同步补齐 |
| **agent + 记忆（改写关）** | **3/7** | 可证伪对照：改写在真实起作用 |

## 结论

1. **接入成功且无回归**：agent 现在能正确处理多轮指代（"那P5呢？"→ 自包含查询），与基线持平。
2. **分数没有提升，符合预期**：基线在 MT 上已是 7/7（天花板效应）。本次改动的价值在于
   **架构一致性**（被路由到 agent 的题不再丢失多轮）、**证据完整性**（MT 首次三臂可比）、
   以及 **D7 前置**（联网 + 记忆组合是 agent 真正有价值的场景）。
3. **可证伪对照保留了改动的可信度**：关掉改写后 3/7，说明 7/7 不是因为"问题本来就简单"。

## 重构后回归验证（2026-10-14，包结构 + ConversationEnvelope 抽取之后）

改动把会话逻辑抽成共享组件 `ConversationEnvelope`，两条链路（编排层 / agent 端点）都改走它。
因为 Spring 上下文装配测试在无密钥时会被跳过，构造器改动必须实测：

| 检查 | 结果 |
|---|---|
| 应用启动（`ConversationEnvelope` Bean 装配） | ✅ 启动成功 |
| 基线多轮（重构后的编排层路径） | ✅ 7/7 |
| agent 多轮（重构后的端点路径） | ✅ 7/7 |
| agent 关改写（可证伪对照） | ✅ 3/7 —— 与重构前**完全相同** |

最后一行是关键：可证伪对照的数字与重构前逐位一致（7/7 vs 3/7），说明这次是**行为等价重构**，
不只是"编译通过"。

