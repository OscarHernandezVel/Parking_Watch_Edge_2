package com.parkingwatch.edge.pipeline;

import com.parkingwatch.edge.domain.Detection;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.tracking.TrackSnapshot;
import com.parkingwatch.edge.zone.VehicleZoneStatus;
import com.parkingwatch.edge.zone.ZoneEvent;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Datos que fluyen entre las etapas del pipeline (RF-9.3: el fotograma convertido en datos). */
public final class FrameData {

  private FrameData() {}

  /** Salida de la inferencia. */
  public record Detected(
      Frame frame, List<Detection> detections, Duration latency, String modelVersion) {

    /** Copia defensiva. */
    public Detected {
      detections = List.copyOf(detections);
    }

    /** Confianza promedio de las detecciones, o null si no hubo. */
    public Double meanConfidence() {
      return detections.isEmpty()
          ? null
          : detections.stream().mapToDouble(Detection::confidence).average().orElse(0);
    }
  }

  /** Salida del seguimiento. */
  public record Tracked(Detected detected, List<TrackSnapshot> tracks, Set<Long> aliveTrackIds) {

    /** Copias defensivas. */
    public Tracked {
      tracks = List.copyOf(tracks);
      aliveTrackIds = Set.copyOf(aliveTrackIds);
    }
  }

  /** Resultado final de un fotograma: vehículos con su zona y eventos de cambio. */
  public record Analysis(
      Detected detected, List<VehicleZoneStatus> vehicles, List<ZoneEvent> events) {

    /** Copias defensivas. */
    public Analysis {
      vehicles = List.copyOf(vehicles);
      events = List.copyOf(events);
    }

    public Frame frame() {
      return detected.frame();
    }
  }
}
