package com.parkingwatch.edge.detection;

import com.parkingwatch.edge.frame.Frame;
import java.util.Arrays;

/**
 * Preprocesamiento (RF-9.1): redimensiona a 640 px con interpolación bilineal conservando la
 * proporción, rellena con gris (114) como en el entrenamiento de Ultralytics y normaliza a [0, 1].
 */
public final class LetterboxPreprocessor {

  private static final float PAD_VALUE = 114f / 255f;
  private static final int[] BGR_TO_RGB = {2, 1, 0};

  private final int inputSize;

  public LetterboxPreprocessor(int inputSize) {
    if (inputSize <= 0) {
      throw new IllegalArgumentException("Tamaño de entrada inválido");
    }
    this.inputSize = inputSize;
  }

  /** Construye el tensor CHW del fotograma. */
  public ModelInput prepare(Frame frame) {
    Letterbox letterbox = Letterbox.forFrame(frame.width(), frame.height(), inputSize);
    int plane = inputSize * inputSize;
    float[] tensor = new float[plane * 3];
    Arrays.fill(tensor, PAD_VALUE);
    int scaledWidth = (int) Math.round(frame.width() * letterbox.scale());
    int scaledHeight = (int) Math.round(frame.height() * letterbox.scale());
    int offsetX = (int) Math.floor(letterbox.padX());
    int offsetY = (int) Math.floor(letterbox.padY());
    for (int y = 0; y < scaledHeight && y + offsetY < inputSize; y++) {
      double sourceY = Math.min(frame.height() - 1.0, (y + 0.5) / letterbox.scale() - 0.5);
      for (int x = 0; x < scaledWidth && x + offsetX < inputSize; x++) {
        double sourceX = Math.min(frame.width() - 1.0, (x + 0.5) / letterbox.scale() - 0.5);
        int target = (y + offsetY) * inputSize + x + offsetX;
        for (int c = 0; c < 3; c++) {
          tensor[c * plane + target] = bilinear(frame, sourceX, sourceY, BGR_TO_RGB[c]) / 255f;
        }
      }
    }
    return new ModelInput(frame, tensor, letterbox);
  }

  private static float bilinear(Frame frame, double x, double y, int channel) {
    int x0 = (int) Math.max(0, Math.floor(x));
    int y0 = (int) Math.max(0, Math.floor(y));
    int x1 = Math.min(frame.width() - 1, x0 + 1);
    int y1 = Math.min(frame.height() - 1, y0 + 1);
    double dx = Math.max(0, x - x0);
    double dy = Math.max(0, y - y0);
    double top = frame.channel(x0, y0, channel) * (1 - dx) + frame.channel(x1, y0, channel) * dx;
    double bottom = frame.channel(x0, y1, channel) * (1 - dx) + frame.channel(x1, y1, channel) * dx;
    return (float) (top * (1 - dy) + bottom * dy);
  }
}
