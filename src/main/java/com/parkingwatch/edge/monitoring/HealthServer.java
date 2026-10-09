package com.parkingwatch.edge.monitoring;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Servidor HTTP mínimo del agente: {@code /health} para systemd y el monitoreo (RNF-5.1) y {@code
 * /metrics} en formato Prometheus (RNF-9.1). Solo escucha en la red local de la maqueta.
 */
public final class HealthServer implements AutoCloseable {

  private static final Duration STALE_AFTER = Duration.ofSeconds(10);

  private final HttpServer server;

  public HealthServer(int port, EdgeMetrics metrics, Clock clock, Supplier<String> status)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/metrics",
        exchange -> respond(exchange, 200, "text/plain; version=0.0.4", metrics.scrape()));
    server.createContext(
        "/health",
        exchange -> {
          long age = clock.millis() - metrics.lastFrameEpochMillis();
          boolean up = metrics.lastFrameEpochMillis() > 0 && age < STALE_AFTER.toMillis();
          String body =
              "{\"status\":\""
                  + (up ? "UP" : "DOWN")
                  + "\",\"lastFrameAgeMs\":"
                  + age
                  + ",\"detail\":\""
                  + status.get()
                  + "\"}";
          respond(exchange, up ? 200 : 503, "application/json", body);
        });
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int status, String type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
