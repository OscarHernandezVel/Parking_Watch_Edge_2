package com.parkingwatch.edge.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parkingwatch.common.domain.VehicleType;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.Detection;
import com.parkingwatch.edge.frame.Frame;
import com.parkingwatch.edge.support.Frames;
import java.nio.FloatBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetectionTest {

  @Test
  void letterboxScalesKeepingAspectAndMapsBack() {
    Letterbox letterbox = Letterbox.forFrame(1280, 720, 640);
    assertThat(letterbox.scale()).isEqualTo(0.5);
    assertThat(letterbox.padX()).isZero();
    assertThat(letterbox.padY()).isEqualTo(140);
    BoundingBox original = letterbox.toOriginal(new BoundingBox(100, 240, 200, 340), 1280, 720);
    assertThat(original).isEqualTo(new BoundingBox(200, 200, 400, 400));
  }

  @Test
  void preprocessorBuildsNormalizedRgbTensorWithGrayPadding() {
    Frame red = Frames.solid(64, 32, 0, 0, 255);
    ModelInput input = new LetterboxPreprocessor(64).prepare(red);
    FloatBuffer tensor = input.tensor();
    int plane = 64 * 64;
    assertThat(input.tensorLength()).isEqualTo(3 * plane);
    assertThat(tensor.get(0)).isCloseTo(114f / 255f, within(1e-6f));
    int center = 32 * 64 + 32;
    assertThat(tensor.get(center)).isCloseTo(1f, within(1e-6f));
    assertThat(tensor.get(plane + center)).isZero();
    assertThat(tensor.get(2 * plane + center)).isZero();
    assertThatThrownBy(() -> new LetterboxPreprocessor(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void yoloDecoderKeepsVehicleClassesAboveThreshold() {
    float[][] output = new float[4 + 8][3];
    setAnchor(output, 0, 100, 100, 20, 10);
    output[4 + 2][0] = 0.9f;
    setAnchor(output, 1, 50, 50, 10, 10);
    output[4 + 0][1] = 0.95f;
    setAnchor(output, 2, 70, 70, 10, 10);
    output[4 + 3][2] = 0.1f;
    List<Detection> detections = new YoloOutputDecoder(ClassMapping.coco(), 0.25).decode(output);
    assertThat(detections)
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.type()).isEqualTo(VehicleType.CAR);
              assertThat(d.box()).isEqualTo(new BoundingBox(90, 95, 110, 105));
            });
    assertThatThrownBy(
            () -> new YoloOutputDecoder(ClassMapping.coco(), 0.2).decode(new float[4][1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void classMappingsCoverCocoAndPrototypeModels() {
    assertThat(ClassMapping.coco().typeOf(7)).contains(VehicleType.TRUCK);
    assertThat(ClassMapping.coco().typeOf(0)).isEmpty();
    assertThat(ClassMapping.prototype().typeOf(1)).contains(VehicleType.MOTORCYCLE);
  }

  @Test
  void nmsSuppressesOverlappingBoxesOfTheSameClassOnly() {
    Detection best = new Detection(new BoundingBox(0, 0, 100, 100), VehicleType.CAR, 0.9);
    Detection duplicate = new Detection(new BoundingBox(5, 5, 100, 100), VehicleType.CAR, 0.6);
    Detection otherClass = new Detection(new BoundingBox(5, 5, 100, 100), VehicleType.BUS, 0.5);
    Detection far = new Detection(new BoundingBox(300, 300, 400, 400), VehicleType.CAR, 0.7);
    assertThat(new NonMaxSuppression(0.45).apply(List.of(duplicate, far, best, otherClass)))
        .containsExactly(best, far, otherClass);
  }

  @Test
  void hotSwappableDetectorReplacesAndClosesThePreviousModel() {
    Detector first = mock(Detector.class);
    Detector second = mock(Detector.class);
    when(second.modelVersion()).thenReturn("det-2");
    HotSwappableDetector proxy = new HotSwappableDetector(first);
    proxy.swap(second);
    verify(first).close();
    assertThat(proxy.modelVersion()).isEqualTo("det-2");
    ModelInput input = new LetterboxPreprocessor(32).prepare(Frames.black(32, 32));
    proxy.detect(input);
    verify(second).detect(input);
    proxy.close();
    verify(second).close();
    assertThat(DetectorSettings.defaults().inputSize()).isEqualTo(640);
  }

  private static void setAnchor(
      float[][] output, int anchor, float cx, float cy, float w, float h) {
    output[0][anchor] = cx;
    output[1][anchor] = cy;
    output[2][anchor] = w;
    output[3][anchor] = h;
  }
}
