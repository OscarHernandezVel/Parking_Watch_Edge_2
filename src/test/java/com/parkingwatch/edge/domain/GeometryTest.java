package com.parkingwatch.edge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.parkingwatch.common.domain.VehicleType;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeometryTest {

  @Test
  void boundingBoxComputesIouAndGroundPoint() {
    BoundingBox a = new BoundingBox(0, 0, 10, 10);
    BoundingBox b = new BoundingBox(5, 0, 15, 10);
    assertThat(a.iou(b)).isCloseTo(50.0 / 150.0, within(1e-9));
    assertThat(a.iou(new BoundingBox(20, 20, 30, 30))).isZero();
    assertThat(a.bottomCenter()).isEqualTo(new ImagePoint(5, 10));
    assertThat(BoundingBox.fromCenter(5, 5, 10, 10)).isEqualTo(a);
  }

  @Test
  void boundingBoxClipsToFrameAndRejectsInvertedBoxes() {
    assertThat(new BoundingBox(-5, -5, 2000, 900).clip(1280, 720))
        .isEqualTo(new BoundingBox(0, 0, 1280, 720));
    assertThatThrownBy(() -> new BoundingBox(10, 0, 5, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void polygonContainsUsesRayCastingOnConcaveShapes() {
    ImagePolygon concave =
        new ImagePolygon(
            List.of(
                new ImagePoint(0, 0),
                new ImagePoint(200, 0),
                new ImagePoint(200, 200),
                new ImagePoint(100, 100),
                new ImagePoint(0, 200)));
    assertThat(concave.contains(new ImagePoint(100, 50))).isTrue();
    assertThat(concave.contains(new ImagePoint(100, 150))).isFalse();
    assertThatThrownBy(() -> new ImagePolygon(List.of(new ImagePoint(0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void detectionAndZoneValidateTheirFields() {
    assertThatThrownBy(() -> new Detection(new BoundingBox(0, 0, 1, 1), VehicleType.CAR, 1.5))
        .isInstanceOf(IllegalArgumentException.class);
    ImagePolygon square =
        new ImagePolygon(List.of(new ImagePoint(0, 0), new ImagePoint(1, 0), new ImagePoint(1, 1)));
    assertThatThrownBy(
            () ->
                new MonitoredZone(
                    1, "x", com.parkingwatch.common.domain.ZoneType.CORNER, square, 0, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ImagePoint(0, 0).distanceTo(new ImagePoint(3, 4))).isEqualTo(5);
  }
}
