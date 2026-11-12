package dev.agentbridge.core;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Owns HTTP threads and task state. Never retains IDEA objects in task history. */
public final class BridgeServer implements AutoCloseable {
    private static final int MAX_BODY = 16384, MAX_HISTORY = 200;
    private final BridgeBackend backend;
    private final Gson json = new GsonBuilder().disableHtmlEscaping().create();
    private final LinkedHashMap<String,Task> tasks = new LinkedHashMap<>();
    private final Set<String> lockedProjects = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32),r -> {
            var t=new Thread(r,"idea-agent-bridge-http");
            t.setDaemon(true); t.setContextClassLoader(ClassLoader.getPlatformClassLoader()); return t;
        },new ThreadPoolExecutor.AbortPolicy());
    private HttpServer server;
    private boolean closed;
    private boolean accepting=true;

    public BridgeServer(BridgeBackend backend) {
        this.backend=backend;
    }
    public synchronized int start(int port) throws IOException {
        if(closed || server!=null) throw new IllegalStateException("Server already started or closed");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);
        server.createContext("/",this::handle); server.setExecutor(workers);
        var thread=Thread.currentThread(); var previous=thread.getContextClassLoader();
        try { thread.setContextClassLoader(ClassLoader.getPlatformClassLoader()); server.start(); }
        finally { thread.setContextClassLoader(previous); }
        return port();
    }
    public synchronized int port() { return server.getAddress().getPort(); }
    public synchronized boolean isBusy() { return !lockedProjects.isEmpty(); }
    public synchronized boolean quiesceIfIdle() {
        if(isBusy()) return false;
        accepting=false; return true;
    }
    public synchronized void resume() { if(!closed) accepting=true; }
    private void handle(HttpExchange exchange) throws IOException {
        try(exchange) {
            try {
                String origin=exchange.getRequestHeaders().getFirst("Origin");
                if(origin!=null && !baseUrl().equals(origin)) throw new ApiError(403,"Only same-origin browser requests are accepted");
                String host=exchange.getRequestHeaders().getFirst("Host");
                if(!("127.0.0.1:"+port()).equals(host)) throw new ApiError(403,"Invalid Host");
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                if((path.equals("/") || path.equals("/help")) && method.equals("GET")) {
                    sendText(exchange,"text/html",BridgeHelp.browserPage());
                } else if(path.equals("/v1/help") && method.equals("GET")) {
                    send(exchange,200,BridgeHelp.api(baseUrl()));
                } else if((path.equals("/v1/skill") || path.equals("/skill.md")) && method.equals("GET")) {
                    sendText(exchange,"text/markdown",BridgeHelp.skill(baseUrl()));
                } else if(path.equals("/v1/status") && method.equals("GET")) {
                    send(exchange,200,Map.of("version",BridgeHelp.VERSION,"busy",isBusy(),
                        "configurations",backend.configurations()));
                } else if(path.equals("/v1/tasks") && method.equals("POST")) {
                    String type=exchange.getRequestHeaders().getFirst("Content-Type");
                    if(type==null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))
                        throw new ApiError(415,"Content-Type must be application/json");
                    byte[] body=exchange.getRequestBody().readNBytes(MAX_BODY+1);
                    if(body.length>MAX_BODY) throw new ApiError(413,"Request body exceeds 16 KiB");
                    BridgeRequest request;
                    try {
                        request=json.fromJson(new String(body,StandardCharsets.UTF_8),BridgeRequest.class);
                        if(request==null) throw new IllegalArgumentException("Request required");
                        request.validate();
                    } catch(JsonParseException | IllegalArgumentException e) { throw new ApiError(400,e.getMessage()); }
                    Task task=submit(request);
                    send(exchange,202,task.snapshot());
                } else if(path.startsWith("/v1/tasks/") && method.equals("GET")) {
                    Task task;
                    synchronized(this) { task=tasks.get(path.substring("/v1/tasks/".length())); }
                    if(task==null) throw new ApiError(404,"Task not found");
                    send(exchange,200,task.snapshot());
                } else throw new ApiError(404,"Route not found");
            } catch(ApiError e) { send(exchange,e.code,Map.of("error",String.valueOf(e.getMessage()))); }
            catch(Exception e) { send(exchange,500,Map.of("error",String.valueOf(e.getMessage()))); }
        }
    }
    private String baseUrl() { return "http://127.0.0.1:"+port(); }
    private void sendText(HttpExchange exchange,String type,String content) throws IOException {
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
    }
    private Task submit(BridgeRequest request) {
        Task task;
        synchronized(this) {
            if(closed || !accepting) throw new ApiError(503,"Bridge is closing or paused for plugin update");
            // Windows project paths are case insensitive. Normalize aliases before admission.
            String key=java.nio.file.Path.of(request.project().replace('\\','/')).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            if(lockedProjects.contains(key)) throw new ApiError(409,"Project has an active task; retry after it finishes");
            if(lockedProjects.size()>=16) throw new ApiError(429,"Too many active tasks");
            lockedProjects.add(key);
            task=new Task(request,key); tasks.put(task.id,task);
            var iterator=tasks.values().iterator();
            while(tasks.size()>MAX_HISTORY && iterator.hasNext()) {
                if(!iterator.next().status.equals("RUNNING")) iterator.remove();
            }
        }
        try {
            backend.execute(request).whenComplete((result,error) -> {
                synchronized(BridgeServer.this) {
                    task.finish(result,error); lockedProjects.remove(task.projectKey);
                }
            });
        } catch(Exception e) {
            synchronized(this) { task.finish(null,e); lockedProjects.remove(task.projectKey); }
        }
        return task;
    }
    private void send(HttpExchange exchange,int code,Object value) throws IOException {
        byte[] bytes=json.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        exchange.sendResponseHeaders(code,bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        HttpServer previous;
        synchronized(this) {
            if(closed) return; closed=true; previous=server;
        }
        if(previous!=null) previous.stop(0);
        backend.close(); workers.shutdownNow();
        try { workers.awaitTermination(2,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized(this) {
            tasks.values().stream().filter(t->t.status.equals("RUNNING"))
                .forEach(t->t.finish(null,new CancellationException("Bridge unloaded")));
            lockedProjects.clear();
        }
    }
    private static final class Task {
        final String id=UUID.randomUUID().toString(),createdAt=Instant.now().toString(),projectKey;
        final BridgeRequest request;
        String status="RUNNING",finishedAt,error;
        Map<String,Object> result;
        Task(BridgeRequest request,String projectKey) { this.request=request; this.projectKey=projectKey; }
        synchronized void finish(Map<String,Object> result,Throwable error) {
            if(!status.equals("RUNNING")) return;
            this.result=result;
            if(error!=null) {
                while(error instanceof CompletionException && error.getCause()!=null) error=error.getCause();
                this.error=error.getClass().getSimpleName()+": "+error.getMessage();
            }
            status=error==null ? "SUCCEEDED" : "FAILED"; finishedAt=Instant.now().toString();
        }
        synchronized Map<String,Object> snapshot() {
            var map=new LinkedHashMap<String,Object>();
            map.put("id",id); map.put("status",status); map.put("request",request); map.put("createdAt",createdAt);
            map.put("finishedAt",finishedAt); map.put("result",result); map.put("error",error); return map;
        }
    }
    private static final class ApiError extends RuntimeException {
        final int code;
        ApiError(int code,String message) { super(message); this.code=code; }
    }
}
