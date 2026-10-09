package com.parkingwatch.edge.pipeline;

import com.parkingwatch.common.contract.DetectionSnapshot;
import com.parkingwatch.common.contract.DriftAlertMessage;
import com.parkingwatch.common.contract.ParkingEventMessage;
import com.parkingwatch.common.contract.PlateReading;
import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.edge.analytics.DriftMonitor;
import com.parkingwatch.edge.analytics.MinuteAggregator;
import com.parkingwatch.edge.analytics.RealtimeAnalytics;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.MonitoredZone;
import com.parkingwatch.edge.evidence.EvidenceBundle;
import com.parkingwatch.edge.evidence.EvidenceCollector;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.monitoring.EdgeMetrics;
import com.parkingwatch.edge.ocr.PlateVoter;
import com.parkingwatch.edge.transport.ReliableEventPublisher;
import com.parkingwatch.edge.zone.VehicleZoneStatus;
import com.parkingwatch.edge.zone.ZoneEvent;
import com.parkingwatch.edge.zone.ZoneRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Último paso de cada fotograma: convierte los eventos de zona en mensajes del contrato con su
 * evidencia (RF-7.3), alimenta la analítica (PB-12) y el monitoreo de deriva (RF-14.2) y deja lista
 * la muestra de detecciones para el video en vivo (RF-2.2).
 */
public final class AnalysisProcessor implements Consumer<FrameData.Analysis> {

  private static final Logger LOG = LoggerFactory.getLogger(AnalysisProcessor.class);

  /** Colaboradores del procesador. */
  public record Collaborators(
      ReliableEventPublisher publisher,
      EvidenceCollector evidence,
      PlateVoter voter,
      RealtimeAnalytics analytics,
      MinuteAggregator minutes,
      DriftMonitor drift,
      Consumer<DriftAlertMessage> driftAlerts,
      ZoneRegistry zones,
      EdgeMetrics metrics) {}

  private final Collaborators c;
  private final AtomicReference<DetectionSnapshot> latestDetections = new AtomicReference<>();
  private final AtomicReference<Frame> latestFrame = new AtomicReference<>();

  public AnalysisProcessor(Collaborators collaborators) {
    this.c = collaborators;
  }

  @Override
  public void accept(FrameData.Analysis analysis) {
    Frame frame = analysis.frame();
    Instant at = frame.capturedAt();
    c.metrics().frameProcessed(at.toEpochMilli());
    c.analytics()
        .recordFrame(at, analysis.vehicles(), analysis.detected().latency().toNanos() / 1e6);
    c.minutes().recordFrame(at, analysis.vehicles(), zoneIds());
    c.drift()
        .record(analysis.detected().meanConfidence(), frame.meanBrightness(), at)
        .ifPresent(c.driftAlerts());
    latestDetections.set(snapshot(analysis));
    latestFrame.set(frame);
    for (ZoneEvent event : analysis.events()) {
      handle(event, frame, analysis.detected().modelVersion());
    }
  }

  /** Toma (y limpia) el fotograma más reciente para la vista en vivo; null si no hay uno nuevo. */
  public Frame takeLatestFrame() {
    return latestFrame.getAndSet(null);
  }

  /** Toma (y limpia) la muestra de detecciones más reciente para enviarla al backend. */
  public DetectionSnapshot takeLatestDetections() {
    return latestDetections.getAndSet(null);
  }

  private void handle(ZoneEvent event, Frame frame, String modelVersion) {
    long zoneId = event.zone().id();
    try {
      switch (event.type()) {
        case ZONE_ENTERED -> {
          c.evidence().rememberEntry(event.trackId(), zoneId, frame);
          c.publisher().publish(message(event, modelVersion, null), null);
        }
        case TOLERANCE_EXCEEDED -> {
          PlateReading plate = c.voter().vote(event.trackId());
          EvidenceBundle bundle = c.evidence().collect(event.trackId(), zoneId, frame, event.box());
          c.publisher().publish(message(event, modelVersion, plate), bundle);
          c.analytics().recordReport(event.occurredAt());
          LOG.info(
              "Vehículo {} superó la tolerancia en {} (placa {})",
              event.trackId(),
              event.zone().name(),
              plate.value());
        }
        case ZONE_EXITED -> {
          c.publisher().publish(message(event, modelVersion, null), null);
          c.analytics().recordExit(event.occurredAt(), event.dwellSeconds());
          c.minutes().recordExit(event.occurredAt(), zoneId, event.dwellSeconds());
          c.voter().forget(event.trackId());
          c.evidence().forget(event.trackId(), zoneId);
        }
        default -> throw new IllegalStateException("Evento no soportado: " + event.type());
      }
      c.metrics().eventPublished();
    } catch (IOException e) {
      LOG.error("No se pudo guardar el evento {} en el buzón local", event.type(), e);
    }
  }

  private static ParkingEventMessage message(
      ZoneEvent event, String modelVersion, PlateReading plate) {
    return new ParkingEventMessage(
        UUID.randomUUID(),
        event.type(),
        event.zone().id(),
        event.trackId(),
        event.vehicleType(),
        round(event.confidence()),
        modelVersion,
        event.enteredAt(),
        event.occurredAt(),
        round(event.dwellSeconds()),
        event.type() == ParkingEventType.TOLERANCE_EXCEEDED ? plate : null,
        null);
  }

  private static DetectionSnapshot snapshot(FrameData.Analysis analysis) {
    List<DetectionSnapshot.TrackedVehicle> vehicles =
        analysis.vehicles().stream().map(AnalysisProcessor::toView).toList();
    Frame frame = analysis.frame();
    return new DetectionSnapshot(frame.capturedAt(), frame.width(), frame.height(), vehicles);
  }

  private static DetectionSnapshot.TrackedVehicle toView(VehicleZoneStatus vehicle) {
    BoundingBox box = vehicle.track().box();
    return new DetectionSnapshot.TrackedVehicle(
        vehicle.track().trackId(),
        vehicle.track().type(),
        new DetectionSnapshot.Box(
            round(box.x1()), round(box.y1()), round(box.x2()), round(box.y2())),
        round(vehicle.track().confidence()),
        vehicle.zoneId(),
        round(vehicle.stationarySeconds()));
  }

  private List<Long> zoneIds() {
    return c.zones().current().stream().map(MonitoredZone::id).toList();
  }

  private static double round(double value) {
    return Math.round(value * 100) / 100.0;
  }
}
