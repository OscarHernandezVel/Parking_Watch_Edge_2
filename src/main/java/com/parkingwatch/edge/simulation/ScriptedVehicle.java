package com.parkingwatch.edge.simulation;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Vehículo a escala con una trayectoria guionizada por cuadros clave; entre cuadros clave el
 * recuadro se interpola linealmente. Fuera del intervalo del guion el vehículo no está en escena.
 */
public record ScriptedVehicle(
    String key, VehicleType type, String plate, List<Keyframe> keyframes) {

  /** Posición del vehículo en un instante del guion. */
  public record Keyframe(Duration at, BoundingBox box) {}

  /** Copia defensiva y validación del orden de los cuadros clave. */
  public ScriptedVehicle {
    keyframes = List.copyOf(keyframes);
    if (keyframes.size() < 2) {
      throw new IllegalArgumentException("Se necesitan al menos dos cuadros clave");
    }
  }

  /** Recuadro del vehículo en el instante {@code t} del guion, si está en escena. */
  public Optional<BoundingBox> boxAt(Duration t) {
    for (int i = 1; i < keyframes.size(); i++) {
      Keyframe from = keyframes.get(i - 1);
      Keyframe to = keyframes.get(i);
      if (t.compareTo(from.at()) >= 0 && t.compareTo(to.at()) <= 0) {
        return Optional.of(interpolate(from, to, t));
      }
    }
    return Optional.empty();
  }

  private static BoundingBox interpolate(Keyframe from, Keyframe to, Duration t) {
    long span = to.at().minus(from.at()).toMillis();
    double ratio = span == 0 ? 0 : (double) t.minus(from.at()).toMillis() / span;
    BoundingBox a = from.box();
    BoundingBox b = to.box();
    return new BoundingBox(
        lerp(a.x1(), b.x1(), ratio),
        lerp(a.y1(), b.y1(), ratio),
        lerp(a.x2(), b.x2(), ratio),
        lerp(a.y2(), b.y2(), ratio));
  }

  private static double lerp(double a, double b, double ratio) {
    return a + (b - a) * ratio;
  }
}
