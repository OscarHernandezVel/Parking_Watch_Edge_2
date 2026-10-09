package com.parkingwatch.edge.detection;

import com.parkingwatch.common.domain.VehicleType;
import java.util.Map;
import java.util.Optional;

/**
 * Relación entre el índice de clase del modelo y el tipo de vehículo. El modelo base COCO usa
 * car=2, motorcycle=3, bus=5, truck=7; el modelo ajustado con la maqueta usa 0..3.
 */
public final class ClassMapping {

  private final Map<Integer, VehicleType> mapping;

  private ClassMapping(Map<Integer, VehicleType> mapping) {
    this.mapping = Map.copyOf(mapping);
  }

  /** Clases del modelo YOLOv8n preentrenado en COCO (80 clases). */
  public static ClassMapping coco() {
    return new ClassMapping(
        Map.of(
            2, VehicleType.CAR,
            3, VehicleType.MOTORCYCLE,
            5, VehicleType.BUS,
            7, VehicleType.TRUCK));
  }

  /** Clases del modelo ajustado con transfer learning sobre imágenes de la maqueta. */
  public static ClassMapping prototype() {
    return new ClassMapping(
        Map.of(
            0, VehicleType.CAR,
            1, VehicleType.MOTORCYCLE,
            2, VehicleType.BUS,
            3, VehicleType.TRUCK));
  }

  /** Tipo de vehículo de una clase; vacío si la clase no es un vehículo de interés. */
  public Optional<VehicleType> typeOf(int classIndex) {
    return Optional.ofNullable(mapping.get(classIndex));
  }
}
