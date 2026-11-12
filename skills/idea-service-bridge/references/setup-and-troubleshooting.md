# 本机配置、MCP 接入与插件维护

以下是本技能创建时的本机约定，不代表每次运行的实时状态。先查接口，再决定操作。

## 本机地址与路径

- 插件工程为本仓库实际克隆目录，下文用 `C:/tools/idea-service-bridge` 举例。
- 目标项目路径、配置名称、运行实例和业务端口均从 `/v1/status` 获取，不从示例推断。
- 默认白名单配置为 `portal` 和 `file-agent`，可在 settings.json 中换成自己的配置名。
- 默认插件 API 为 `http://127.0.0.1:17891`，与业务端口独立。
- Windows IDEA 2024.2 连接信息一般为 `$env:APPDATA/JetBrains/IntelliJIdea2024.2/agent-service-bridge/connection.json`，其中保存实际 baseUrl。
- settings.json 在同目录，包含 port 和 allowedConfigurations。仅在用户要求配置接口时修改；停止 Agent 接口不等于停止业务服务。

## 版本边界

- 插件 0.2.0 支持免 Token、HTTP 帮助和已有实例更新/重启。
- 插件 0.3.0 新增启动未运行配置；默认 Debug，已有实例时拒绝重复启动。
- 当前已生成的插件声明 IDEA `242.*`，实际验证 Ultimate 2024.2.6。不能把 2024.3、2025.1、2025.2 或更早版本说成已经支持。
- 2024.1 及更早还涉及 Java 17 构建；当前构建为 Java 21。Community 不在当前支持范围内，插件依赖 Ultimate 应用服务器功能。
- 扩展兼容应针对目标 SDK 验证后再修改声明，而不是只放宽 until-build。具体状态以工程 COMPATIBILITY.md 和实测报告为准。
- SDK 沙箱验证过启动、定向重启、双 Debug JVM HotSwap 及动态加载卸载；真实业务 Tomcat exploded WAR 资源效果仍应实测，不从沙箱结果推断业务生效。

## 独立 MCP 适配器

适配器位于 `C:/tools/idea-service-bridge/mcp-adapter`，Node 22+，通过 stdio 连接，再调用插件 HTTP API，不另开 MCP HTTP 端口。

支持 mcpServers 格式的客户端可参考：

```json
{
  "mcpServers": {
    "idea-service-bridge": {
      "command": "node",
      "args": ["C:/tools/idea-service-bridge/mcp-adapter/src/index.mjs"],
      "env": {"IDEA_BRIDGE_URL":"http://127.0.0.1:17891"}
    }
  }
}
```

客户端格式不同则按其配置方式使用相同 Node 入口；不要把 17891 的普通 HTTP API 当作 MCP /mcp 地址。`node` 不在客户端 PATH 时使用实际 node.exe 路径。更新适配器后只需重连对应 MCP，不需要因此重启 IDEA。

仅在需要安装或验证适配器时执行：

```powershell
cd C:/tools/idea-service-bridge/mcp-adapter
npm ci --ignore-scripts
npm test
npm run smoke
```

smoke 仅做 MCP 工具发现、状态和帮助读取，不启动、更新或重启业务服务。配置客户端并不意味着本次会话已自动加载新工具，仍需检查当前工具列表。

## 插件安装及“需要 Restart IDE”

在用户要求安装或升级时，从 IDEA **Settings → Plugins → Install Plugin from Disk** 选择安装包，然后 **Tools → Agent Service Bridge → 启动 / 查看连接信息**。只升级插件时等待接口任务空闲，先停 Agent 接口，再安装并重新开启接口。

动态兼容的插件也可能因 IDEA 全局状态无法热更新。已确认的 2024.2 情况：其他插件存在待重启更新时，全局 InstalledPluginsState.isRestartRequired() 会使动态安装检查返回：

```text
Not allowing load/unload without restart because of pending restart operation
```

排查时只读查看当前版本、IDEA idea.log 的 DynamicPlugins 记录及待启动操作队列。安装队列中的版本不等于正在运行的版本。不要为了本插件自动删除 action.script、取消其他插件更新或修改全局重启标记；报告限制，按用户约定的维护窗口处理。

IDEA 2024.2 日志通常在 `$env:LOCALAPPDATA/JetBrains/IntelliJIdea2024.2/log/idea.log`。其他版本通过 Help → Show Log 获取实际目录。

原工程的 `build.ps1 -Task sandbox` 使用隔离 SDK 测试，不应拿业务 portal/file-agent 当成可随意停止的测试夹具。正式业务验证只针对用户授权的目标配置。
