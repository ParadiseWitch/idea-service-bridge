# 本机配置、MCP 接入与插件维护

以下是本技能创建时的本机约定，不代表每次运行的实时状态。先查接口，再决定操作。

## 本机地址与路径

- 插件工程为本仓库实际克隆目录，下文用 `C:/tools/idea-service-bridge` 举例。
- 目标项目路径、配置名称、运行实例和业务端口均从 `/v1/status` 获取，不从示例推断。
- 0.4.0 起默认允许所有运行配置，服务名从状态查询获取；`portal` 和 `file-agent` 仅为示例。
- 默认插件 API 为 `http://127.0.0.1:17891`，与业务端口独立。
- Windows IDEA 2024.2 连接信息一般为 `$env:APPDATA/JetBrains/IntelliJIdea2024.2/agent-service-bridge/connection.json`，其中保存实际 baseUrl。
- settings.json 在同目录，仅包含 port（0.4.0+）。仅在用户要求配置接口时修改；停止 Agent 接口不等于停止业务服务。

## 版本边界

- 插件 0.5.0 起在 IDEA 启动和插件热加载时自动开启 HTTP 接口。手动停止接口在本次插件会话内生效，下次 IDEA 启动或插件重新加载时再次自动开启。自动开启失败只记录 idea.log，Tools 菜单可手动恢复。

- 插件 0.2.0 支持免 Token、HTTP 帮助和已有实例更新/重启。
- 插件 0.4.0 移除白名单；启动接口时自动移除旧 settings.json 中的 allowedConfigurations 字段并保留端口。
- 插件 0.3.0 新增启动未运行配置；默认 Debug，已有实例时拒绝重复启动。
- 插件 0.3.1 移除启动/重启时错误的 headless 标记，恢复正常控制台显示流程。不会自动补挂 0.3.0 已启动实例的控制台，不能为恢复日志未经授权重启服务。
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

在用户要求安装或升级时，从 IDEA **Settings → Plugins → Install Plugin from Disk** 选择安装包；0.5.0+ 会自动开启接口，可通过 **Tools → Agent Service Bridge → 启动 / 查看连接信息** 查看实际地址或在失败后手动重试。只升级插件时等待接口任务空闲，先停 Agent 接口再安装；0.5.0+ 热加载成功后自动开启，旧版本需手动开启。

动态兼容的插件也可能因 IDEA 全局状态无法热更新。已确认的 2024.2 情况：其他插件存在待重启更新时，全局 InstalledPluginsState.isRestartRequired() 会使动态安装检查返回：

```text
Not allowing load/unload without restart because of pending restart operation
```

排查时只读查看当前版本、IDEA idea.log 的 DynamicPlugins 记录及待启动操作队列。安装队列中的版本不等于正在运行的版本。不要为了本插件自动删除 action.script、取消其他插件更新或修改全局重启标记；报告限制，按用户约定的维护窗口处理。

IDEA 2024.2 日志通常在 `$env:LOCALAPPDATA/JetBrains/IntelliJIdea2024.2/log/idea.log`。其他版本通过 Help → Show Log 获取实际目录。

原工程的 `build.ps1 -Task sandbox` 使用隔离 SDK 测试，不应拿业务 portal/file-agent 当成可随意停止的测试夹具。正式业务验证只针对用户授权的目标配置。

## 读取输出日志与核验部署

0.3.1 的 HTTP/MCP 只提供状态、任务和帮助，没有 console/logs 接口。IDEA 控制台显示修复与远程读取控制台是两项能力，不能混为一谈。可在有文件权限时只读查看服务的落盘日志；它可能不包含全部 stdout/stderr 或业务日志。

1. 先查询当前项目、配置、executionId 和业务端口。通过监听端口对应 PID、进程启动时间与命令行 `-Dcatalina.base` 对应当前实例，不能只根据某个旧日志目录名推断。
2. Windows IDEA 的 Tomcat 实例目录通常在 `$env:LOCALAPPDATA/JetBrains/IntelliJIdea2024.2/tomcat/<实例目录>`。具体以当前进程或运行配置为准；`conf/server.xml` 中 Connector 端口可辅助核对，但可能存在多个旧目录使用相同端口。
3. 查看该实例 `logs/catalina.<日期>.log`、`localhost.<日期>.log` 的末尾，核对日志时间晚于本次启动。只读取得所需末尾行和异常上下文；分享时省略凭据、会话值和无关个人信息。
4. 即使任务 SUCCEEDED、Tomcat connected=true、starting=false，仍需检查 `SEVERE`、筛选器启动失败、Context 启动失败等部署错误。localhost 日志通常包含更具体的异常。不要把根路径 404 或端口监听当作应用健康证明，也不要因读取到错误就自动修改业务代码或重启。

没有落盘日志且无法控制桌面时，明确当前不能获取 IDEA 控制台全文；不要编造日志或声称日志已验证。
