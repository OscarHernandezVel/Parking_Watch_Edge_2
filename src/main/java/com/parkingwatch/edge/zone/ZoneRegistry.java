package com.parkingwatch.edge.zone;

import com.parkingwatch.edge.domain.MonitoredZone;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zonas vigentes, reemplazables en caliente cuando el backend publica una nueva configuración
 * (RF-6.2): el pipeline lee siempre una lista inmutable y coherente.
 */
public final class ZoneRegistry {

  private final AtomicReference<List<MonitoredZone>> zones = new AtomicReference<>(List.of());

  public List<MonitoredZone> current() {
    return zones.get();
  }

  /** Reemplaza todas las zonas de forma atómica. */
  public void replace(List<MonitoredZone> updated) {
    zones.set(List.copyOf(updated));
  }
}
