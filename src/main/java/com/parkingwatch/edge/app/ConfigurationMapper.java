package com.parkingwatch.edge.app;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.domain.ImagePoint;
import com.parkingwatch.edge.domain.ImagePolygon;
import com.parkingwatch.edge.domain.MonitoredZone;
import java.util.List;

/** Convierte la configuración del contrato en zonas del dominio del agente. */
final class ConfigurationMapper {

  private ConfigurationMapper() {}

  static List<MonitoredZone> zones(EdgeConfiguration configuration) {
    return configuration.zones().stream()
        .map(
            zone ->
                new MonitoredZone(
                    zone.id(),
                    zone.name(),
                    zone.zoneType(),
                    new ImagePolygon(
                        zone.polygon().stream().map(p -> new ImagePoint(p.x(), p.y())).toList()),
                    zone.toleranceSeconds(),
                    zone.signaled()))
        .toList();
  }
}
