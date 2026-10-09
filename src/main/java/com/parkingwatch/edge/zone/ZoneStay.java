package com.parkingwatch.edge.zone;

import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.edge.domain.MonitoredZone;
import com.parkingwatch.edge.tracking.TrackSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Permanencia de un vehículo en una zona, modelada con el patrón State (afuera, adentro,
 * infracción). La histéresis evita que el vehículo "entre y salga" por errores de un fotograma
 * (RF-10.3): se exigen varios fotogramas seguidos para cambiar de estado.
 */
final class ZoneStay {

  /** Observación de un fotograma. */
  record Observation(
      TrackSnapshot track, boolean inside, Instant at, Optional<Instant> stationarySince) {}

  /** Resultado de una transición: siguiente estado y evento emitido (puede ser null). */
  private record Step(Phase phase, ZoneEvent event) {}

  /** Un estado decide la transición y el evento que se emite. */
  @FunctionalInterface
  private interface Phase {
    Step next(ZoneStay stay, Observation observation);
  }

  private static final Phase OUTSIDE = ZoneStay::whenOutside;
  private static final Phase INSIDE = ZoneStay::whenInside;
  private static final Phase VIOLATION = ZoneStay::whenViolation;

  private final MonitoredZone zone;
  private final int enterFrames;
  private final int exitFrames;
  private Phase phase = OUTSIDE;
  private int insideStreak;
  private int outsideStreak;
  private Instant firstInsideAt;
  private Instant enteredAt;

  ZoneStay(MonitoredZone zone, int enterFrames, int exitFrames) {
    this.zone = zone;
    this.enterFrames = enterFrames;
    this.exitFrames = exitFrames;
  }

  /** Procesa un fotograma y retorna el evento generado, si hubo transición. */
  Optional<ZoneEvent> observe(Observation observation) {
    Step step = phase.next(this, observation);
    phase = step.phase();
    return Optional.ofNullable(step.event());
  }

  boolean isInside() {
    return phase != OUTSIDE;
  }

  /** Segundos inmóvil dentro de la zona en el instante dado. */
  double stationarySeconds(Observation observation) {
    if (!isInside() || observation.stationarySince().isEmpty()) {
      return 0;
    }
    Instant since = latest(enteredAt, observation.stationarySince().get());
    return Math.max(0, Duration.between(since, observation.at()).toMillis() / 1000.0);
  }

  private Step whenOutside(Observation observation) {
    if (!observation.inside()) {
      insideStreak = 0;
      return stay(OUTSIDE);
    }
    if (insideStreak++ == 0) {
      firstInsideAt = observation.at();
    }
    if (insideStreak < enterFrames) {
      return stay(OUTSIDE);
    }
    enteredAt = firstInsideAt;
    outsideStreak = 0;
    return new Step(INSIDE, event(ParkingEventType.ZONE_ENTERED, observation, 0));
  }

  private Step whenInside(Observation observation) {
    Step exit = checkExit(observation);
    if (exit != null) {
      return exit;
    }
    double dwell = stationarySeconds(observation);
    if (zone.signaled() && dwell >= zone.toleranceSeconds()) {
      return new Step(VIOLATION, event(ParkingEventType.TOLERANCE_EXCEEDED, observation, dwell));
    }
    return stay(INSIDE);
  }

  private Step whenViolation(Observation observation) {
    Step exit = checkExit(observation);
    return exit != null ? exit : stay(VIOLATION);
  }

  /**
   * Retorna la salida si el vehículo lleva suficientes fotogramas afuera; null si sigue adentro.
   */
  private Step checkExit(Observation observation) {
    if (observation.inside()) {
      outsideStreak = 0;
      return null;
    }
    if (++outsideStreak < exitFrames) {
      return null;
    }
    double total = Duration.between(enteredAt, observation.at()).toMillis() / 1000.0;
    insideStreak = 0;
    return new Step(OUTSIDE, event(ParkingEventType.ZONE_EXITED, observation, total));
  }

  private static Step stay(Phase phase) {
    return new Step(phase, null);
  }

  private ZoneEvent event(ParkingEventType type, Observation observation, double dwellSeconds) {
    TrackSnapshot track = observation.track();
    return new ZoneEvent(
        type,
        zone,
        track.trackId(),
        track.type(),
        track.confidence(),
        track.box(),
        enteredAt,
        observation.at(),
        dwellSeconds);
  }

  private static Instant latest(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }
}
