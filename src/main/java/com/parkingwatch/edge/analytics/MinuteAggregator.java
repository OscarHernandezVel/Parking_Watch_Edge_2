package com.parkingwatch.edge.analytics;

import com.parkingwatch.common.contract.MinuteStatisticsMessage;
import com.parkingwatch.edge.zone.VehicleZoneStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Acumula los indicadores por minuto y por zona que el backend guarda para consultar tendencias por
 * hora y por día (RF-12.2). Al cambiar de minuto, el minuto anterior queda listo para enviarse.
 */
public final class MinuteAggregator {

  private static final class ZoneAccumulator {
    private final Set<Long> vehicles = new HashSet<>();
    private final List<Double> dwellTimes = new ArrayList<>();
    private int occupiedFrames;
  }

  private static final class MinuteAccumulator {
    private final Map<Long, ZoneAccumulator> zones = new HashMap<>();
    private int frames;
    private double confidenceSum;
    private int confidenceSamples;

    ZoneAccumulator zone(long zoneId) {
      return zones.computeIfAbsent(zoneId, id -> new ZoneAccumulator());
    }
  }

  private final NavigableMap<Instant, MinuteAccumulator> minutes = new TreeMap<>();

  /** Registra un fotograma. */
  public synchronized void recordFrame(
      Instant at, List<VehicleZoneStatus> vehicles, Collection<Long> zoneIds) {
    MinuteAccumulator minute =
        minutes.computeIfAbsent(truncate(at), key -> new MinuteAccumulator());
    minute.frames++;
    zoneIds.forEach(minute::zone);
    for (VehicleZoneStatus vehicle : vehicles) {
      minute.confidenceSum += vehicle.track().confidence();
      minute.confidenceSamples++;
      if (vehicle.zoneId() != null) {
        ZoneAccumulator zone = minute.zone(vehicle.zoneId());
        zone.vehicles.add(vehicle.track().trackId());
        if (vehicle.stationarySeconds() > 0) {
          zone.occupiedFrames++;
        }
      }
    }
  }

  /** Registra la permanencia de un vehículo que salió de una zona. */
  public synchronized void recordExit(Instant at, long zoneId, double dwellSeconds) {
    minutes
        .computeIfAbsent(truncate(at), key -> new MinuteAccumulator())
        .zone(zoneId)
        .dwellTimes
        .add(dwellSeconds);
  }

  /** Retira los minutos ya cerrados (anteriores al minuto actual). */
  public synchronized List<MinuteStatisticsMessage> drainCompleted(Instant now) {
    List<MinuteStatisticsMessage> completed = new ArrayList<>();
    Instant current = truncate(now);
    while (!minutes.isEmpty() && minutes.firstKey().isBefore(current)) {
      Map.Entry<Instant, MinuteAccumulator> entry = minutes.pollFirstEntry();
      completed.add(toMessage(entry.getKey(), entry.getValue()));
    }
    return completed;
  }

  private static MinuteStatisticsMessage toMessage(Instant minute, MinuteAccumulator data) {
    double fps = data.frames / 60.0;
    double confidence =
        data.confidenceSamples == 0 ? 0 : data.confidenceSum / data.confidenceSamples;
    List<MinuteStatisticsMessage.ZoneMinute> zones = new ArrayList<>();
    data.zones.forEach(
        (zoneId, zone) ->
            zones.add(
                new MinuteStatisticsMessage.ZoneMinute(
                    zoneId,
                    zone.vehicles.size(),
                    round(data.frames == 0 ? 0 : zone.occupiedFrames * 100.0 / data.frames),
                    zone.dwellTimes.isEmpty()
                        ? null
                        : (int)
                            Math.round(
                                zone.dwellTimes.stream()
                                    .mapToDouble(Double::doubleValue)
                                    .average()
                                    .orElse(0)),
                    round(fps),
                    round(confidence))));
    return new MinuteStatisticsMessage(minute, zones);
  }

  private static double round(double value) {
    return Math.round(value * 100) / 100.0;
  }

  private static Instant truncate(Instant at) {
    return at.truncatedTo(ChronoUnit.MINUTES);
  }
}
