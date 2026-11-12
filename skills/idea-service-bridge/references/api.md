# HTTP 与 MCP 调用

默认 Base URL：`http://127.0.0.1:17891`。通过 `-BaseUrl` 或 MCP 适配器的 `IDEA_BRIDGE_URL` 使用其他本机端口。插件 0.2.0+ 不需要 Authorization 请求头。

| 能力 | HTTP | MCP 工具 |
|---|---|---|
| 状态 | GET `/v1/status` | `idea_list_configurations` |
| 启动 | POST `/v1/tasks`，operation=start | `idea_start_configuration` |
| 更新资源 | 同上，operation=update-resources | `idea_update_resources` |
| 更新 class 和资源 | 同上，operation=update-classes-and-resources | `idea_update_classes_and_resources` |
| 重启实例 | 同上，operation=restart | `idea_restart_configuration` |
| 查询任务 | GET `/v1/tasks/{id}` | `idea_get_task` |
| 最新使用说明 | GET `/v1/skill` 或 `/skill.md` | `idea_get_help`；资源 `idea-agent-bridge://usage` |
| 浏览器调试 | GET `/help` 或 `/` | — |
| JSON 接口说明 | GET `/v1/help` | — |

## 请求与返回

修改请求头：`Content-Type: application/json`。使用实际查询的项目和配置，`caller` 填当前 agent/session 标识。

启动（插件 >=0.3.0）：

```json
{"project":"C:/projects/my-app","configuration":"portal","operation":"start","executor":"Debug","caller":"agent-session-a"}
```

对已有实例更新或重启：

```json
{"project":"C:/projects/my-app","configuration":"portal","executionId":123,"operation":"update-resources","caller":"agent-session-a"}
```

123 仅为格式示例，必须换成最新实例 ID。MCP 修改工具中操作由工具名决定，不传 operation；启动工具不要求 executionId，其他修改工具要求正整数 executionId。

提交返回 `id` 和 `status`。保存 id 后查询任务；status 为 FAILED 时读取 error。MCP 适配器把任务失败和 HTTP 错误标为 `isError: true`，409 的结构化结果还包含 `httpStatus`。

## PowerShell 脚本

```powershell
$bridge = "$env:USERPROFILE/.agents/skills/idea-service-bridge/scripts/idea-bridge.ps1"
& $bridge -Operation status
& $bridge -Operation skill

# 确认未运行且插件 >=0.3.0 后，依次启动所需配置。
& $bridge -Operation start -Project C:/projects/my-app -Configuration portal -Caller agent-session-a -Wait
& $bridge -Operation start -Project C:/projects/my-app -Configuration file-agent -Caller agent-session-a -Wait

# 示例 ID 必须替换为实时查询值。
& $bridge -Operation update-resources -Project C:/projects/my-app -Configuration portal -ExecutionId 123 -Caller agent-session-a -Wait
& $bridge -Operation update-classes-and-resources -Project C:/projects/my-app -Configuration portal -ExecutionId 123 -Caller agent-session-a -Wait
& $bridge -Operation restart -Project C:/projects/my-app -Configuration portal -ExecutionId 123 -Caller agent-session-a -Wait
& $bridge -Operation task -TaskId '返回的任务 UUID'
```

脚本只提交用户选择的操作，不会自动重启或重试。默认等待上限 300 秒；超时仍在执行会保留任务 ID 并报错，可继续查询。使用 `-Executor Run` 显式选择普通启动；默认 Debug。可传 `-ConnectionFile` 读取特定 IDEA 实例的 baseUrl，但普通调用无需读取该文件。

## 错误处理

| 返回/现象 | 处理 |
|---|---|
| 400 | 检查必填参数、operation 和 executionId；0.2.0 不支持 start |
| 403 | 检查配置是否在白名单、请求 Host 是否为实际 127.0.0.1 地址；浏览器跨源请求不支持 |
| 409 | 同项目已有修改任务，等待后重新查状态 |
| 503 | 接口暂停等待插件更新，任务空闲后从 Tools 启动接口恢复 |
| FAILED | 读取错误信息；更新失败不自动改为 restart |
| 401 Bearer token required | 检查是否仍运行旧版插件或连错端口；0.2.0+ 应无需 Token |
| 连接失败 | 核对接口是否已开启、实际端口和本机网络；不等同于业务服务停止 |
