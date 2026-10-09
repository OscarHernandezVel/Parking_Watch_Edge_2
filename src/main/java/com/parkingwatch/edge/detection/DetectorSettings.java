package com.parkingwatch.edge.detection;

/** Parámetros de inferencia: tamaño de entrada y umbrales de confianza y de NMS. */
public record DetectorSettings(int inputSize, double confidenceThreshold, double iouThreshold) {

  /** Valores habituales de YOLOv8n. */
  public static DetectorSettings defaults() {
    return new DetectorSettings(640, 0.25, 0.45);
  }
}
