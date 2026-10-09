package com.parkingwatch.edge.simulation;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.simulation.ScriptedVehicle.Keyframe;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Guion de la demostración con la maqueta (sección "Guion de la demostración"), usado para probar
 * el sistema completo sin cámara: un carro se estaciona en la zona amarilla más allá de la
 * tolerancia, una moto pasa sin detenerse y otro carro se detiene brevemente frente al garaje. El
 * guion se repite cada {@link #period()}.
 */
public final class MaquetaScenario {

  /** Vehículo visible en un instante con su recuadro. */
  public record Appearance(ScriptedVehicle vehicle, BoundingBox box) {}

  private final Instant start;
  private final Duration period;
  private final List<ScriptedVehicle> vehicles;

  public MaquetaScenario(Instant start, Duration period, List<ScriptedVehicle> vehicles) {
    this.start = start;
    this.period = period;
    this.vehicles = List.copyOf(vehicles);
  }

  /** Escenario por defecto para un cuadro de 1280 x 720 px y la zona amarilla de la maqueta. */
  public static MaquetaScenario defaultScenario(Instant start) {
    ScriptedVehicle parked =
        new ScriptedVehicle(
            "carro-zona-amarilla",
            VehicleType.CAR,
            "ABC123",
            List.of(
                frame(0, 0, 300, 160, 90),
                frame(4, 900, 400, 160, 90),
                frame(22, 900, 400, 160, 90),
                frame(26, 1110, 300, 160, 90)));
    ScriptedVehicle passing =
        new ScriptedVehicle(
            "moto-de-paso",
            VehicleType.MOTORCYCLE,
            "XYZ12A",
            List.of(frame(8, 0, 320, 60, 60), frame(14, 1210, 320, 60, 60)));
    ScriptedVehicle briefStop =
        new ScriptedVehicle(
            "carro-garaje",
            VehicleType.CAR,
            "DEF456",
            List.of(
                frame(30, 0, 300, 160, 90),
                frame(33, 430, 400, 160, 90),
                frame(38, 430, 400, 160, 90),
                frame(41, 0, 300, 160, 90)));
    return new MaquetaScenario(start, Duration.ofSeconds(50), List.of(parked, passing, briefStop));
  }

  private static Keyframe frame(int second, double x, double y, double width, double height) {
    return new Keyframe(Duration.ofSeconds(second), new BoundingBox(x, y, x + width, y + height));
  }

  public Duration period() {
    return period;
  }

  /** Vehículos visibles en el instante dado (posición dentro del ciclo del guion). */
  public List<Appearance> at(Instant instant) {
    long elapsed = Math.max(0, Duration.between(start, instant).toMillis());
    Duration t = Duration.ofMillis(elapsed % period.toMillis());
    List<Appearance> visible = new ArrayList<>();
    for (ScriptedVehicle vehicle : vehicles) {
      Optional<BoundingBox> box = vehicle.boxAt(t);
      box.ifPresent(value -> visible.add(new Appearance(vehicle, value)));
    }
    return visible;
  }
}
