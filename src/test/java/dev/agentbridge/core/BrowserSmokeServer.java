package dev.agentbridge.core;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Browser-only test backend. It cannot access IDEA or any user service. */
public final class BrowserSmokeServer {
    public static void main(String[] args) throws Exception {
        BridgeBackend fake=new BridgeBackend() {
            public List<Map<String,Object>> configurations() {
                return List.of(Map.of("project","D:/browser-fixture","configuration","portal","instances",
                    List.of(Map.of("executionId",17,"executors",List.of("Debug")))));
            }
            public CompletionStage<Map<String,Object>> execute(BridgeRequest request) {
                return CompletableFuture.completedFuture(Map.of("fixture",true,"operation",request.operation()));
            }
            public void close() { }
        };
        try(var server=new BridgeServer(fake,Set.of("portal","file-agent"))) {
            int port=server.start(0);
            String base="http://127.0.0.1:"+port;
            Files.writeString(Path.of("build/reports/browser-connection.json"),new Gson().toJson(Map.of("baseUrl",base)));
            System.out.println("Browser test fixture: "+base);
            Thread.sleep(180_000);
        }
    }
}
