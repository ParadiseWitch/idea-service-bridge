# IDEA 兼容范围

当前插件 0.3.0 的声明为 `since-build="242" until-build="242.*"`，使用 Java 21 字节码。实际验证环境是 **IntelliJ IDEA Ultimate 2024.2.6，IU-242.26775.15**。

| IDEA | 平台分支 | 平台 Java | 当前状态 |
|---|---|---|---|
| 2024.1 | 241 | 17 | 不支持当前构建；需要 Java 17 构建及 SDK 适配 |
| 2024.2 | 242 | 21 | 当前声明范围；实测 2024.2.6 Ultimate |
| 2024.3 | 243 | 21 | 尚未适配验证，当前包不允许安装 |
| 2025.1 | 251 | 21 | 尚未适配验证，当前包不允许安装 |
| 2025.2 | 252 | 21 | 尚未适配验证，当前包不允许安装 |

更早版本需要先明确最低版本，没有“所有 2025.2 以下版本”这一无限兼容承诺。当前插件强依赖 Jakarta EE 应用服务器功能，因此 Community 不在支持范围内。

独立 MCP 适配器只调用稳定的本机 HTTP API，不直接依赖 IDEA SDK。查询和已有实例操作需要插件 0.2.0+；启动需要插件 0.3.0+。适配器运行时为 Node 22+，它不会自动扩大插件的 IDEA 版本范围。

扩展兼容时，应在目标分支执行 SDK 链接/Plugin Verifier 检查、动态安装卸载、配置启动和定向重启、双 Debug 会话 HotSwap、应用服务器资源更新测试，再扩展插件描述范围；若内部 API 发生变化，分别适配或构建不同安装包。2024.1 及更早还需替换 Java 21 API 并提供对应字节码。

JetBrains 官方 MCP 从 2025.2 开始内置；本项目可以作为更早版本的替代接入方式，但只有完成上述验证后才能声明对应版本支持。

依据：[官方平台分支与 Java 版本表](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)、[官方 MCP 文档](https://www.jetbrains.com/help/idea/mcp-server.html)。
