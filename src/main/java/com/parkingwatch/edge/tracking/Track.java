package com.parkingwatch.edge.tracking;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.Detection;
import java.util.EnumMap;
import java.util.Map;

/** Pista de un vehículo: identificador estable, estado de Kalman y tipo por votación. */
public final class Track {

  private final long id;
  private final Map<VehicleType, Integer> typeVotes = new EnumMap<>(VehicleType.class);
  private KalmanBoxFilter.State kalman;
  private TrackState state = TrackState.NEW;
  private boolean activated;
  private double confidence;
  private long lastSeenFrame;

  Track(long id, Detection detection, KalmanBoxFilter filter, long frame, boolean activated) {
    this.id = id;
    this.kalman = filter.initiate(detection.box());
    this.confidence = detection.confidence();
    this.lastSeenFrame = frame;
    this.activated = activated;
    this.state = activated ? TrackState.TRACKED : TrackState.NEW;
    vote(detection.type());
  }

  void predict(KalmanBoxFilter filter) {
    if (state != TrackState.TRACKED) {
      kalman.mean()[7] = 0;
    }
    kalman = filter.predict(kalman);
  }

  void update(Detection detection, KalmanBoxFilter filter, long frame) {
    kalman = filter.correct(kalman, detection.box());
    confidence = detection.confidence();
    lastSeenFrame = frame;
    state = TrackState.TRACKED;
    activated = true;
    vote(detection.type());
  }

  void markLost() {
    state = TrackState.LOST;
  }

  void markRemoved() {
    state = TrackState.REMOVED;
  }

  private void vote(VehicleType type) {
    typeVotes.merge(type, 1, Integer::sum);
  }

  public long id() {
    return id;
  }

  public TrackState state() {
    return state;
  }

  public boolean isActivated() {
    return activated;
  }

  public long lastSeenFrame() {
    return lastSeenFrame;
  }

  public BoundingBox box() {
    return kalman.box();
  }

  public double confidence() {
    return confidence;
  }

  /** Tipo más votado a lo largo de la pista (estabiliza errores de clasificación). */
  public VehicleType type() {
    return typeVotes.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .orElse(VehicleType.CAR);
  }

  /** Copia inmutable para las etapas siguientes del pipeline. */
  public TrackSnapshot snapshot() {
    return new TrackSnapshot(id, box(), type(), confidence);
  }
}
