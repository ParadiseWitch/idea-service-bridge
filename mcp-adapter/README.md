# IDEA MCP 适配器

独立 Node 进程，把已有 IDEA HTTP API 暴露为标准 MCP 工具。无需 Token，不改 IDEA 插件，不占用额外监听端口。浏览器仍使用 `http://127.0.0.1:17891/help`。

```text
MCP 客户端 → stdio 适配器 → localhost HTTP → IDEA 插件
```

## 安装与配置

需要 Node.js 22+，工程当前环境为 Node 24。

```powershell
cd C:/tools/idea-service-bridge/mcp-adapter
npm ci --ignore-scripts
npm test
```

在支持 `mcpServers` 格式的客户端配置中加入以下内容；不同客户端配置入口可能不同：

```json
{
  "mcpServers": {
    "idea-service-bridge": {
      "command": "node",
      "args": ["C:/tools/idea-service-bridge/mcp-adapter/src/index.mjs"],
      "env": { "IDEA_BRIDGE_URL": "http://127.0.0.1:17891" }
    }
  }
}
```

也可参考 [mcp-config.example.json](mcp-config.example.json)。若客户端找不到 `node`，将 command 改成实际 `node.exe` 的绝对路径。不要在客户端 command 中用 `npm start`，直接运行 Node 入口即可，不依赖客户端工作目录。

客户端连接后应发现 7 个工具及一个使用说明资源：

| 工具 | 用途 |
|---|---|
| `idea_list_configurations` | 查询项目、配置、实例 ID、模式及端口 |
| `idea_start_configuration` | 启动未运行配置，默认 Debug，可选 Run |
| `idea_update_resources` | 更新目标实例的静态资源 |
| `idea_update_classes_and_resources` | 对目标 Debug 实例编译并 HotSwap |
| `idea_restart_configuration` | 明确重启指定实例，保留 Run/Debug |
| `idea_get_task` | 按任务 UUID 查询结果 |
| `idea_get_help` | 读取当前插件的使用说明 |

资源 URI：`idea-agent-bridge://usage`。它与帮助工具都从运行中的插件读取 `/v1/skill`。

## 调用流程

1. 先调用 `idea_list_configurations`，确认项目、准确的配置名称和实例 ID。
2. 未运行时使用 `idea_start_configuration`，例如：

   ```json
   {"project":"C:/projects/my-app","configuration":"portal","caller":"agent-session-a","executor":"Debug"}
   ```

3. 已运行时更新或重启必须传最新 `executionId`。修改工具立即返回任务 ID；HTTP 202 和任务 RUNNING 不代表完成。
4. 使用 `idea_get_task` 轮询至 SUCCEEDED 或 FAILED。失败返回 MCP `isError: true`，保留插件的错误信息。
5. 进程启动成功后另做业务 HTTP 健康检查。资源构建/通知提交不等于页面已生效。

同项目并发仍由插件控制。遇到 409、超时或编译失败，适配器不会自动重复修改请求，也不会自动重启。每个 MCP 客户端可启动自己的适配器进程，所有进程共享同一个 IDEA 后端的任务锁。

## 版本与维护

- 独立适配器版本为 1.0.0，与 IDEA 插件版本分开。
- 查询、帮助和已有实例更新可配合插件 0.2.0；启动工具需要插件 **0.3.0+**。旧版会返回明确提示，不发送无效启动请求。
- 关闭 MCP 客户端只关闭适配器，不停止 IDEA 或其运行服务。
- 修改适配器代码后重启对应 MCP 连接即可；涉及 IDEA SDK 的插件修改仍需要更新插件。
- IDEA 版本兼容范围由插件决定，目前插件声明 `242.*`，实际验证 IDEA Ultimate 2024.2.6。
- 当前使用 stdio，不提供远程 MCP HTTP 监听地址；`17891` 是原有插件 HTTP API。

## 测试

`npm test` 使用官方 SDK 的内存 transport 和真实 stdio 子进程验证协议，不操作业务服务。

`npm run smoke` 使用真实 stdio 子进程对当前 IDEA 执行工具发现、状态查询和帮助资源读取，**不会启动、更新或重启服务**。报告在 `../build/reports/mcp-smoke.json`。

协议实现采用 [MCP 官方 TypeScript SDK](https://ts.sdk.modelcontextprotocol.io/server)，依赖版本由 package-lock.json 锁定。
