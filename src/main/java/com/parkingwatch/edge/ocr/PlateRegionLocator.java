package com.parkingwatch.edge.ocr;

import com.parkingwatch.edge.domain.BoundingBox;

/**
 * Estrategia para ubicar la placa dentro del recuadro del vehículo. La implementación por defecto
 * toma la franja inferior central, donde va la placa en los carros a escala; se puede reemplazar
 * por un detector de placas sin cambiar el resto del pipeline.
 */
public interface PlateRegionLocator {

  BoundingBox locate(BoundingBox vehicleBox);

  /** Franja inferior: 45 % inferior del alto y 70 % central del ancho. */
  static PlateRegionLocator lowerBand() {
    return box -> {
      double margin = box.width() * 0.15;
      return new BoundingBox(
          box.x1() + margin, box.y2() - box.height() * 0.45, box.x2() - margin, box.y2());
    };
  }
}
