package com.parkingwatch.edge.zone;

import com.parkingwatch.edge.domain.ImagePoint;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decide si un vehículo está inmóvil (RF-10.3): el punto donde toca el piso debe desplazarse menos
 * que un umbral calibrado durante toda la ventana (2 s por defecto).
 */
public final class MotionAnalyzer {

  private record Sample(Instant at, ImagePoint anchor) {}

  private final double thresholdPx;
  private final Duration window;
  private final Map<Long, Deque<Sample>> history = new HashMap<>();
  private final Map<Long, Instant> stationarySince = new HashMap<>();

  public MotionAnalyzer(double thresholdPx, Duration window) {
    this.thresholdPx = thresholdPx;
    this.window = window;
  }

  /** Registra la posición y retorna desde cuándo está inmóvil, si lo está. */
  public Optional<Instant> update(long trackId, ImagePoint anchor, Instant at) {
    Deque<Sample> samples = history.computeIfAbsent(trackId, id -> new ArrayDeque<>());
    samples.addLast(new Sample(at, anchor));
    while (samples.size() > 2 && samples.peekFirst().at().isBefore(at.minus(window))) {
      samples.removeFirst();
    }
    boolean coversWindow = !samples.peekFirst().at().isAfter(at.minus(window));
    boolean still = samples.stream().allMatch(s -> s.anchor().distanceTo(anchor) < thresholdPx);
    if (coversWindow && still) {
      stationarySince.putIfAbsent(trackId, samples.peekFirst().at());
    } else if (!still) {
      stationarySince.remove(trackId);
    }
    return Optional.ofNullable(stationarySince.get(trackId));
  }

  /** Olvida las pistas que ya no existen. */
  public void retainOnly(Set<Long> aliveTrackIds) {
    history.keySet().retainAll(aliveTrackIds);
    stationarySince.keySet().retainAll(aliveTrackIds);
  }
}
