package com.parkingwatch.edge.support;

import com.parkingwatch.common.contract.AnalyticsSnapshot;
import com.parkingwatch.common.contract.DetectionSnapshot;
import com.parkingwatch.common.contract.DriftAlertMessage;
import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.common.contract.EventBatchResult;
import com.parkingwatch.common.contract.EventResult;
import com.parkingwatch.common.contract.EvidenceKeys;
import com.parkingwatch.common.contract.HeartbeatMessage;
import com.parkingwatch.common.contract.MinuteStatisticsMessage;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.domain.ZoneType;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import com.parkingwatch.edge.transport.BackendException;
import com.parkingwatch.edge.transport.BackendGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Backend en memoria para probar el Edge sin red (doble de prueba del puerto Gateway). */
public final class InMemoryBackendGateway implements BackendGateway {

  public final List<ParkingEventMessage> events = new CopyOnWriteArrayList<>();
  public final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
  public final List<HeartbeatMessage> heartbeats = new CopyOnWriteArrayList<>();
  public final List<MinuteStatisticsMessage> statistics = new CopyOnWriteArrayList<>();
  public final List<DetectionSnapshot> detections = new CopyOnWriteArrayList<>();
  public final List<AnalyticsSnapshot> analytics = new CopyOnWriteArrayList<>();
  public final List<DriftAlertMessage> driftAlerts = new CopyOnWriteArrayList<>();
  public final List<byte[]> videoFrames = new CopyOnWriteArrayList<>();
  private volatile boolean available = true;
  private volatile EdgeConfiguration configuration = maquetaConfiguration();

  /** Simula una caída de red. */
  public void setAvailable(boolean value) {
    this.available = value;
  }

  public void setConfiguration(EdgeConfiguration value) {
    this.configuration = value;
  }

  /** Configuración con la zona amarilla, el garaje y el carril sin señalizar de la maqueta. */
  public static EdgeConfiguration maquetaConfiguration() {
    return new EdgeConfiguration(
        "CAM-MAQ-01",
        1,
        new EdgeConfiguration.FrameSize(1280, 720),
        List.of(
            zone(1, "Zona amarilla", ZoneType.YELLOW_ZONE, true, 820, 420, 1180, 560),
            zone(2, "Entrada de garaje", ZoneType.GARAGE_ENTRANCE, true, 420, 430, 600, 560),
            zone(5, "Carril", ZoneType.TRAFFIC_LANE, false, 0, 260, 1280, 400)),
        new EdgeConfiguration.ActiveModel("det-1.0-maqueta", null, null),
        EdgeConfiguration.Settings.defaults());
  }

  private static EdgeConfiguration.ZoneDefinition zone(
      long id,
      String name,
      ZoneType type,
      boolean signaled,
      double x1,
      double y1,
      double x2,
      double y2) {
    return new EdgeConfiguration.ZoneDefinition(
        id,
        name,
        type,
        List.of(
            new EdgeConfiguration.Point(x1, y1),
            new EdgeConfiguration.Point(x2, y1),
            new EdgeConfiguration.Point(x2, y2),
            new EdgeConfiguration.Point(x1, y2)),
        10,
        signaled);
  }

  @Override
  public Optional<VersionedConfiguration> fetchConfiguration(String etag) throws BackendException {
    ensureAvailable();
    String current = "W/\"" + configuration.configVersion() + "\"";
    return current.equals(etag)
        ? Optional.empty()
        : Optional.of(new VersionedConfiguration(current, configuration));
  }

  @Override
  public void sendHeartbeat(HeartbeatMessage message) throws BackendException {
    ensureAvailable();
    heartbeats.add(message);
  }

  @Override
  public EventBatchResult sendEvents(List<ParkingEventMessage> batch) throws BackendException {
    ensureAvailable();
    events.addAll(batch);
    return new EventBatchResult(
        batch.stream().map(e -> EventResult.accepted(e.eventId(), null)).toList());
  }

  @Override
  public EvidenceKeys uploadEvidence(EvidenceBundle evidence) throws BackendException {
    ensureAvailable();
    String base = "evidence/CAM-MAQ-01/" + uploads.size() / 3;
    uploads.put(base + "-entry.jpg", evidence.entry());
    uploads.put(base + "-report.jpg", evidence.report());
    uploads.put(base + "-plate.jpg", evidence.plate());
    return new EvidenceKeys(base + "-entry.jpg", base + "-report.jpg", base + "-plate.jpg");
  }

  @Override
  public void sendVideoFrame(byte[] jpeg) throws BackendException {
    ensureAvailable();
    videoFrames.add(jpeg.clone());
  }

  @Override
  public void sendStatistics(MinuteStatisticsMessage message) throws BackendException {
    ensureAvailable();
    statistics.add(message);
  }

  @Override
  public void sendDetections(DetectionSnapshot snapshot) throws BackendException {
    ensureAvailable();
    detections.add(snapshot);
  }

  @Override
  public void sendAnalytics(AnalyticsSnapshot snapshot) throws BackendException {
    ensureAvailable();
    analytics.add(snapshot);
  }

  @Override
  public void sendDriftAlert(DriftAlertMessage message) throws BackendException {
    ensureAvailable();
    driftAlerts.add(message);
  }

  private void ensureAvailable() throws BackendException {
    if (!available) {
      throw BackendException.unreachable(new java.io.IOException("red caída"));
    }
  }
}
