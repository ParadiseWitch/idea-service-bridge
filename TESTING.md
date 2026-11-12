# 测试记录与验证边界

## 验证环境

Windows、IntelliJ IDEA Ultimate 2024.2.6 (`IU-242.26775.15`)、JetBrains Runtime 21.0.5。插件按 Java 21 编译，独立 MCP 适配器使用 Node 24 验证，要求 Node 22+。

## 自动化验证

运行 `./build.ps1 -Task sandbox`，包含编译、13 项 Java 单元测试、安装 ZIP 生成和独立 SDK 集成测试。

- HTTP 免 Token、浏览器同源 POST、跨源拒绝、参数校验、任意运行配置名、帮助页和 Markdown 使用说明。
- 异步任务互斥、错误传播、无自动重试/重启、路径归一化、关闭端口和查询等待线程。
- 旧版 token/allowedConfigurations 设置迁移移除，端口保留；新版本状态/帮助不再返回白名单。
- HTTP 启动两个使用自定义配置名的真实 Debug JVM；重复启动拒绝，原进程保留。
- 启动和重启后检查执行环境未设置 headless、存在控制台且内容未隐藏。SDK 单元测试模式的 RunContentManager 跳过工具窗口挂接，实际 Services / Debug 窗口显示需完整桌面验证。
- 通过 HTTP 重启一个测试配置，另一个测试配置的 PID 不变，Debug 模式保留。
- 旧 executionId 和不支持的更新请求失败，两个进程继续运行。
- 修改方法体并编译，对目标 Debug JVM HotSwap：目标返回 v2、PID 不变；另一个仍返回 v1、PID 不变。重复无变更加载的回调也完成。
- 应用启动事件驱动新服务自动监听 HTTP；端口冲突不抛出到启动流程，释放端口后可手动恢复。SDK 通过消息总线触发生命周期事件，不等于已重启用户完整桌面 IDEA。
- 两轮动态安装后自动开启接口、手动停止后重复事件不重开、动态卸载和重新加载。检查 Action 注销、类加载器解除、端口可重新绑定及 HTTP 工作线程退出。
- 在沙箱中模拟全局待重启标记，确认 IDEA 会拒绝动态安装；恢复标记后再验证正常加载。
- 检查安装 ZIP 仅含生产插件 JAR。

MCP：在 `mcp-adapter` 运行 `npm test`，10 项测试验证工具发现、参数 Schema、默认 Debug、旧插件版本预检、冲突/网络失败不重试、失败不回退重启、使用说明资源、真实 stdio 子进程和正常退出。

`npm run smoke` 只读连接正在运行的插件，查询工具、配置和帮助资源，不启动或更新业务服务。

## 浏览器验证

Edge 在独立模拟后端验证 `/help`：查询状态、选择实例并自动填写 JSON、提交任务和按 ID 查询结果。页面示例见 `docs/images/help-example.png`，测试后端没有 IDEA 控制能力。

## 产物

本地原始结果保存在 `build/reports/unit-tests.txt`、`sandbox.txt` 和 `mcp-smoke.json`。这些运行时报告不随源码上传；GitHub Release 提供安装包及 SHA-256 校验文件。

## 当前限制

- 0.3.1 已在真实 IDEA 中通过 HTTP 启动两个 Tomcat Debug 配置，任务成功且服务器已连接。落盘日志随后发现应用 Context 因筛选器初始化异常部署失败，因此该次验证仅证明进程启动，不代表业务健康；控制台实际桌面显示尚未核验。
- 真实业务 Tomcat exploded WAR 资源编译、部署通知和业务页面效果尚未完成端到端验证，不能仅凭上述 JVM 测试推断业务生效。
- 当前范围仅为 242.*，实测 2024.2.6 Ultimate。尚未覆盖其他 IDEA 分支和 Community。
- SDK 沙箱测试框架不等于用户完整 IDE 的全部状态。其他插件等待重启、类加载器引用等因素仍可能阻止热更新。
- 测试配置、缓存和日志隔离在 `.sandbox/`，临时端口避免占用用户实际接口；不以正在使用的业务服务作为可随意停止的夹具。
