package dev.agentbridge.core;

import com.google.gson.*;
import org.junit.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BridgeServerTest {
    private BridgeServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final FakeBackend backend = new FakeBackend();
    private String base;
    @Before public void start() throws Exception {
        server = new BridgeServer(backend);
        base = "http://127.0.0.1:" + server.start(0);
    }
    @After public void stop() { server.close(); }
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String body(String project, String config, String op) {
        return new Gson().toJson(Map.of("project",project,"configuration",config,"executionId",17,"operation",op,"caller","agent-test"));
    }
    private JsonObject job(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    private JsonObject awaitJob(String id) throws Exception {
        for (int i=0; i<100; i++) {
            var j = job(request("GET", "/v1/tasks/" + id,null));
            if (!j.get("status").getAsString().equals("RUNNING")) return j;
            Thread.sleep(10);
        }
        throw new AssertionError("Task did not finish");
    }
    @Test public void noTokenNeededAndLocalBrowserRequestsWork() throws Exception {
        assertEquals(200, request("GET","/v1/status",null).statusCode());
        var local=HttpRequest.newBuilder(URI.create(base+"/v1/tasks")).header("Origin",base)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body("p","portal","update-resources"))).build();
        assertEquals(202,http.send(local,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, request("GET","/v1/status",null).statusCode());
        var req=HttpRequest.newBuilder(URI.create(base+"/v1/status")).header("Origin","https://evil.example").build();
        assertEquals(403,http.send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test public void browserHelpAndAgentSkillDescribeRealApi() throws Exception {
        var home=request("GET","/",null);
        assertEquals(200,home.statusCode());
        assertTrue(home.headers().firstValue("Content-Type").orElse("").contains("text/html"));
        assertTrue(home.body().contains("/v1/status"));
        var help=request("GET","/v1/help",null);
        assertEquals(200,help.statusCode());
        assertFalse(job(help).get("authenticationRequired").getAsBoolean());
        assertEquals(base,job(help).get("baseUrl").getAsString());
        assertEquals("/v1/skill",job(help).get("skillEndpoint").getAsString());
        var skill=request("GET","/v1/skill",null);
        assertEquals(200,skill.statusCode());
        assertTrue(skill.body().contains(base));
        assertTrue(skill.body().contains("executionId"));
        assertTrue(skill.body().contains("update-classes-and-resources"));
        assertTrue(skill.body().contains("409"));
        assertEquals(skill.body(),request("GET","/skill.md",null).body());
        assertEquals(0,backend.calls.get());
    }
    @Test public void validationCannotReachBackend() throws Exception {
        assertEquals(400,request("POST","/v1/tasks","{").statusCode());
        assertEquals(400,request("POST","/v1/tasks","{}").statusCode());
        assertEquals(400,request("POST","/v1/tasks",body("p","portal","stop-all")).statusCode());
        assertEquals(0,backend.calls.get());
    }
    @Test public void arbitraryConfigurationNamesNeedNoAllowlist() throws Exception {
        assertFalse(job(request("GET","/v1/status",null)).has("allowedConfigurations"));
        assertFalse(job(request("GET","/v1/help",null)).has("allowedConfigurations"));
        assertEquals(202,request("POST","/v1/tasks",body("p","my-custom-app","restart")).statusCode());
        backend.complete("p",Map.of("processStarted",true));
    }
    @Test public void startAcceptsNoInstanceIdButRejectsInvalidExecutor() throws Exception {
        String start="{\"project\":\"p\",\"configuration\":\"portal\",\"operation\":\"start\",\"caller\":\"test\",\"executor\":\"Debug\"}";
        assertEquals(400,request("POST","/v1/tasks",start.replace("Debug","Other")).statusCode());
        assertEquals(400,request("POST","/v1/tasks",start.replace("\"start\"","\"restart\"")).statusCode());
        assertEquals(202,request("POST","/v1/tasks",start).statusCode());
    }
    @Test public void serializeSharedProjectUntilRealCompletion() throws Exception {
        var first=request("POST","/v1/tasks",body("p","portal","update-resources"));
        assertEquals(202,first.statusCode());
        assertTrue(server.isBusy());
        assertEquals(409,request("POST","/v1/tasks",body("p","file-agent","restart")).statusCode());
        assertEquals(202,request("POST","/v1/tasks",body("another","file-agent","restart")).statusCode());
        backend.complete("p",Map.of("compiled",true));
        assertEquals("SUCCEEDED",awaitJob(job(first).get("id").getAsString()).get("status").getAsString());
        assertEquals(202,request("POST","/v1/tasks",body("p","portal","restart")).statusCode());
    }
    @Test public void failureNeverTriggersRestartAndPreservesError() throws Exception {
        var response=request("POST","/v1/tasks",body("p","portal","update-classes-and-resources"));
        backend.fail("p",new IllegalStateException("HotSwap failed: schema change"));
        var done=awaitJob(job(response).get("id").getAsString());
        assertEquals("FAILED",done.get("status").getAsString());
        assertTrue(done.get("error").getAsString().contains("schema change"));
        assertEquals(1,backend.calls.get());
    }
    @Test public void stopReleasesPortAndCancelsBackend() throws Exception {
        request("POST","/v1/tasks",body("p","portal","restart"));
        int port=server.port();
        server.close();
        assertTrue(backend.closed);
        try(var socket=new java.net.ServerSocket()) { socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1",port)); }
    }
    @Test public void oversizedBodyAndUnknownRoutes() throws Exception {
        assertEquals(413,request("POST","/v1/tasks"," ".repeat(17000)).statusCode());
        assertEquals(404,request("GET","/v1/tasks/missing",null).statusCode());
        assertEquals(404,request("GET","/v1/status/extra",null).statusCode());
    }
    @Test public void synchronousBackendErrorReleasesLock() throws Exception {
        backend.throwImmediately=true;
        var response=request("POST","/v1/tasks",body("p","portal","restart"));
        assertEquals("FAILED",awaitJob(job(response).get("id").getAsString()).get("status").getAsString());
        assertFalse(server.isBusy());
    }
    @Test public void unloadAdmissionIsAtomicAndCanResume() throws Exception {
        assertTrue(server.quiesceIfIdle());
        assertEquals(503,request("POST","/v1/tasks",body("p","portal","restart")).statusCode());
        server.resume();
        assertEquals(202,request("POST","/v1/tasks",body("p","portal","restart")).statusCode());
        assertFalse(server.quiesceIfIdle());
    }
    @Test public void normalizedProjectAliasesCannotBypassLock() throws Exception {
        assertEquals(202,request("POST","/v1/tasks",body("p","portal","restart")).statusCode());
        assertEquals(409,request("POST","/v1/tasks",body("other/../p","file-agent","restart")).statusCode());
    }
    @Test public void unloadInterruptsAnInflightStatusQuery() throws Exception {
        backend.blockStatus=true;
        var request=HttpRequest.newBuilder(URI.create(base+"/v1/status")).build();
        var pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertTrue(backend.statusEntered.await(3,TimeUnit.SECONDS));
        server.close();
        pending.handle((v,e)->null).get(3,TimeUnit.SECONDS);
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive() && t.getName().equals("idea-agent-bridge-http")));
    }
    static class FakeBackend implements BridgeBackend {
        final ConcurrentHashMap<String,CompletableFuture<Map<String,Object>>> pending=new ConcurrentHashMap<>();
        final AtomicInteger calls=new AtomicInteger();
        volatile boolean closed,throwImmediately;
        volatile boolean blockStatus;
        final CountDownLatch statusEntered=new CountDownLatch(1);
        public List<Map<String,Object>> configurations() throws Exception {
            if(blockStatus) { statusEntered.countDown(); new CountDownLatch(1).await(); }
            return List.of(Map.of("configuration","portal","executionId",17));
        }
        public CompletionStage<Map<String,Object>> execute(BridgeRequest request) {
            calls.incrementAndGet();
            if(throwImmediately) throw new IllegalStateException("compile failure");
            var future=new CompletableFuture<Map<String,Object>>(); pending.put(request.project(),future); return future;
        }
        void complete(String project,Map<String,Object> result) throws Exception { waitFor(project).complete(result); }
        void fail(String project,Throwable error) throws Exception { waitFor(project).completeExceptionally(error); }
        CompletableFuture<Map<String,Object>> waitFor(String project) throws Exception {
            for(int i=0;i<100 && !pending.containsKey(project);i++) Thread.sleep(10);
            return Objects.requireNonNull(pending.get(project));
        }
        public void close() { closed=true; pending.values().forEach(f -> f.cancel(true)); }
    }
}
