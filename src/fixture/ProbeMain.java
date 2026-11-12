import java.nio.file.*;

/** Isolated disposable process used only by SDK integration tests. */
public final class ProbeMain {
    public static void main(String[] args) throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/probe",exchange-> {
            byte[] data=(version()+":"+ProcessHandle.current().pid()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length);
            try(var out=exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        Files.writeString(Path.of(args[0]+".port"),Integer.toString(server.getAddress().getPort()));
        Files.writeString(Path.of(args[0]),Long.toString(ProcessHandle.current().pid()));
        System.out.println("Agent Bridge test process: "+ProcessHandle.current().pid());
        try { Thread.sleep(120_000); } finally { server.stop(0); }
    }
    public static String version() { return "v1"; }
}
