package com.parkingwatch.edge.tracking;

import com.parkingwatch.edge.domain.Detection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementación en Java de ByteTrack (RF-10.2): asocia primero las detecciones de alta confianza
 * con todas las pistas y luego las de baja confianza con las pistas que quedaron sin asociar, de
 * modo que un vehículo conserva su identificador aunque el detector lo pierda en algunos fotogramas
 * o baje su confianza (oclusiones, reflejos).
 */
public final class ByteTracker {

  private static final double LOW_MATCH_COST = 0.5;
  private static final double UNCONFIRMED_MATCH_COST = 0.7;

  private final TrackerSettings settings;
  private final KalmanBoxFilter filter = new KalmanBoxFilter();
  private final List<Track> tracks = new ArrayList<>();
  private long frame;
  private long nextId = 1;

  public ByteTracker(TrackerSettings settings) {
    this.settings = settings;
  }

  /** Procesa las detecciones de un fotograma y retorna las pistas activas. */
  public List<TrackSnapshot> update(List<Detection> detections) {
    frame++;
    List<Detection> high = new ArrayList<>();
    List<Detection> low = new ArrayList<>();
    for (Detection detection : detections) {
      if (detection.confidence() >= settings.highThreshold()) {
        high.add(detection);
      } else if (detection.confidence() >= settings.lowThreshold()) {
        low.add(detection);
      }
    }
    List<Track> unconfirmed = tracks.stream().filter(t -> !t.isActivated()).toList();
    List<Track> pool = tracks.stream().filter(Track::isActivated).toList();
    pool.forEach(track -> track.predict(filter));

    List<Detection> remainingHigh = associate(pool, high, settings.matchCost());
    List<Track> unmatchedTracked =
        pool.stream()
            .filter(t -> t.lastSeenFrame() != frame && t.state() == TrackState.TRACKED)
            .toList();
    associate(unmatchedTracked, low, LOW_MATCH_COST);
    unmatchedTracked.stream().filter(t -> t.lastSeenFrame() != frame).forEach(Track::markLost);

    List<Detection> stillUnmatched = associate(unconfirmed, remainingHigh, UNCONFIRMED_MATCH_COST);
    unconfirmed.stream().filter(t -> t.lastSeenFrame() != frame).forEach(Track::markRemoved);

    startNewTracks(stillUnmatched);
    expireLostTracks();
    return tracks.stream()
        .filter(t -> t.state() == TrackState.TRACKED && t.isActivated())
        .map(Track::snapshot)
        .toList();
  }

  /** Asocia pistas y detecciones por IoU; retorna las detecciones que quedaron libres. */
  private List<Detection> associate(
      List<Track> candidates, List<Detection> detections, double maxCost) {
    if (candidates.isEmpty() || detections.isEmpty()) {
      return detections;
    }
    double[][] cost = new double[candidates.size()][detections.size()];
    for (int i = 0; i < candidates.size(); i++) {
      for (int j = 0; j < detections.size(); j++) {
        cost[i][j] = 1 - candidates.get(i).box().iou(detections.get(j).box());
      }
    }
    Set<Integer> used = new HashSet<>();
    for (LinearAssignment.Match match : LinearAssignment.solve(cost, maxCost)) {
      candidates.get(match.row()).update(detections.get(match.column()), filter, frame);
      used.add(match.column());
    }
    List<Detection> free = new ArrayList<>();
    for (int j = 0; j < detections.size(); j++) {
      if (!used.contains(j)) {
        free.add(detections.get(j));
      }
    }
    return free;
  }

  private void startNewTracks(List<Detection> detections) {
    for (Detection detection : detections) {
      if (detection.confidence() >= settings.newTrackThreshold()) {
        tracks.add(new Track(nextId++, detection, filter, frame, frame == 1));
      }
    }
  }

  private void expireLostTracks() {
    for (Track track : tracks) {
      if (track.state() == TrackState.LOST
          && frame - track.lastSeenFrame() > settings.maxLostFrames()) {
        track.markRemoved();
      }
    }
    tracks.removeIf(track -> track.state() == TrackState.REMOVED);
  }

  /** Identificadores de las pistas que siguen vivas (seguidas o perdidas recientemente). */
  public Set<Long> aliveTrackIds() {
    Set<Long> ids = new HashSet<>();
    tracks.forEach(track -> ids.add(track.id()));
    return ids;
  }
}
