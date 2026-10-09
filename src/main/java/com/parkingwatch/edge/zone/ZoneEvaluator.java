package com.parkingwatch.edge.zone;

import com.parkingwatch.edge.domain.ImagePoint;
import com.parkingwatch.edge.domain.MonitoredZone;
import com.parkingwatch.edge.tracking.TrackSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Evalúa en cada fotograma la relación entre los vehículos seguidos y las zonas no autorizadas
 * (PB-10): usa el punto central inferior del recuadro, la inmovilidad y la histéresis para emitir
 * los eventos de entrada, tolerancia superada y salida.
 */
public final class ZoneEvaluator {

  /** Resultado de un fotograma: estado por vehículo y eventos generados. */
  public record Evaluation(List<VehicleZoneStatus> vehicles, List<ZoneEvent> events) {

    /** Copias defensivas. */
    public Evaluation {
      vehicles = List.copyOf(vehicles);
      events = List.copyOf(events);
    }
  }

  private record StayKey(long trackId, long zoneId) {}

  private final ZoneRegistry zones;
  private final MotionAnalyzer motion;
  private final int enterFrames;
  private final int exitFrames;
  private final Map<StayKey, ZoneStay> stays = new HashMap<>();
  private final Map<Long, TrackSnapshot> lastSeen = new HashMap<>();

  public ZoneEvaluator(ZoneRegistry zones, MotionAnalyzer motion, int enterFrames, int exitFrames) {
    this.zones = zones;
    this.motion = motion;
    this.enterFrames = enterFrames;
    this.exitFrames = exitFrames;
  }

  /**
   * Evalúa un fotograma.
   *
   * @param tracks pistas activas en el fotograma
   * @param aliveTrackIds pistas aún vivas en el seguidor (incluye las perdidas recientemente)
   * @param at marca de tiempo de captura
   */
  public Evaluation evaluate(List<TrackSnapshot> tracks, Set<Long> aliveTrackIds, Instant at) {
    List<ZoneEvent> events = new ArrayList<>();
    List<VehicleZoneStatus> vehicles = new ArrayList<>();
    List<MonitoredZone> current = zones.current();
    Map<Long, TrackSnapshot> visible = new HashMap<>();
    for (TrackSnapshot track : tracks) {
      visible.put(track.trackId(), track);
      lastSeen.put(track.trackId(), track);
      ImagePoint anchor = track.box().bottomCenter();
      Optional<Instant> stationary = motion.update(track.trackId(), anchor, at);
      vehicles.add(observeTrack(track, anchor, stationary, current, at, events));
    }
    observeMissing(visible, at, events);
    cleanUp(aliveTrackIds, current);
    return new Evaluation(vehicles, events);
  }

  private VehicleZoneStatus observeTrack(
      TrackSnapshot track,
      ImagePoint anchor,
      Optional<Instant> stationary,
      List<MonitoredZone> current,
      Instant at,
      List<ZoneEvent> events) {
    Long zoneId = null;
    double stationarySeconds = 0;
    for (MonitoredZone zone : current) {
      ZoneStay stay =
          stays.computeIfAbsent(
              new StayKey(track.trackId(), zone.id()),
              key -> new ZoneStay(zone, enterFrames, exitFrames));
      ZoneStay.Observation observation =
          new ZoneStay.Observation(track, zone.polygon().contains(anchor), at, stationary);
      stay.observe(observation).ifPresent(events::add);
      if (stay.isInside() && zoneId == null) {
        zoneId = zone.id();
        stationarySeconds = stay.stationarySeconds(observation);
      }
    }
    return new VehicleZoneStatus(track, zoneId, stationarySeconds);
  }

  /** Las pistas que no aparecen en el fotograma cuentan como "afuera" para la histéresis. */
  private void observeMissing(
      Map<Long, TrackSnapshot> visible, Instant at, List<ZoneEvent> events) {
    stays.forEach(
        (key, stay) -> {
          if (!visible.containsKey(key.trackId()) && stay.isInside()) {
            TrackSnapshot track = lastSeen.get(key.trackId());
            stay.observe(new ZoneStay.Observation(track, false, at, Optional.empty()))
                .ifPresent(events::add);
          }
        });
  }

  private void cleanUp(Set<Long> aliveTrackIds, List<MonitoredZone> current) {
    Set<Long> zoneIds = new HashSet<>();
    current.forEach(zone -> zoneIds.add(zone.id()));
    stays
        .entrySet()
        .removeIf(
            entry ->
                !zoneIds.contains(entry.getKey().zoneId())
                    || (!aliveTrackIds.contains(entry.getKey().trackId())
                        && !entry.getValue().isInside()));
    lastSeen
        .keySet()
        .removeIf(
            id ->
                !aliveTrackIds.contains(id)
                    && stays.keySet().stream().noneMatch(k -> k.trackId() == id));
    motion.retainOnly(aliveTrackIds);
  }
}
