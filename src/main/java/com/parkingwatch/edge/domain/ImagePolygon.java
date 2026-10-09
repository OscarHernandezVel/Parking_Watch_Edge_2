package com.parkingwatch.edge.domain;

import java.util.List;

/** Polígono de una zona no autorizada en coordenadas de la imagen (RF-4.1). */
public record ImagePolygon(List<ImagePoint> vertices) {

  /** Copia defensiva y validación de la cantidad mínima de vértices. */
  public ImagePolygon {
    vertices = List.copyOf(vertices);
    if (vertices.size() < 3) {
      throw new IllegalArgumentException("Un polígono necesita al menos 3 vértices");
    }
  }

  /** Prueba de pertenencia por el método del rayo. */
  public boolean contains(ImagePoint point) {
    boolean inside = false;
    int count = vertices.size();
    for (int i = 0, j = count - 1; i < count; j = i++) {
      ImagePoint a = vertices.get(i);
      ImagePoint b = vertices.get(j);
      boolean crosses = (a.y() > point.y()) != (b.y() > point.y());
      if (crosses && point.x() < (b.x() - a.x()) * (point.y() - a.y()) / (b.y() - a.y()) + a.x()) {
        inside = !inside;
      }
    }
    return inside;
  }
}
