package com.parkingwatch.edge.detection;

import com.parkingwatch.edge.domain.Detection;
import java.util.List;

/**
 * Puerto de detección de vehículos (patrón Strategy): YOLOv8n en ONNX Runtime para la operación
 * real o un detector simulado para la maqueta sin cámara.
 */
public interface Detector extends AutoCloseable {

  /** Detecta vehículos; los recuadros se entregan en coordenadas del fotograma original. */
  List<Detection> detect(ModelInput input);

  /** Versión del modelo que produjo las detecciones; viaja en cada reporte (RF-7.3). */
  String modelVersion();

  @Override
  default void close() {
    // Por defecto no hay recursos nativos que liberar.
  }
}
