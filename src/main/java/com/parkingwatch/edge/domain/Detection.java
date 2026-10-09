package com.parkingwatch.edge.domain;

import com.parkingwatch.common.domain.VehicleType;
import java.util.Objects;

/** Detección de un vehículo en un fotograma: recuadro, tipo y confianza del modelo. */
public record Detection(BoundingBox box, VehicleType type, double confidence) {

  /** Valida el rango de la confianza. */
  public Detection {
    Objects.requireNonNull(box, "box");
    Objects.requireNonNull(type, "type");
    if (confidence < 0 || confidence > 1) {
      throw new IllegalArgumentException("Confianza fuera de rango: " + confidence);
    }
  }
}
