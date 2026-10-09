package com.parkingwatch.edge.domain;

/** Punto en coordenadas de la imagen (píxeles, origen arriba a la izquierda). */
public record ImagePoint(double x, double y) {

  /** Distancia euclidiana a otro punto. */
  public double distanceTo(ImagePoint other) {
    return Math.hypot(x - other.x, y - other.y);
  }
}
