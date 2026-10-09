package com.parkingwatch.edge.domain;

import com.parkingwatch.common.domain.ZoneType;
import java.util.Objects;

/** Zona no autorizada que vigila el agente, recibida en la configuración del backend. */
public record MonitoredZone(
    long id,
    String name,
    ZoneType type,
    ImagePolygon polygon,
    int toleranceSeconds,
    boolean signaled) {

  /** Valida los campos obligatorios. */
  public MonitoredZone {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(polygon, "polygon");
    if (toleranceSeconds <= 0) {
      throw new IllegalArgumentException("La tolerancia debe ser positiva");
    }
  }
}
