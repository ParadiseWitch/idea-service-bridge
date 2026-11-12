# 更新记录

## v0.3.0

首次 GitHub 发布，包含 IDEA 插件 0.3.0、独立 MCP 适配器 1.0.0 和可复用 agent skill。

- 启动未运行配置，默认 Debug，可选 Run；拒绝已有实例和正在启动的 Run/Debug 重复启动。
- 精确按项目、配置、实例 ID 更新静态资源、热替换 class 或重启。
- 免 Token 本机 HTTP、浏览器调试页、JSON help 和 Markdown skill 接口。
- 异步任务、错误传播、项目级互斥；失败不自动重启或重复提交修改请求。
- 独立 stdio MCP 适配器提供 7 个工具和使用说明资源。
- SDK 集成验证动态加载、卸载、端口/线程清理及共享实例隔离。

兼容声明：IDEA Ultimate 2024.2 / 242.*，实测 2024.2.6。尚未验证其他 IDEA 分支或真实业务 Tomcat 资源部署端到端效果。
