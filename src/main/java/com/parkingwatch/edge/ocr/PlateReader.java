package com.parkingwatch.edge.ocr;

import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import java.util.Optional;

/** Puerto de lectura de placas (Strategy): modelo ONNX real o lector simulado. */
public interface PlateReader extends AutoCloseable {

  /** Lee la placa del vehículo del recuadro; vacío si no hay lectura. */
  Optional<OcrResult> read(Frame frame, BoundingBox vehicleBox);

  @Override
  default void close() {
    // Por defecto no hay recursos nativos.
  }
}
