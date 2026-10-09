package com.parkingwatch.edge.detection;

import com.parkingwatch.edge.frame.Frame;
import java.nio.FloatBuffer;
import java.util.Objects;

/**
 * Entrada preparada para el modelo: tensor CHW (RGB normalizado a [0, 1]) más el fotograma original
 * y la transformación letterbox aplicada (RF-9.1, etapa de preprocesamiento).
 */
public final class ModelInput {

  private final Frame frame;
  private final float[] tensor;
  private final Letterbox letterbox;

  public ModelInput(Frame frame, float[] tensor, Letterbox letterbox) {
    this.frame = Objects.requireNonNull(frame, "frame");
    this.tensor = tensor.clone();
    this.letterbox = Objects.requireNonNull(letterbox, "letterbox");
  }

  public Frame frame() {
    return frame;
  }

  public Letterbox letterbox() {
    return letterbox;
  }

  /** Vista de solo lectura del tensor, lista para ONNX Runtime. */
  public FloatBuffer tensor() {
    return FloatBuffer.wrap(tensor).asReadOnlyBuffer();
  }

  public int tensorLength() {
    return tensor.length;
  }
}
