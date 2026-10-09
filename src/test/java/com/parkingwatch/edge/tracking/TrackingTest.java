package com.parkingwatch.edge.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.Detection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackingTest {

  @Test
  void matrixInverseRecoversIdentity() {
    double[][] a = {{4, 7}, {2, 6}};
    double[][] product = Matrix.multiply(a, Matrix.inverse(a));
    assertThat(product[0][0]).isCloseTo(1, within(1e-9));
    assertThat(product[0][1]).isCloseTo(0, within(1e-9));
    assertThatThrownBy(() -> Matrix.inverse(new double[][] {{1, 2}, {2, 4}}))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void kalmanFilterFollowsAMovingBox() {
    KalmanBoxFilter filter = new KalmanBoxFilter();
    KalmanBoxFilter.State state = filter.initiate(new BoundingBox(0, 0, 40, 20));
    for (int step = 1; step <= 20; step++) {
      state = filter.predict(state);
      state = filter.correct(state, new BoundingBox(step * 5, 0, step * 5 + 40, 20));
    }
    KalmanBoxFilter.State predicted = filter.predict(state);
    assertThat(predicted.box().center().x()).isCloseTo(125, within(3.0));
    assertThat(predicted.box().width()).isCloseTo(40, within(1.0));
  }

  @Test
  void linearAssignmentFindsTheOptimalPairsAndAppliesTheThreshold() {
    double[][] cost = {{0.9, 0.1, 0.5}, {0.2, 0.8, 0.9}};
    assertThat(LinearAssignment.solve(cost, 0.5))
        .containsExactlyInAnyOrder(
            new LinearAssignment.Match(0, 1), new LinearAssignment.Match(1, 0));
    double[][] tall = {{0.1}, {0.05}, {0.9}};
    assertThat(LinearAssignment.solve(tall, 1)).containsExactly(new LinearAssignment.Match(1, 0));
    assertThat(LinearAssignment.solve(new double[0][0], 1)).isEmpty();
    assertThat(LinearAssignment.solve(new double[][] {{0.95}}, 0.5)).isEmpty();
  }

  @Test
  void byteTrackKeepsStableIdsAcrossMissedFramesAndLowConfidence() {
    ByteTracker tracker = new ByteTracker(TrackerSettings.defaults());
    List<Long> ids = new ArrayList<>();
    for (int frame = 0; frame < 40; frame++) {
      double x = frame * 4;
      List<Detection> detections = new ArrayList<>();
      if (frame % 10 != 5) {
        double confidence = frame % 10 == 7 ? 0.3 : 0.9;
        detections.add(
            new Detection(new BoundingBox(x, 100, x + 80, 150), VehicleType.CAR, confidence));
      }
      detections.add(new Detection(new BoundingBox(900, 400, 980, 450), VehicleType.BUS, 0.8));
      tracker.update(detections).stream()
          .filter(track -> track.box().x1() < 600)
          .forEach(track -> ids.add(track.trackId()));
    }
    assertThat(ids).isNotEmpty().allMatch(id -> id.equals(ids.get(0)));
    assertThat(tracker.aliveTrackIds()).hasSize(2);
  }

  @Test
  void byteTrackConfirmsNewTracksOnTheSecondFrameAndExpiresLostOnes() {
    ByteTracker tracker = new ByteTracker(new TrackerSettings(0.5, 0.1, 0.6, 0.8, 3));
    Detection car = new Detection(new BoundingBox(0, 0, 50, 50), VehicleType.CAR, 0.9);
    assertThat(tracker.update(List.of(car))).hasSize(1);
    Detection newcomer =
        new Detection(new BoundingBox(500, 500, 550, 550), VehicleType.MOTORCYCLE, 0.9);
    assertThat(tracker.update(List.of(car, newcomer))).hasSize(1);
    List<TrackSnapshot> confirmed = tracker.update(List.of(car, newcomer));
    assertThat(confirmed).hasSize(2);
    assertThat(confirmed).anySatisfy(t -> assertThat(t.type()).isEqualTo(VehicleType.MOTORCYCLE));
    for (int i = 0; i < 5; i++) {
      tracker.update(List.of());
    }
    assertThat(tracker.aliveTrackIds()).isEmpty();
  }
}
