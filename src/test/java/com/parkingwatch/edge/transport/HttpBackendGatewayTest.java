package com.parkingwatch.edge.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkingwatch.common.contract.ContractExamples;
import com.parkingwatch.common.contract.EvidenceKeys;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Acoplamiento HTTPS del Edge contra un backend falso con los ejemplos del contrato real. */
class HttpBackendGatewayTest {

  private static final String TOKEN = "pwd_test-token";
  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private HttpBackendGateway gateway;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requests.add(
              exchange.getRequestMethod()
                  + " "
                  + path
                  + " "
                  + exchange.getRequestHeaders().getFirst("Authorization")
                  + " "
                  + exchange.getRequestHeaders().getFirst("If-None-Match"));
          bodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
          Response response = route(path, exchange.getRequestHeaders().getFirst("If-None-Match"));
          response.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
          byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          }
          exchange.close();
        });
    server.start();
    gateway =
        new HttpBackendGateway(
            HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
            "CAM-MAQ-01",
            TOKEN);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private record Response(int status, String body, Map<String, String> headers) {}

  private static Response route(String path, String ifNoneMatch) throws IOException {
    if (path.endsWith("/configuration")) {
      if ("W/\"abc\"".equals(ifNoneMatch)) {
        return new Response(304, "", Map.of("ETag", "W/\"abc\""));
      }
      return new Response(
          200,
          example("edge-configuration"),
          Map.of("ETag", "W/\"abc\"", "Content-Type", "application/json"));
    }
    if (path.endsWith("/events/batch")) {
      return new Response(200, example("event-batch-result"), Map.of());
    }
    if (path.endsWith("/evidence")) {
      return new Response(201, example("evidence-keys"), Map.of());
    }
    if (path.endsWith("/models/det-1/detector")) {
      return new Response(200, "ONNX", Map.of());
    }
    if (path.endsWith("/models/det-1/plate-reader")) {
      return new Response(503, "{}", Map.of());
    }
    if (path.endsWith("/models/det-1/otro")) {
      return new Response(404, "{}", Map.of());
    }
    return new Response(202, "", Map.of());
  }

  private static String example(String name) throws IOException {
    return ContractExamples.read(name);
  }

  @Test
  void fetchesConfigurationWithEtagAndHandlesNotModified() throws Exception {
    var configuration = gateway.fetchConfiguration(null);
    assertThat(configuration)
        .hasValueSatisfying(
            c -> {
              assertThat(c.etag()).isEqualTo("W/\"abc\"");
              assertThat(c.configuration().zones()).hasSize(1);
            });
    assertThat(gateway.fetchConfiguration("W/\"abc\"")).isEmpty();
    assertThat(requests.get(0))
        .isEqualTo("GET /api/v1/edge/cameras/CAM-MAQ-01/configuration Bearer " + TOKEN + " null");
  }

  @Test
  void sendsBufferedEventsAndParsesPerEventOutcomes() throws Exception {
    var result = gateway.sendEvents(List.of());
    assertThat(result.results()).hasSize(3);
    assertThat(requests.get(0)).startsWith("POST /api/v1/edge/cameras/CAM-MAQ-01/events/batch");
  }

  @Test
  void uploadsTheThreePhotosInOneMultipartRequest() throws Exception {
    EvidenceKeys keys =
        gateway.uploadEvidence(new EvidenceBundle(new byte[] {1}, new byte[] {2}, new byte[] {3}));
    assertThat(keys.platePhotoKey()).endsWith("-plate.jpg");
    assertThat(requests.get(0)).startsWith("POST /api/v1/edge/cameras/CAM-MAQ-01/evidence Bearer");
    assertThat(bodies.get(0))
        .contains("name=\"entry\"", "name=\"report\"", "name=\"plate\"", "image/jpeg");
  }

  @Test
  void downloadsModelsAndMapsErrors(@TempDir Path directory) throws Exception {
    Path target = directory.resolve("detector.onnx");
    gateway.download("/api/v1/edge/cameras/CAM-MAQ-01/models/det-1/detector", target);
    assertThat(Files.readString(target)).isEqualTo("ONNX");
    assertThat(requests.get(0)).contains("Bearer " + TOKEN);
    assertThatThrownBy(
            () ->
                gateway.download(
                    "/api/v1/edge/cameras/CAM-MAQ-01/models/det-1/plate-reader", target))
        .isInstanceOfSatisfying(
            BackendException.class,
            e -> {
              assertThat(e.isRetryable()).isTrue();
              assertThat(e.status()).isEqualTo(503);
            });
    assertThatThrownBy(
            () -> gateway.download("/api/v1/edge/cameras/CAM-MAQ-01/models/det-1/otro", target))
        .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.isRetryable()).isFalse());
  }

  @Test
  void reportsAnUnreachableBackendAsRetryable() {
    server.stop(0);
    assertThatThrownBy(() -> gateway.fetchConfiguration(null))
        .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.isRetryable()).isTrue());
  }
}
