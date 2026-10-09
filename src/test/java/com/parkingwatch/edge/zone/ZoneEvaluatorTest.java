package com.parkingwatch.edge.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.parkingwatch.common.domain.ParkingEventType;
import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.common.domain.ZoneType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.ImagePoint;
import com.parkingwatch.edge.domain.ImagePolygon;
import com.parkingwatch.edge.domain.MonitoredZone;
import com.parkingwatch.edge.tracking.TrackSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba del diseño de calidad: inyectar detecciones simuladas y verificar que se genera un solo
 * reporte al superar la tolerancia y ninguno antes.
 */
class ZoneEvaluatorTest {

  private static final Instant START = Instant.parse("2026-10-01T15:00:00Z");
  private static final BoundingBox INSIDE = new BoundingBox(900, 400, 1060, 490);
  private static final BoundingBox OUTSIDE = new BoundingBox(100, 150, 260, 240);

  private ZoneRegistry registry;
  private ZoneEvaluator evaluator;
  private final List<ZoneEvent> events = new ArrayList<>();

  @BeforeEach
  void setUp() {
    registry = new ZoneRegistry();
    registry.replace(List.of(yellowZone(), laneZone()));
    evaluator = new ZoneEvaluator(registry, new MotionAnalyzer(6, Duration.ofSeconds(2)), 3, 5);
  }

  @Test
  void emitsExactlyOneToleranceEventAfterTheToleranceAndNoneBefore() {
    run(INSIDE, 0, 100);
    assertThat(types()).containsExactly(ParkingEventType.ZONE_ENTERED);
    run(INSIDE, 100, 300);
    assertThat(types())
        .containsExactly(ParkingEventType.ZONE_ENTERED, ParkingEventType.TOLERANCE_EXCEEDED);
    ZoneEvent exceeded = events.get(1);
    assertThat(exceeded.dwellSeconds()).isBetween(10.0, 10.2);
    assertThat(exceeded.enteredAt()).isEqualTo(START);
    run(OUTSIDE, 300, 310);
    assertThat(types())
        .containsExactly(
            ParkingEventType.ZONE_ENTERED,
            ParkingEventType.TOLERANCE_EXCEEDED,
            ParkingEventType.ZONE_EXITED);
    assertThat(events.get(2).dwellSeconds()).isCloseTo(30.4, within(0.2));
  }

  @Test
  void hysteresisIgnoresSingleFrameGlitches() {
    run(INSIDE, 0, 20);
    run(OUTSIDE, 20, 22);
    run(INSIDE, 22, 40);
    assertThat(types()).containsExactly(ParkingEventType.ZONE_ENTERED);
  }

  @Test
  void movingVehiclesNeverExceedTheTolerance() {
    for (int frame = 0; frame < 300; frame++) {
      double shift = frame % 2 == 0 ? 0 : 20;
      observe(new BoundingBox(900 + shift, 400, 1060 + shift, 490), frame);
    }
    assertThat(types()).containsExactly(ParkingEventType.ZONE_ENTERED);
  }

  @Test
  void unsignaledZonesReportEntryAndExitButNoInfraction() {
    BoundingBox inLane = new BoundingBox(100, 290, 260, 380);
    run(inLane, 0, 200);
    run(OUTSIDE, 200, 210);
    assertThat(types())
        .containsExactly(ParkingEventType.ZONE_ENTERED, ParkingEventType.ZONE_EXITED);
    assertThat(events).allMatch(event -> event.zone().id() == 2);
  }

  @Test
  void aVanishedTrackExitsTheZoneAfterTheHysteresis() {
    run(INSIDE, 0, 20);
    for (int frame = 20; frame < 30; frame++) {
      events.addAll(evaluator.evaluate(List.of(), Set.of(), at(frame)).events());
    }
    assertThat(types())
        .containsExactly(ParkingEventType.ZONE_ENTERED, ParkingEventType.ZONE_EXITED);
  }

  @Test
  void reportsTheZoneAndStationaryTimeOfEachVehicle() {
    run(INSIDE, 0, 50);
    ZoneEvaluator.Evaluation evaluation =
        evaluator.evaluate(List.of(track(INSIDE)), Set.of(7L), at(50));
    VehicleZoneStatus status = evaluation.vehicles().get(0);
    assertThat(status.zoneId()).isEqualTo(1L);
    assertThat(status.stationarySeconds()).isGreaterThan(2.5);
  }

  @Test
  void removedZonesStopBeingEvaluated() {
    run(INSIDE, 0, 20);
    registry.replace(List.of(laneZone()));
    run(INSIDE, 20, 200);
    assertThat(types()).containsExactly(ParkingEventType.ZONE_ENTERED);
  }

  private void run(BoundingBox box, int fromFrame, int toFrame) {
    for (int frame = fromFrame; frame < toFrame; frame++) {
      observe(box, frame);
    }
  }

  private void observe(BoundingBox box, int frame) {
    events.addAll(evaluator.evaluate(List.of(track(box)), Set.of(7L), at(frame)).events());
  }

  private static TrackSnapshot track(BoundingBox box) {
    return new TrackSnapshot(7, box, VehicleType.CAR, 0.9);
  }

  private static Instant at(int frame) {
    return START.plusMillis(frame * 100L);
  }

  private List<ParkingEventType> types() {
    return events.stream().map(ZoneEvent::type).toList();
  }

  private static MonitoredZone yellowZone() {
    return new MonitoredZone(
        1, "Zona amarilla", ZoneType.YELLOW_ZONE, rectangle(820, 420, 1180, 560), 10, true);
  }

  private static MonitoredZone laneZone() {
    return new MonitoredZone(
        2, "Carril", ZoneType.TRAFFIC_LANE, rectangle(0, 260, 1280, 400), 10, false);
  }

  private static ImagePolygon rectangle(double x1, double y1, double x2, double y2) {
    return new ImagePolygon(
        List.of(
            new ImagePoint(x1, y1),
            new ImagePoint(x2, y1),
            new ImagePoint(x2, y2),
            new ImagePoint(x1, y2)));
  }
}
