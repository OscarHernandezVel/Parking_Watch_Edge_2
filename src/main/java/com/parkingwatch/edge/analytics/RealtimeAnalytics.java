package com.parkingwatch.edge.analytics;

import com.parkingwatch.common.contract.AnalyticsSnapshot;
import com.parkingwatch.edge.zone.VehicleZoneStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Indicadores en tiempo real calculados en Java sobre ventanas deslizantes de 1 y 15 minutos
 * (RF-12.1): vehículos detectados, ocupación de cada zona, permanencia promedio, reportes, FPS y
 * latencia de inferencia.
 */
public final class RealtimeAnalytics {

  private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
  private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);
  private static final Duration FPS_WINDOW = Duration.ofSeconds(5);
  private static final double LATENCY_SMOOTHING = 0.1;

  private record FrameSample(Instant at, Set<Long> trackIds, Set<Long> occupiedZones) {}

  private record TimedValue(Instant at, double value) {}

  private final Deque<FrameSample> frames = new ArrayDeque<>();
  private final Deque<TimedValue> dwellTimes = new ArrayDeque<>();
  private final Deque<Instant> reports = new ArrayDeque<>();
  private double inferenceLatencyMs;

  /** Registra un fotograma procesado. */
  public synchronized void recordFrame(
      Instant at, List<VehicleZoneStatus> vehicles, double latencyMs) {
    Set<Long> tracks = new HashSet<>();
    Set<Long> occupied = new HashSet<>();
    for (VehicleZoneStatus vehicle : vehicles) {
      tracks.add(vehicle.track().trackId());
      if (vehicle.zoneId() != null && vehicle.stationarySeconds() > 0) {
        occupied.add(vehicle.zoneId());
      }
    }
    frames.addLast(new FrameSample(at, tracks, occupied));
    inferenceLatencyMs =
        inferenceLatencyMs == 0
            ? latencyMs
            : inferenceLatencyMs + LATENCY_SMOOTHING * (latencyMs - inferenceLatencyMs);
    evict(at);
  }

  /** Registra la salida de un vehículo con su permanencia. */
  public synchronized void recordExit(Instant at, double dwellSeconds) {
    dwellTimes.addLast(new TimedValue(at, dwellSeconds));
  }

  /** Registra un reporte generado. */
  public synchronized void recordReport(Instant at) {
    reports.addLast(at);
  }

  /** Fotogramas por segundo en los últimos 5 segundos. */
  public synchronized double fps(Instant now) {
    Instant since = now.minus(FPS_WINDOW);
    long count = frames.stream().filter(frame -> frame.at().isAfter(since)).count();
    return Math.round(count * 100.0 / FPS_WINDOW.toSeconds()) / 100.0;
  }

  /** Indicadores para la página web. */
  public synchronized AnalyticsSnapshot snapshot(Instant now, Collection<Long> zoneIds) {
    evict(now);
    return new AnalyticsSnapshot(
        now,
        fps(now),
        Math.round(inferenceLatencyMs * 10) / 10.0,
        window(now.minus(ONE_MINUTE), zoneIds),
        window(now.minus(FIFTEEN_MINUTES), zoneIds));
  }

  private AnalyticsSnapshot.WindowMetrics window(Instant since, Collection<Long> zoneIds) {
    List<FrameSample> samples = frames.stream().filter(f -> !f.at().isBefore(since)).toList();
    Set<Long> vehicles = new HashSet<>();
    samples.forEach(sample -> vehicles.addAll(sample.trackIds()));
    Double avgDwell =
        dwellTimes.stream()
            .filter(d -> !d.at().isBefore(since))
            .mapToDouble(TimedValue::value)
            .average()
            .stream()
            .boxed()
            .findFirst()
            .map(value -> Math.round(value * 10) / 10.0)
            .orElse(null);
    int reportCount = (int) reports.stream().filter(at -> !at.isBefore(since)).count();
    List<AnalyticsSnapshot.ZoneOccupancy> occupancy =
        zoneIds.stream()
            .map(zoneId -> new AnalyticsSnapshot.ZoneOccupancy(zoneId, occupancy(samples, zoneId)))
            .toList();
    return new AnalyticsSnapshot.WindowMetrics(vehicles.size(), avgDwell, reportCount, occupancy);
  }

  private static double occupancy(List<FrameSample> samples, long zoneId) {
    if (samples.isEmpty()) {
      return 0;
    }
    long occupied = samples.stream().filter(s -> s.occupiedZones().contains(zoneId)).count();
    return Math.round(occupied * 10000.0 / samples.size()) / 100.0;
  }

  private void evict(Instant now) {
    Instant limit = now.minus(FIFTEEN_MINUTES);
    while (!frames.isEmpty() && frames.peekFirst().at().isBefore(limit)) {
      frames.removeFirst();
    }
    while (!dwellTimes.isEmpty() && dwellTimes.peekFirst().at().isBefore(limit)) {
      dwellTimes.removeFirst();
    }
    while (!reports.isEmpty() && reports.peekFirst().isBefore(limit)) {
      reports.removeFirst();
    }
  }
}
