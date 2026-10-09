package com.parkingwatch.edge.ocr;

import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.frame.Frame;
import java.util.Optional;

/** Lector nulo (patrón Null Object) cuando no hay modelo de OCR instalado. */
public final class NoOpPlateReader implements PlateReader {

  @Override
  public Optional<OcrResult> read(Frame frame, BoundingBox vehicleBox) {
    return Optional.empty();
  }
}
