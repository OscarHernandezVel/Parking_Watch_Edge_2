package com.parkingwatch.edge.simulation;

import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.ModelInput;
import com.parkingwatch.edge.domain.BoundingBox;
import com.parkingwatch.edge.domain.Detection;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Detector simulado: devuelve los vehículos del guion con ruido determinista en el recuadro y la
 * confianza, y omite ocasionalmente una detección para ejercitar la recuperación de ByteTrack.
 */
public final class SimulatedDetector implements Detector {

  private static final double JITTER_PX = 1.5;
  private static final int DROP_EVERY = 23;

  private final MaquetaScenario scenario;
  private final String version;

  public SimulatedDetector(MaquetaScenario scenario, String version) {
    this.scenario = scenario;
    this.version = version;
  }

  @Override
  public List<Detection> detect(ModelInput input) {
    long sequence = input.frame().sequence();
    SplittableRandom random = new SplittableRandom(sequence * 31 + 7);
    List<Detection> detections = new ArrayList<>();
    for (MaquetaScenario.Appearance appearance : scenario.at(input.frame().capturedAt())) {
      if (sequence % DROP_EVERY == 0) {
        continue;
      }
      BoundingBox box = appearance.box();
      BoundingBox noisy =
          new BoundingBox(
              box.x1() + jitter(random), box.y1() + jitter(random),
              box.x2() + jitter(random), box.y2() + jitter(random));
      double confidence = 0.82 + random.nextDouble() * 0.15;
      detections.add(
          new Detection(
              noisy.clip(input.frame().width(), input.frame().height()),
              appearance.vehicle().type(),
              confidence));
    }
    return detections;
  }

  private static double jitter(SplittableRandom random) {
    return (random.nextDouble() * 2 - 1) * JITTER_PX;
  }

  @Override
  public String modelVersion() {
    return version;
  }
}
