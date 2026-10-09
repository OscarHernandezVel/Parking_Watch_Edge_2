package com.parkingwatch.edge.domain;

/**
 * Recuadro de un vehículo en coordenadas de la imagen (esquinas superior izquierda e inferior
 * derecha).
 */
public record BoundingBox(double x1, double y1, double x2, double y2) {

  /** Valida que el recuadro no esté invertido. */
  public BoundingBox {
    if (x2 < x1 || y2 < y1) {
      throw new IllegalArgumentException(
          "Recuadro invertido: " + x1 + "," + y1 + "," + x2 + "," + y2);
    }
  }

  /** Crea un recuadro a partir de su centro y su tamaño (formato de salida de YOLO). */
  public static BoundingBox fromCenter(double cx, double cy, double width, double height) {
    return new BoundingBox(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
  }

  public double width() {
    return x2 - x1;
  }

  public double height() {
    return y2 - y1;
  }

  public double area() {
    return width() * height();
  }

  public ImagePoint center() {
    return new ImagePoint((x1 + x2) / 2, (y1 + y2) / 2);
  }

  /** Punto central inferior: donde el vehículo toca el piso (RF-10.3). */
  public ImagePoint bottomCenter() {
    return new ImagePoint((x1 + x2) / 2, y2);
  }

  /** Intersección sobre unión, base de la asociación de ByteTrack (RF-10.2). */
  public double iou(BoundingBox other) {
    double interWidth = Math.max(0, Math.min(x2, other.x2) - Math.max(x1, other.x1));
    double interHeight = Math.max(0, Math.min(y2, other.y2) - Math.max(y1, other.y1));
    double intersection = interWidth * interHeight;
    double union = area() + other.area() - intersection;
    return union <= 0 ? 0 : intersection / union;
  }

  /** Recorta el recuadro a los límites de un cuadro de ancho y alto dados. */
  public BoundingBox clip(int frameWidth, int frameHeight) {
    double cx1 = clamp(x1, frameWidth);
    double cy1 = clamp(y1, frameHeight);
    return new BoundingBox(
        cx1, cy1, Math.max(cx1, clamp(x2, frameWidth)), Math.max(cy1, clamp(y2, frameHeight)));
  }

  private static double clamp(double value, int max) {
    return Math.max(0, Math.min(max, value));
  }
}
