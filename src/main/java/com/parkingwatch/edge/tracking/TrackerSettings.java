package com.parkingwatch.edge.tracking;

/**
 * Parámetros de ByteTrack.
 *
 * @param highThreshold confianza mínima de las detecciones de la primera asociación
 * @param lowThreshold confianza mínima de las detecciones débiles (segunda asociación)
 * @param newTrackThreshold confianza mínima para iniciar una pista
 * @param matchCost costo máximo (1 - IoU) en la primera asociación
 * @param maxLostFrames fotogramas que se conserva una pista perdida
 */
public record TrackerSettings(
    double highThreshold,
    double lowThreshold,
    double newTrackThreshold,
    double matchCost,
    int maxLostFrames) {

  /** Valores de referencia de ByteTrack para 10 a 15 fps. */
  public static TrackerSettings defaults() {
    return new TrackerSettings(0.5, 0.1, 0.6, 0.8, 30);
  }
}
