# 参与开发

当前插件针对 IDEA Ultimate 2024.2 SDK，构建方法见 README。

提交改动前运行 Java 测试和相关 SDK 沙箱测试；MCP 适配器改动运行 `npm test`。不要把正在使用的业务服务当作可随意重启的测试环境。

扩展 IDEA 兼容范围时，请提供目标 SDK 的编译/链接验证、动态加载、启动/重启、HotSwap 和应用服务器更新测试结果。只修改 since-build/until-build 不能证明兼容。

Issue 请附 IDEA 完整版本、插件版本、使用 HTTP 还是 MCP、最小复现、任务错误和必要的已脱敏日志。不要提交 Token、连接文件、完整个人配置、SDK 或 node_modules。
