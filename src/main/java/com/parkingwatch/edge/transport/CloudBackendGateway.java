package com.parkingwatch.edge.transport;

import com.parkingwatch.common.contract.AnalyticsSnapshot;
import com.parkingwatch.common.contract.DetectionSnapshot;
import com.parkingwatch.common.contract.DriftAlertMessage;
import com.parkingwatch.common.contract.EdgeStomp;
import com.parkingwatch.common.contract.EventBatch;
import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EventResult;
import com.parkingwatch.common.contract.EvidenceKeys;
import com.parkingwatch.common.contract.HeartbeatMessage;
import com.parkingwatch.common.contract.MinuteStatisticsMessage;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conexión con el Backend en Render (PB-15): el tiempo real viaja por WSS ({@link StompChannel}) y
 * lo demás por HTTPS ({@link HttpBackendGateway}). Los eventos se envían por WSS y se espera su
 * resultado en la cola del Edge; si no hay conexión o el resultado no llega a tiempo, se envían por
 * HTTPS como lote. El backend es idempotente por eventId, así que un doble envío no duplica nada.
 */
public final class CloudBackendGateway implements BackendGateway {

  private static final Logger LOG = LoggerFactory.getLogger(CloudBackendGateway.class);

  private final HttpBackendGateway https;
  private final StompChannel stomp;
  private final Duration resultTimeout;
  private final Map<UUID, CompletableFuture<EventBatchResult>> pending = new ConcurrentHashMap<>();

  public CloudBackendGateway(HttpBackendGateway https, StompChannel stomp, Duration resultTimeout) {
    this.https = https;
    this.stomp = stomp;
    this.resultTimeout = resultTimeout;
    stomp.onEventResults(this::complete);
  }

  @Override
  public Optional<VersionedConfiguration> fetchConfiguration(String etag) throws BackendException {
    return https.fetchConfiguration(etag);
  }

  @Override
  public EventBatchResult sendEvents(List<ParkingEventMessage> events) throws BackendException {
    if (events.isEmpty() || !stomp.isConnected()) {
      return https.sendEvents(events);
    }
    UUID key = events.get(0).eventId();
    CompletableFuture<EventBatchResult> result = new CompletableFuture<>();
    pending.put(key, result);
    try {
      stomp.sendJson(EdgeStomp.EVENTS, new EventBatch(events));
      return result.get(resultTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (BackendException | TimeoutException | ExecutionException e) {
      LOG.info("Sin resultado por WebSocket; se envía el lote por HTTPS");
      return https.sendEvents(events);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw BackendException.unreachable(e);
    } finally {
      pending.remove(key);
    }
  }

  @Override
  public EvidenceKeys uploadEvidence(EvidenceBundle evidence) throws BackendException {
    return https.uploadEvidence(evidence);
  }

  @Override
  public void sendHeartbeat(HeartbeatMessage message) throws BackendException {
    stomp.sendJson(EdgeStomp.HEARTBEAT, message);
  }

  @Override
  public void sendStatistics(MinuteStatisticsMessage message) throws BackendException {
    stomp.sendJson(EdgeStomp.STATISTICS, message);
  }

  @Override
  public void sendDetections(DetectionSnapshot snapshot) throws BackendException {
    stomp.sendJson(EdgeStomp.DETECTIONS, snapshot);
  }

  @Override
  public void sendAnalytics(AnalyticsSnapshot snapshot) throws BackendException {
    stomp.sendJson(EdgeStomp.ANALYTICS, snapshot);
  }

  @Override
  public void sendDriftAlert(DriftAlertMessage message) throws BackendException {
    stomp.sendJson(EdgeStomp.ALERTS, message);
  }

  @Override
  public void sendVideoFrame(byte[] jpeg) throws BackendException {
    stomp.sendBinary(EdgeStomp.VIDEO, jpeg);
  }

  /** Completa la espera del lote cuyo primer evento aparece en el resultado. */
  void complete(EventBatchResult result) {
    result.results().stream()
        .map(EventResult::eventId)
        .map(pending::get)
        .filter(future -> future != null)
        .findFirst()
        .ifPresent(future -> future.complete(result));
  }
}
