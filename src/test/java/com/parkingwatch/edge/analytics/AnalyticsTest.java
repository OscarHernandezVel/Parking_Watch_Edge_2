package com.parkingwatch.edge.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkingwatch.common.contract.AnalyticsSnapshot;
import com.parkingwatch.common.contract.DriftAlertMessage;
import com.parkingwatch.common.contract.MinuteStatisticsMessage;
import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.tracking.TrackSnapshot;
import com.parkingwatch.edge.zone.VehicleZoneStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnalyticsTest {

  private static final Instant START = Instant.parse("2026-10-01T15:00:00Z");

  private static VehicleZoneStatus vehicle(long id, Long zoneId, double stationary) {
    return new VehicleZoneStatus(
        new TrackSnapshot(id, new BoundingBox(0, 0, 10, 10), VehicleType.CAR, 0.9),
        zoneId,
        stationary);
  }

  @Test
  void realtimeAnalyticsAggregatesSlidingWindows() {
    RealtimeAnalytics analytics = new RealtimeAnalytics();
    for (int frame = 0; frame < 100; frame++) {
      Instant at = START.plusMillis(frame * 100L);
      List<VehicleZoneStatus> vehicles =
          frame < 50 ? List.of(vehicle(1, 1L, 3), vehicle(2, null, 0)) : List.of(vehicle(1, 1L, 8));
      analytics.recordFrame(at, vehicles, 80);
    }
    analytics.recordExit(START.plusSeconds(9), 12);
    analytics.recordReport(START.plusSeconds(9));
    Instant now = START.plusSeconds(10);
    AnalyticsSnapshot snapshot = analytics.snapshot(now, List.of(1L, 2L));
    assertThat(snapshot.fps()).isBetween(9.5, 10.0);
    assertThat(snapshot.inferenceLatencyMs()).isEqualTo(80.0);
    assertThat(snapshot.oneMinute().vehiclesDetected()).isEqualTo(2);
    assertThat(snapshot.oneMinute().avgDwellSeconds()).isEqualTo(12.0);
    assertThat(snapshot.oneMinute().reportsGenerated()).isEqualTo(1);
    assertThat(snapshot.oneMinute().zoneOccupancy())
        .containsExactly(
            new AnalyticsSnapshot.ZoneOccupancy(1, 100), new AnalyticsSnapshot.ZoneOccupancy(2, 0));
    AnalyticsSnapshot later = analytics.snapshot(START.plus(Duration.ofMinutes(20)), List.of(1L));
    assertThat(later.fifteenMinutes().vehiclesDetected()).isZero();
    assertThat(later.fifteenMinutes().avgDwellSeconds()).isNull();
  }

  @Test
  void minuteAggregatorClosesCompletedMinutesPerZone() {
    MinuteAggregator aggregator = new MinuteAggregator();
    for (int second = 0; second < 60; second++) {
      Instant at = START.plusSeconds(second);
      aggregator.recordFrame(at, List.of(vehicle(1, 1L, second >= 30 ? 1 : 0)), List.of(1L, 2L));
    }
    aggregator.recordExit(START.plusSeconds(59), 1, 25);
    assertThat(aggregator.drainCompleted(START.plusSeconds(59))).isEmpty();
    List<MinuteStatisticsMessage> completed = aggregator.drainCompleted(START.plusSeconds(61));
    assertThat(completed)
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.minute()).isEqualTo(START);
              MinuteStatisticsMessage.ZoneMinute zone1 =
                  message.zones().stream().filter(z -> z.zoneId() == 1).findFirst().orElseThrow();
              assertThat(zone1.vehiclesDetected()).isEqualTo(1);
              assertThat(zone1.occupancyPct()).isEqualTo(50.0);
              assertThat(zone1.avgDwellSeconds()).isEqualTo(25);
              assertThat(zone1.fps()).isEqualTo(1.0);
              assertThat(zone1.avgConfidence()).isEqualTo(0.9);
              assertThat(message.zones()).hasSize(2);
            });
  }

  @Test
  void driftMonitorAlertsWhenBrightnessDropsAfterWarmup() {
    DriftMonitor monitor =
        new DriftMonitor(new DriftMonitor.Settings(50, 0.15, 0.30, Duration.ofMinutes(10)));
    Instant at = START;
    for (int i = 0; i < 100; i++) {
      assertThat(monitor.record(0.9, 120, at)).isEmpty();
      at = at.plusMillis(100);
    }
    Optional<DriftAlertMessage> alert = Optional.empty();
    for (int i = 0; i < 100 && alert.isEmpty(); i++) {
      alert = monitor.record(0.9, 40, at);
      at = at.plusMillis(100);
    }
    assertThat(alert)
        .hasValueSatisfying(
            a -> {
              assertThat(a.alertType()).isEqualTo(DriftAlertMessage.Type.BRIGHTNESS_DRIFT);
              assertThat(a.observedValue()).isLessThan(a.baselineValue());
            });
    assertThat(monitor.record(0.9, 40, at)).isEmpty();
  }
}
