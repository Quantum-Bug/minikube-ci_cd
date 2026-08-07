import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.time.Instant;

public class Main {
    static void log(String msg) {
        System.out.println(Instant.now() + " level=INFO service=java-backend msg=\"" + msg + "\"");
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange -> {
            log("root endpoint hit");
            String resp = "{\"service\":\"java-backend\",\"status\":\"ok\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length());
            OutputStream os = exchange.getResponseBody();
            os.write(resp.getBytes());
            os.close();
        });

        server.createContext("/health", exchange -> {
            String resp = "{\"status\":\"healthy\"}";
            exchange.sendResponseHeaders(200, resp.length());
            OutputStream os = exchange.getResponseBody();
            os.write(resp.getBytes());
            os.close();
        });

        server.setExecutor(null);
        log("java-backend starting on port 8080");
        server.start();
    }
}
