package com.parkingwatch.edge.app;

import com.parkingwatch.common.contract.EdgeConfiguration;
import com.parkingwatch.edge.detection.Detector;
import com.parkingwatch.edge.detection.HotSwappableDetector;
import com.parkingwatch.edge.frame.FrameSource;
import com.parkingwatch.edge.ocr.PlateReader;
import com.parkingwatch.edge.simulation.MaquetaScenario;
import com.parkingwatch.edge.simulation.SimulatedDetector;
import com.parkingwatch.edge.simulation.SimulatedPlateReader;
import com.parkingwatch.edge.simulation.SyntheticFrameSource;
import java.time.Instant;
import java.util.function.Consumer;

/** Familia simulada: la maqueta dibujada, el detector y el OCR guionizados. */
public final class SimulatedHardware implements Hardware {

  private final MaquetaScenario scenario;
  private final SyntheticFrameSource source;
  private final String modelVersion;

  /**
   * Crea la simulación.
   *
   * @param start inicio del guion
   * @param fps fotogramas por segundo
   * @param pacer ritmo de emisión (tiempo real o sin espera en pruebas)
   * @param maxFrames fotogramas a emitir (0 = sin límite)
   * @param modelVersion versión de modelo que reporta el detector simulado
   */
  public SimulatedHardware(
      Instant start,
      double fps,
      SyntheticFrameSource.Pacer pacer,
      long maxFrames,
      String modelVersion) {
    this.scenario = MaquetaScenario.defaultScenario(start);
    this.source = new SyntheticFrameSource(scenario, start, fps, 1280, 720, pacer, maxFrames);
    this.modelVersion = modelVersion;
  }

  @Override
  public FrameSource frameSource() {
    return source;
  }

  @Override
  public Detector detector() {
    return new SimulatedDetector(scenario, modelVersion);
  }

  @Override
  public PlateReader plateReader() {
    return new SimulatedPlateReader(scenario);
  }

  @Override
  public Consumer<EdgeConfiguration> modelManager(HotSwappableDetector detector) {
    return configuration -> {
      // En simulación el detector es guionizado: no se descargan modelos.
    };
  }
}
