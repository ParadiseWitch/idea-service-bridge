package dev.agentbridge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared interface documentation for the browser, IDE dialog and agents. */
public final class BridgeHelp {
    public static final String VERSION="0.4.0";
    private BridgeHelp() { }
    public static Map<String,Object> api(String base) {
        return Map.of("name","Agent Service Bridge","version",VERSION,"baseUrl",base,
            "authenticationRequired",false,"skillEndpoint","/v1/skill","browserHelp",base+"/help",
            "endpoints",List.of(
                Map.of("method","GET","path","/v1/status","description","查询项目、运行配置和 executionId"),
                Map.of("method","POST","path","/v1/tasks","description","提交启动、更新或显式重启任务"),
                Map.of("method","GET","path","/v1/tasks/{id}","description","查询异步任务结果"),
                Map.of("method","GET","path","/v1/help","description","JSON 接口说明"),
                Map.of("method","GET","path","/v1/skill","description","供 agent 读取的 Markdown 使用说明")),
            "taskExample",Map.of("project","C:/projects/my-app","configuration","portal","executionId",123,
                "operation","update-resources","caller","agent-session-name"));
    }
    public static String skill(String base) {
        return """
            ---
            name: idea-agent-service-bridge
            description: 使用本机 IDEA 更新运行配置的静态资源、热替换 class 或明确重启指定实例。
            ---
            # IDEA Agent Service Bridge

            服务地址：%s。无需 Token 或 Authorization 请求头。支持启动未运行配置，以及操作已运行实例。
            默认允许所有运行配置。项目路径必须是绝对路径。

            ## 调用流程

            1. GET %s/v1/status，按 project 和 configuration 精确选择配置；操作已有实例时取最新 executionId。
               如果该配置未运行，使用 start 操作并省略 executionId。
            2. POST %s/v1/tasks，Content-Type: application/json，完整 JSON 示例：

            ```json
            {"project":"C:/projects/my-app","configuration":"portal","executionId":123,"operation":"update-resources","caller":"agent-session-name"}
            ```

            executionId=123 只是示例，必须替换为实时查询值。caller 填写当前 agent/session 标识。
            3. HTTP 202 表示已接收，记录响应 id。GET %s/v1/tasks/{id} 轮询至 SUCCEEDED 或 FAILED。
            4. 更新结束后请求实际页面或业务接口验证生效；重启结果 processStarted 不是应用健康就绪。

            ## 操作选择

            - start：启动尚未运行的配置，executionId 省略或填 0。executor 可填 Debug（默认）或 Run。
              已运行时拒绝重复启动，不会停止已有实例。
              示例：{"project":"C:/projects/my-app","configuration":"portal","operation":"start","executor":"Debug","caller":"agent-session-name"}
            - update-resources：更新目标应用的静态资源，不主动重启。
            - update-classes-and-resources：编译资源与 class，只对目标 Debug 会话执行 HotSwap。
              要求本地应用服务器和 exploded 部署。Run 模式不支持 class 热替换。
            - restart：只有明确需要重启且共享服务使用者已协调好时才提交，保留原 Run/Debug 模式。
              重启后 executionId 会变化，后续操作必须重新查询。

            ## 并发与失败处理

            - 同一项目只允许一个修改任务。HTTP 409 时等待当前任务完成，再查状态后重试。
            - HTTP 403 是请求 Host 或浏览器 Origin 无效；400 是请求无效；503 是接口暂停等待插件更新。
            - FAILED 时读取 error。编译或 HotSwap 失败不自动重启，也不要将客户端超时视为取消。
            - 索引中、正在编译、存在未保存编辑内容、实例 ID 过期或实例不唯一时会拒绝操作。
            - 多个 agent 共享工作目录时，编译可能包含其他 agent 已写入磁盘的改动，先协调再更新。
            - resourceBuildCompleted / resourcePathsWritten / deploymentNotificationSubmitted 只描述
              IDE 构建与部署通知，不能证明服务器已经采用资源。applicationHealthVerified=false 时另做健康检查。

            ## 快速只读验证

            ```sh
            curl %s/v1/status
            curl %s/v1/help
            ```

            浏览器帮助与调试：%s/help。GET 接口不触发更新或重启，修改操作仅通过 POST /v1/tasks 提交。
            """.formatted(base,base,base,base,base,base,base);
    }
    public static String browserPage() throws IOException {
        try(var stream=BridgeHelp.class.getResourceAsStream("/web/help.html")) {
            if(stream==null) throw new IOException("Browser help resource missing");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    public static String dialog(String base,String connectionFile) {
        return "Agent 接口已启动（无需 Token）。\n\n"+
            "浏览器帮助 / 调试："+base+"/help\n"+
            "运行状态：GET "+base+"/v1/status\n"+
            "提交任务：POST "+base+"/v1/tasks\n"+
            "任务结果：GET "+base+"/v1/tasks/{id}\n"+
            "接口说明：GET "+base+"/v1/help\n"+
            "Agent Skill：GET "+base+"/v1/skill\n\n"+
            "可直接告诉 agent：先读取 "+base+"/v1/skill，按说明操作 IDEA。\n\n"+
            "连接信息："+connectionFile+"\n"+
            "默认允许所有运行配置。启动接口不会操作运行服务。";
    }
}
