package com.parkingwatch.edge.detection;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.Detection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decodifica la salida de YOLOv8 exportado a ONNX: tensor [1, 4 + clases, anclas] donde las
 * primeras cuatro filas son cx, cy, w, h y el resto los puntajes por clase (sin objectness).
 */
public final class YoloOutputDecoder {

  private static final int BOX_ROWS = 4;

  private final ClassMapping classMapping;
  private final double confidenceThreshold;

  public YoloOutputDecoder(ClassMapping classMapping, double confidenceThreshold) {
    this.classMapping = classMapping;
    this.confidenceThreshold = confidenceThreshold;
  }

  /** Candidatos por encima del umbral, en coordenadas del espacio de entrada del modelo. */
  public List<Detection> decode(float[][] output) {
    if (output.length <= BOX_ROWS) {
      throw new IllegalArgumentException("Salida de YOLO sin filas de clase");
    }
    int anchors = output[0].length;
    List<Detection> candidates = new ArrayList<>();
    for (int anchor = 0; anchor < anchors; anchor++) {
      decodeAnchor(output, anchor).ifPresent(candidates::add);
    }
    return candidates;
  }

  private Optional<Detection> decodeAnchor(float[][] output, int anchor) {
    int bestClass = -1;
    float bestScore = 0;
    for (int row = BOX_ROWS; row < output.length; row++) {
      if (output[row][anchor] > bestScore) {
        bestScore = output[row][anchor];
        bestClass = row - BOX_ROWS;
      }
    }
    if (bestScore < confidenceThreshold) {
      return Optional.empty();
    }
    Optional<VehicleType> type = classMapping.typeOf(bestClass);
    if (type.isEmpty()) {
      return Optional.empty();
    }
    BoundingBox box =
        BoundingBox.fromCenter(
            output[0][anchor], output[1][anchor], output[2][anchor], output[3][anchor]);
    return Optional.of(new Detection(box, type.get(), Math.min(1.0, bestScore)));
  }
}
