# Agent Service Bridge for IntelliJ IDEA

让 AI agent 启动、重启 IDEA 中的运行配置，更新静态资源和热替换 class。提供本机 HTTP API、浏览器调试页和 MCP 适配器。

## 使用场景

使用 agent 编写代码时，服务常常由 IDEA 启动。代码改完后，还需要回到 IDEA 手动重启服务，或点击更新 class 和静态资源，agent 的工作流程因此需要人工介入。

[JetBrains 官方内置 MCP Server 从 IDEA 2025.2 开始提供](https://www.jetbrains.com/help/idea/mcp-server.html)。本插件面向仍在使用旧版 IDEA 的项目，让 agent 直接调用 IDEA 完成这些操作。

**当前支持 IDEA Ultimate 2024.2（242.*），实测 2024.2.6。** 其他版本及 Community 暂未支持，详见 [兼容说明](COMPATIBILITY.md)。

## 安装

1. 从 [Releases](https://github.com/ParadiseWitch/idea-service-bridge/releases/latest) 下载 `idea-agent-bridge-0.4.0.zip`。
2. IDEA → Settings → Plugins → 齿轮 → **Install Plugin from Disk**，选择 ZIP。
3. Tools → Agent Service Bridge → **启动 / 查看连接信息**。
4. 浏览器打开 **http://127.0.0.1:17891/help**，可查看接口和直接调试。

接口仅监听本机，无需 Token。**默认允许所有运行配置，无需设置服务名白名单。** 开启接口不会自动操作业务服务。

插件支持动态安装和卸载；其他插件存在待重启更新等 IDEA 状态时，仍可能要求重启。0.3.1 起已修复通过插件启动后控制台不显示的问题。

![浏览器调试页示例（模拟后端）](docs/images/help-example.png)

## 让 agent 使用

直接告诉 agent：

> 先读取 http://127.0.0.1:17891/v1/skill，查询状态后，按我的要求操作指定的 IDEA 运行配置。

也可安装 [Agent Skill](skills/idea-service-bridge)，或使用 [MCP 适配器](mcp-adapter/README.md)（Node.js 22+、stdio）。它们提供配置查询、启动、资源更新、class 热替换、重启及任务查询能力。

| 接口 | 用途 |
|---|---|
| `GET /v1/status` | 查询项目、运行配置与实例 ID |
| `POST /v1/tasks` | 提交启动、更新或重启任务 |
| `GET /v1/tasks/{id}` | 查询执行结果 |
| `GET /v1/skill` | agent 使用说明 |
| `GET /v1/help`、`GET /help` | JSON 说明、浏览器调试 |

服务名由状态查询获取，`portal` 等名称只是示例。所有修改操作都需要明确指定目标；已有实例需使用最新 `executionId`。HotSwap 需要 Debug 会话，应用服务器更新需要本地 exploded 部署。

## 配置与注意事项

`settings.json` 位于 IDEA 配置目录的 `agent-service-bridge/` 下，仅保留端口配置：

```json
{"port":17891}
```

升级后启动接口时会自动移除旧的 `allowedConfigurations` 字段。修改端口需先停止 Agent 接口，再修改文件并重新开启。

任务成功表示 IDE 操作完成，不代表应用健康；需继续检查部署日志和业务 HTTP。当前没有控制台日志读取接口，可读取服务的落盘日志。多 agent 共用项目时，编译可能包含其他 agent 的改动，操作共享服务前需协调。

## 开发

Windows PowerShell、JDK 21+、完整 IDEA Ultimate 2024.2 安装目录：

```powershell
$env:IDEA_HOME = 'C:/path/to/IDEA'
./build.ps1                 # 编译、测试、打包
./build.ps1 -Task sandbox   # 独立 SDK 集成验证
cd mcp-adapter
npm ci --ignore-scripts
npm test
```

产物位于 `build/`。详见 [测试与限制](TESTING.md)、[更新记录](CHANGELOG.md) 和 [贡献说明](CONTRIBUTING.md)。
