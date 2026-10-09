package com.parkingwatch.edge.detection;

import com.parkingwatch.edge.domain.BoundingBox;

/**
 * Transformación letterbox: el fotograma se escala conservando la proporción y se rellena hasta el
 * cuadrado de entrada del modelo. Permite devolver los recuadros a la imagen original.
 */
public record Letterbox(double scale, double padX, double padY, int inputSize) {

  /** Calcula la transformación para un fotograma de ancho y alto dados. */
  public static Letterbox forFrame(int width, int height, int inputSize) {
    double scale = Math.min((double) inputSize / width, (double) inputSize / height);
    double padX = (inputSize - width * scale) / 2;
    double padY = (inputSize - height * scale) / 2;
    return new Letterbox(scale, padX, padY, inputSize);
  }

  /** Convierte un recuadro del espacio del modelo al espacio del fotograma original. */
  public BoundingBox toOriginal(BoundingBox modelBox, int frameWidth, int frameHeight) {
    return new BoundingBox(
            (modelBox.x1() - padX) / scale,
            (modelBox.y1() - padY) / scale,
            (modelBox.x2() - padX) / scale,
            (modelBox.y2() - padY) / scale)
        .clip(frameWidth, frameHeight);
  }
}
