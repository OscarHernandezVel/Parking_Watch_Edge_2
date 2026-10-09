package com.parkingwatch.edge.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkingwatch.common.contract.ContractJson;
import com.parkingwatch.common.contract.EdgeApi;
import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.common.contract.EventBatch;
import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EvidenceKeys;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Parte HTTPS de la conexión con el Backend (RF-8.3, RF-15.1, RF-15.3) con el cliente HTTP de Java
 * 21: configuración con ETag, lote de eventos guardados sin conexión, subida multipart de las fotos
 * de evidencia y descarga de los modelos. Autentica con el token del dispositivo; la conexión
 * siempre la inicia el Edge, así que no necesita puertos abiertos.
 */
public final class HttpBackendGateway {

  private static final int NOT_MODIFIED = 304;
  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final String CRLF = new String(new char[] {13, 10});

  private final HttpClient client;
  private final ObjectMapper mapper = ContractJson.newMapper();
  private final String baseUrl;
  private final String cameraId;
  private final String deviceToken;

  public HttpBackendGateway(
      HttpClient client, String baseUrl, String cameraId, String deviceToken) {
    this.client = client;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.cameraId = cameraId;
    this.deviceToken = deviceToken;
  }

  /** Configuración con su ETag; vacío si no cambió (HTTP 304). */
  public Optional<BackendGateway.VersionedConfiguration> fetchConfiguration(String etag)
      throws BackendException {
    HttpRequest.Builder request =
        authorized(EdgeApi.pathFor(cameraId, EdgeApi.CONFIGURATION)).GET();
    if (etag != null) {
      request.header("If-None-Match", etag);
    }
    HttpResponse<String> response = send(request.build());
    if (response.statusCode() == NOT_MODIFIED) {
      return Optional.empty();
    }
    EdgeConfiguration configuration = read(ensureSuccess(response), EdgeConfiguration.class);
    String newEtag = response.headers().firstValue("ETag").orElse(null);
    return Optional.of(new BackendGateway.VersionedConfiguration(newEtag, configuration));
  }

  /** Lote de eventos guardados en el búfer local (RF-8.3). */
  public EventBatchResult sendEvents(List<ParkingEventMessage> events) throws BackendException {
    try {
      HttpRequest request =
          authorized(EdgeApi.pathFor(cameraId, EdgeApi.EVENTS_BATCH))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofByteArray(
                      mapper.writeValueAsBytes(new EventBatch(events))))
              .build();
      return read(ensureSuccess(send(request)), EventBatchResult.class);
    } catch (JsonProcessingException e) {
      throw new BackendException("No se pudo serializar el lote", 0, false, e);
    }
  }

  /** Sube las tres fotos en una petición multipart y retorna sus rutas en el backend. */
  public EvidenceKeys uploadEvidence(EvidenceBundle evidence) throws BackendException {
    String boundary = "cupo-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    part(body, boundary, "entry", evidence.entry());
    part(body, boundary, "report", evidence.report());
    part(body, boundary, "plate", evidence.plate());
    body.writeBytes(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.US_ASCII));
    HttpRequest request =
        authorized(EdgeApi.pathFor(cameraId, EdgeApi.EVIDENCE))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    return read(ensureSuccess(send(request)), EvidenceKeys.class);
  }

  /** Descarga un archivo del backend (ruta relativa, p. ej. un modelo ONNX) a {@code target}. */
  public void download(String path, Path target) throws BackendException {
    try {
      HttpResponse<Path> response =
          client.send(
              authorized(path).timeout(Duration.ofMinutes(5)).GET().build(),
              HttpResponse.BodyHandlers.ofFile(target));
      if (response.statusCode() != 200) {
        throw BackendException.fromStatus(response.statusCode(), "descarga de " + path);
      }
    } catch (IOException e) {
      throw BackendException.unreachable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BackendException.unreachable(e);
    }
  }

  private static void part(ByteArrayOutputStream body, String boundary, String name, byte[] jpeg) {
    String header =
        "--"
            + boundary
            + CRLF
            + "Content-Disposition: form-data; name=\""
            + name
            + "\"; filename=\""
            + name
            + ".jpg\""
            + CRLF
            + "Content-Type: image/jpeg"
            + CRLF
            + CRLF;
    body.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
    body.writeBytes(jpeg);
    body.writeBytes(CRLF.getBytes(StandardCharsets.US_ASCII));
  }

  private HttpRequest.Builder authorized(String path) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + deviceToken)
        .header("Accept", "application/json");
  }

  private HttpResponse<String> send(HttpRequest request) throws BackendException {
    try {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw BackendException.unreachable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BackendException.unreachable(e);
    }
  }

  private static String ensureSuccess(HttpResponse<String> response) throws BackendException {
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw BackendException.fromStatus(status, response.body());
    }
    return response.body();
  }

  private <T> T read(String body, Class<T> type) throws BackendException {
    try {
      return mapper.readValue(body, type);
    } catch (JsonProcessingException e) {
      throw new BackendException("Respuesta del backend inválida", 0, false, e);
    }
  }
}
