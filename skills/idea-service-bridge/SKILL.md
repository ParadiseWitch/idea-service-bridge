---
name: idea-service-bridge
description: 通过本机 IDEA Agent Service Bridge 的 HTTP 或 MCP 接口查询和启动运行配置、更新静态资源、热替换 class、重启指定实例。当用户让 agent 操作 IDEA 中的 portal、file-agent 等服务，或排查该插件连接和热更新问题时使用。
---

# IDEA 服务控制

使用已经安装的 Agent Service Bridge 操作 IDEA 运行配置。默认 HTTP 地址 `http://127.0.0.1:17891`，插件 0.2.0+ 无需 Token。当前任务只控制服务时，不修改业务代码或 IDEA 全局配置。

## 接入与定位

1. 当前客户端已暴露 `idea_*` MCP 工具时优先使用；工具可能带服务命名前缀。否则直接访问 HTTP，或使用本技能的 `scripts/idea-bridge.ps1`。磁盘上存在 MCP 适配器不代表客户端已经连接。
2. 先只读查询 `/v1/status`；需要实时使用说明时读取 `/v1/skill`。记录后端 `version`、项目绝对路径、准确的配置名称、`allowedConfigurations`、实例 `executionId` 和执行模式。不要从旧对话复用实例 ID。
3. 用项目和配置名唯一定位目标。用户口中的 `fileagent` / `file_agent` 在某些项目实际叫 `file-agent`，以查询结果为准。不能按 IDEA 当前选中的运行窗口推断目标。
4. 接口不通时，检查用户给出的实际地址或当前 IDEA 的连接信息；提示在 **Tools → Agent Service Bridge → 启动 / 查看连接信息** 开启接口。启动 Agent 接口不会启动业务服务。

HTTP 与 MCP 的参数、操作映射及请求示例见 [references/api.md](references/api.md)。

## 选择操作

依据用户当前请求及已授权范围执行，不把更新失败扩展成重启：

- **启动服务**：配置未运行时用 `start`，默认 Debug，用户指定时用 Run。要求后端插件 0.3.0+，`executionId` 省略或为 0。若已有实例，报告现状或执行用户明确要求的其他操作。
- **静态资源变更**：用 `update-resources`，指定最新实例 ID。
- **已有 Java 方法体变更**：目标为 Debug 且支持 HotSwap 时用 `update-classes-and-resources`。要求本地应用服务器和 exploded 部署；Run 模式不能用它热替换 class。
- **明确重启**：用 `restart`，指定最新实例 ID。保留 Run/Debug 模式；重启后重新查询实例 ID。

## 异步结果与共享服务

- 修改请求返回任务 ID。HTTP 202 或 `RUNNING` 只表示已接收，继续查询 `/v1/tasks/{id}` / `idea_get_task`，直到 `SUCCEEDED` 或 `FAILED`。
- 同项目修改操作按顺序执行。409 时等待当前任务完成并重新查状态；不要反复提交相同更新、启动或重启请求。
- 客户端等待超时不表示 IDEA 任务取消。保留任务 ID，继续查询。提交阶段网络错误且不知道是否接收时，先查运行状态，不盲目重发修改请求。
- 索引中、正在编译、存在未保存编辑内容、实例过期或不唯一时，报告具体阻碍。编译和 HotSwap 失败读取 `error`，不会自动重启。
- 多 agent 共用服务时，沿用已经约定的维护窗口和授权。共享源码的编译可能包含其他 agent 已写入磁盘的修改，插件的任务锁不能隔离这些修改，也不能拦截用户手动操作。
- 不为本任务停止其他配置、关闭 IDEA、清空其他插件更新队列，或强行绕过插件待重启状态。

## 验证完成

任务完成后重新查询目标实例。启动和重启时确认进程状态；Tomcat 可进一步检查 `connected=true`、`starting=false`，并访问用户或项目指定的健康地址。

`processStarted=true` 不代表业务就绪；端口可连接、HTTP 404 也不能证明业务健康。资源结果中的 `resourceBuildCompleted`、`resourcePathsWritten`、`deploymentNotificationSubmitted` 只描述 IDE 构建和通知。只有实际访问业务页面或接口验证后，才报告对应业务效果。

最终报告目标配置、执行动作、任务结果及实际做过的验证。没有健康地址时，明确“进程已启动，业务健康未验证”。

## 本机配置与维护

日常调用脚本不依赖原插件工程存在。安装包位置、MCP 配置、当前版本边界，以及“需要重启 IDEA”的排查见 [references/setup-and-troubleshooting.md](references/setup-and-troubleshooting.md)，仅在接入或维护时读取。
