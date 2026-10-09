package com.parkingwatch.edge.detection;

import com.parkingwatch.edge.domain.Detection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Supresión de no máximos por clase: elimina recuadros duplicados del mismo vehículo. */
public final class NonMaxSuppression {

  private final double iouThreshold;

  public NonMaxSuppression(double iouThreshold) {
    this.iouThreshold = iouThreshold;
  }

  /** Conserva la detección de mayor confianza entre las que se solapan más que el umbral. */
  public List<Detection> apply(List<Detection> candidates) {
    List<Detection> sorted = new ArrayList<>(candidates);
    sorted.sort(Comparator.comparingDouble(Detection::confidence).reversed());
    List<Detection> kept = new ArrayList<>();
    for (Detection candidate : sorted) {
      boolean suppressed =
          kept.stream()
              .anyMatch(
                  keptDetection ->
                      keptDetection.type() == candidate.type()
                          && keptDetection.box().iou(candidate.box()) > iouThreshold);
      if (!suppressed) {
        kept.add(candidate);
      }
    }
    return kept;
  }
}
